package com.example

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.PhoneNumberUtils
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SmsViewModel(application: Application) : AndroidViewModel(application) {

    private val context: Context get() = getApplication()

    // UI state flows
    private val _isDefaultSms = MutableStateFlow(false)
    val isDefaultSms: StateFlow<Boolean> = _isDefaultSms.asStateFlow()

    private val _permissionsGranted = MutableStateFlow(false)
    val permissionsGranted: StateFlow<Boolean> = _permissionsGranted.asStateFlow()

    private val _conversations = MutableStateFlow<List<SmsConversationModel>>(emptyList())
    val conversations: StateFlow<List<SmsConversationModel>> = _conversations.asStateFlow()

    private val _filteredConversations = MutableStateFlow<List<SmsConversationModel>>(emptyList())
    val filteredConversations: StateFlow<List<SmsConversationModel>> = _filteredConversations.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _currentMessages = MutableStateFlow<List<SmsMessageModel>>(emptyList())
    val currentMessages: StateFlow<List<SmsMessageModel>> = _currentMessages.asStateFlow()

    private val _contactList = MutableStateFlow<List<ContactModel>>(emptyList())
    val contactList: StateFlow<List<ContactModel>> = _contactList.asStateFlow()

    private val _filteredContacts = MutableStateFlow<List<ContactModel>>(emptyList())
    val filteredContacts: StateFlow<List<ContactModel>> = _filteredContacts.asStateFlow()

    private val _activeThreadId = MutableStateFlow<Long?>(null)
    val activeThreadId: StateFlow<Long?> = _activeThreadId.asStateFlow()

    private val _activeRecipient = MutableStateFlow<String?>(null)
    val activeRecipient: StateFlow<String?> = _activeRecipient.asStateFlow()

    private val _activeRecipientName = MutableStateFlow<String?>(null)
    val activeRecipientName: StateFlow<String?> = _activeRecipientName.asStateFlow()

    private val _isSending = MutableStateFlow(false)
    val isSending: StateFlow<Boolean> = _isSending.asStateFlow()

    private val requiredPermissions = mutableListOf(
        android.Manifest.permission.SEND_SMS,
        android.Manifest.permission.RECEIVE_SMS,
        android.Manifest.permission.READ_SMS,
        android.Manifest.permission.READ_CONTACTS
    ).apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // Broadcast receiver to listen for real-time incoming SMS events from SmsReceiver
    private val smsRefreshReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.example.SMS_REFRESH") {
                refreshAll()
            }
        }
    }

    init {
        checkStates()
        
        // Register local SMS refresh receiver
        val filter = IntentFilter("com.example.SMS_REFRESH")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.registerReceiver(smsRefreshReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(smsRefreshReceiver, filter)
        }
    }

    override fun onCleared() {
        super.onCleared()
        try {
            context.unregisterReceiver(smsRefreshReceiver)
        } catch (e: Exception) {
            // Already unregistered or failed
        }
    }

    // Check permissions and default app status
    fun checkStates() {
        val allGranted = requiredPermissions.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
        _permissionsGranted.value = allGranted
        _isDefaultSms.value = SmsHelper.isDefaultSmsApp(context)

        if (allGranted) {
            refreshAll()
        }
    }

    // Refresh conversations and contact list
    fun refreshAll() {
        if (!_permissionsGranted.value) return

        viewModelScope.launch {
            // Load conversations
            val rawConversations = withContext(Dispatchers.IO) {
                SmsHelper.fetchConversations(context)
            }
            _conversations.value = rawConversations
            filterConversations(_searchQuery.value)

            // Load contacts for composer
            val rawContacts = withContext(Dispatchers.IO) {
                SmsHelper.fetchContactList(context)
            }
            _contactList.value = rawContacts
            _filteredContacts.value = rawContacts

            // If a thread is currently open, refresh its messages as well
            val activeId = _activeThreadId.value
            if (activeId != null) {
                loadThreadMessages(activeId)
            }
        }
    }

    // Filter conversations based on query
    fun searchConversations(query: String) {
        _searchQuery.value = query
        filterConversations(query)
    }

    private fun filterConversations(query: String) {
        if (query.trim().isEmpty()) {
            _filteredConversations.value = _conversations.value
        } else {
            val q = query.lowercase().trim()
            _filteredConversations.value = _conversations.value.filter {
                it.body.lowercase().contains(q) ||
                it.address.contains(q) ||
                (it.contactName?.lowercase()?.contains(q) ?: false)
            }
        }
    }

    // Search contacts for selection
    fun searchContacts(query: String) {
        if (query.trim().isEmpty()) {
            _filteredContacts.value = _contactList.value
        } else {
            val q = query.lowercase().trim()
            _filteredContacts.value = _contactList.value.filter {
                it.name.lowercase().contains(q) || it.number.contains(q)
            }
        }
    }

    // Select and load a message thread
    fun selectThread(threadId: Long, recipientAddress: String) {
        _activeThreadId.value = threadId
        _activeRecipient.value = recipientAddress
        
        // Find recipient name
        val name = _conversations.value.find { it.threadId == threadId }?.contactName 
            ?: _contactList.value.find { cleanEquals(it.number, recipientAddress) }?.name
        _activeRecipientName.value = name

        loadThreadMessages(threadId)

        // Mark thread as read
        viewModelScope.launch(Dispatchers.IO) {
            SmsHelper.markThreadAsRead(context, threadId)
            // Reload thread list to update read statuses
            val updatedConversations = SmsHelper.fetchConversations(context)
            _conversations.value = updatedConversations
            filterConversations(_searchQuery.value)
        }
    }

    // Clear active conversation
    fun clearActiveThread() {
        _activeThreadId.value = null
        _activeRecipient.value = null
        _activeRecipientName.value = null
        _currentMessages.value = emptyList()
        refreshAll()
    }

    // Initiate new conversation with a phone number or contact
    fun selectNewRecipient(number: String, name: String? = null) {
        // Search if a thread already exists for this number
        val existingThread = _conversations.value.find { cleanEquals(it.address, number) }
        if (existingThread != null) {
            selectThread(existingThread.threadId, existingThread.address)
        } else {
            // New thread placeholder
            _activeThreadId.value = -1L // Temporary custom ID for new thread
            _activeRecipient.value = number
            _activeRecipientName.value = name ?: number
            _currentMessages.value = emptyList()
        }
    }

    private fun cleanEquals(num1: String, num2: String): Boolean {
        val c1 = num1.replace(Regex("[^0-9]"), "")
        val c2 = num2.replace(Regex("[^0-9]"), "")
        if (c1.isEmpty() || c2.isEmpty()) return false
        return if (c1.length >= 10 && c2.length >= 10) {
            c1.takeLast(10) == c2.takeLast(10)
        } else {
            c1 == c2
        }
    }

    private fun loadThreadMessages(threadId: Long) {
        if (threadId == -1L) return
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) {
                SmsHelper.fetchMessagesForThread(context, threadId)
            }
            _currentMessages.value = list
        }
    }

    // Send SMS message to currently selected recipient
    fun sendCurrentSms(messageText: String, onComplete: (Boolean) -> Unit) {
        val recipient = _activeRecipient.value ?: return
        if (messageText.trim().isEmpty()) return

        _isSending.value = true
        viewModelScope.launch {
            val success = withContext(Dispatchers.IO) {
                SmsHelper.sendSms(context, recipient, messageText)
            }
            _isSending.value = false
            if (success) {
                // If it was a new thread (-1), reload conversations to find the newly created thread_id
                if (_activeThreadId.value == -1L) {
                    val rawConversations = withContext(Dispatchers.IO) {
                        SmsHelper.fetchConversations(context)
                    }
                    _conversations.value = rawConversations
                    filterConversations(_searchQuery.value)
                    
                    // Try to match the newly created thread
                    val newThread = rawConversations.find { cleanEquals(it.address, recipient) }
                    if (newThread != null) {
                        _activeThreadId.value = newThread.threadId
                    }
                }
                
                // Reload messages for the active thread
                val activeId = _activeThreadId.value
                if (activeId != null && activeId != -1L) {
                    loadThreadMessages(activeId)
                }
                
                // Refresh full list in background
                refreshAll()
            }
            onComplete(success)
        }
    }

    // Delete a conversation thread
    fun deleteConversation(threadId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            val success = SmsHelper.deleteThread(context, threadId)
            if (success) {
                if (_activeThreadId.value == threadId) {
                    withContext(Dispatchers.Main) {
                        clearActiveThread()
                    }
                } else {
                    refreshAll()
                }
            }
        }
    }
}
