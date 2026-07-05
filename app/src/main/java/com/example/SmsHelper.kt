package com.example

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import android.provider.Telephony
import android.telephony.SmsManager
import android.util.Log

data class SmsMessageModel(
    val id: Long,
    val threadId: Long,
    val address: String,
    val body: String,
    val date: Long,
    val type: Int, // 1 = inbox, 2 = sent
    val read: Int  // 0 = unread, 1 = read
)

data class SmsConversationModel(
    val threadId: Long,
    val address: String,
    val body: String,
    val date: Long,
    val isRead: Boolean,
    val contactName: String?,
    val avatarColor: Int
)

data class ContactModel(
    val name: String,
    val number: String,
    val initials: String
)

object SmsHelper {

    private const val TAG = "SmsHelper"

    // Colors for contact initials avatars
    val avatarColors = listOf(
        0xFF1A73E8.toInt(), // Blue
        0xFF34A853.toInt(), // Green
        0xFFEA4335.toInt(), // Red
        0xFFF9AB00.toInt(), // Yellow
        0xFF9C27B0.toInt(), // Purple
        0xFF00ACC1.toInt(), // Cyan
        0xFFE91E63.toInt(), // Pink
        0xFF673AB7.toInt(), // Deep Purple
        0xFF4CAF50.toInt(), // Light Green
        0xFFE65100.toInt()  // Orange
    )

    fun isDefaultSmsApp(context: Context): Boolean {
        val isDefault = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = context.getSystemService(Context.ROLE_SERVICE) as? android.app.role.RoleManager
            roleManager?.isRoleHeld(android.app.role.RoleManager.ROLE_SMS) == true
        } else {
            val defaultSmsPackage = Telephony.Sms.getDefaultSmsPackage(context)
            defaultSmsPackage == context.packageName
        }
        Log.d(TAG, "isDefaultSmsApp: $isDefault (ours: ${context.packageName})")
        return isDefault
    }

    // Helper to extract clean phone number
    private fun cleanNumber(number: String): String {
        return number.replace(Regex("[^0-9+]"), "")
    }

    // Fetch contact details to map phone numbers to names
    @SuppressLint("Range")
    fun fetchContacts(context: Context): Map<String, String> {
        val contactsMap = mutableMapOf<String, String>()
        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )

        try {
            val cursor: Cursor? = context.contentResolver.query(uri, projection, null, null, null)
            cursor?.use {
                val nameIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numberIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                while (it.moveToNext()) {
                    val name = it.getString(nameIndex)
                    val rawNumber = it.getString(numberIndex)
                    if (!rawNumber.isNullOrEmpty() && !name.isNullOrEmpty()) {
                        val cleaned = cleanNumber(rawNumber)
                        contactsMap[cleaned] = name
                        // Also store the raw or standard last 10 digits as fallback key
                        if (cleaned.length >= 10) {
                            contactsMap[cleaned.takeLast(10)] = name
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching contacts", e)
        }
        return contactsMap
    }

    // Fetch searchable list of unique contacts
    @SuppressLint("Range")
    fun fetchContactList(context: Context): List<ContactModel> {
        val list = mutableListOf<ContactModel>()
        val seenNumbers = mutableSetOf<String>()
        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )

        try {
            val cursor = context.contentResolver.query(uri, projection, null, null, "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC")
            cursor?.use {
                val nameIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                while (it.moveToNext()) {
                    val name = it.getString(nameIdx) ?: "Unknown"
                    val number = it.getString(numIdx) ?: ""
                    val cleaned = cleanNumber(number)
                    if (cleaned.isNotEmpty() && seenNumbers.add(cleaned)) {
                        val initials = name.split(" ")
                            .filter { part -> part.isNotEmpty() }
                            .map { part -> part[0] }
                            .take(2)
                            .joinToString("")
                            .uppercase()
                        list.add(ContactModel(name, number, if (initials.isEmpty()) "#" else initials))
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading contact list", e)
        }
        return list
    }

    // Load message threads (Conversations)
    @SuppressLint("Range")
    fun fetchConversations(context: Context): List<SmsConversationModel> {
        val conversationsMap = mutableMapOf<Long, SmsConversationModel>()
        val contactsMap = fetchContacts(context)
        val uri = Uri.parse("content://sms")
        val projection = arrayOf("_id", "thread_id", "address", "body", "date", "read", "type")

        try {
            // Fetch messages ordered by date DESC
            val cursor = context.contentResolver.query(uri, projection, null, null, "date DESC")
            cursor?.use {
                val idIdx = it.getColumnIndex("_id")
                val threadIdIdx = it.getColumnIndex("thread_id")
                val addressIdx = it.getColumnIndex("address")
                val bodyIdx = it.getColumnIndex("body")
                val dateIdx = it.getColumnIndex("date")
                val readIdx = it.getColumnIndex("read")
                val typeIdx = it.getColumnIndex("type")

                while (it.moveToNext()) {
                    val threadId = it.getLong(threadIdIdx)
                    // If we already have the latest message for this thread, skip
                    if (conversationsMap.containsKey(threadId)) {
                        continue
                    }

                    val rawAddress = it.getString(addressIdx) ?: ""
                    if (rawAddress.isEmpty()) continue

                    val body = it.getString(bodyIdx) ?: ""
                    val date = it.getLong(dateIdx)
                    val readVal = it.getInt(readIdx)
                    val isRead = readVal == 1

                    val cleanedAddress = cleanNumber(rawAddress)
                    var contactName: String? = contactsMap[cleanedAddress]
                    if (contactName == null && cleanedAddress.length >= 10) {
                        contactName = contactsMap[cleanedAddress.takeLast(10)]
                    }

                    // Create a stable avatar color index based on address
                    val colorHash = Math.abs(cleanedAddress.hashCode())
                    val avatarColor = avatarColors[colorHash % avatarColors.size]

                    conversationsMap[threadId] = SmsConversationModel(
                        threadId = threadId,
                        address = rawAddress,
                        body = body,
                        date = date,
                        isRead = isRead,
                        contactName = contactName,
                        avatarColor = avatarColor
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching conversations", e)
        }

        return conversationsMap.values.sortedByDescending { it.date }
    }

    // Load messages of a specific conversation thread
    @SuppressLint("Range")
    fun fetchMessagesForThread(context: Context, threadId: Long): List<SmsMessageModel> {
        val messages = mutableListOf<SmsMessageModel>()
        val uri = Uri.parse("content://sms")
        val projection = arrayOf("_id", "thread_id", "address", "body", "date", "read", "type")
        val selection = "thread_id = ?"
        val selectionArgs = arrayOf(threadId.toString())

        try {
            val cursor = context.contentResolver.query(uri, projection, selection, selectionArgs, "date ASC")
            cursor?.use {
                val idIdx = it.getColumnIndex("_id")
                val threadIdx = it.getColumnIndex("thread_id")
                val addressIdx = it.getColumnIndex("address")
                val bodyIdx = it.getColumnIndex("body")
                val dateIdx = it.getColumnIndex("date")
                val readIdx = it.getColumnIndex("read")
                val typeIdx = it.getColumnIndex("type")

                while (it.moveToNext()) {
                    messages.add(
                        SmsMessageModel(
                            id = it.getLong(idIdx),
                            threadId = it.getLong(threadIdx),
                            address = it.getString(addressIdx) ?: "",
                            body = it.getString(bodyIdx) ?: "",
                            date = it.getLong(dateIdx),
                            type = it.getInt(typeIdx),
                            read = it.getInt(readIdx)
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching messages for thread $threadId", e)
        }

        return messages
    }

    // Send an SMS message
    fun sendSms(context: Context, recipient: String, messageText: String): Boolean {
        if (recipient.trim().isEmpty() || messageText.trim().isEmpty()) {
            return false
        }

        val cleanRecipient = cleanNumber(recipient)
        if (cleanRecipient.isEmpty()) {
            return false
        }

        return try {
            val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                context.getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }

            // Standard SMS API handles splitting automatically or sends single text
            if (messageText.length > 160) {
                val parts = smsManager.divideMessage(messageText)
                smsManager.sendMultipartTextMessage(cleanRecipient, null, parts, null, null)
            } else {
                smsManager.sendTextMessage(cleanRecipient, null, messageText, null, null)
            }

            // Save sent message to standard ContentProvider so it appears in history
            saveSentSms(context, cleanRecipient, messageText)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send SMS to $cleanRecipient", e)
            false
        }
    }

    // Save sent SMS to system outbox/sent provider
    private fun saveSentSms(context: Context, recipient: String, messageText: String) {
        try {
            val values = ContentValues().apply {
                put("address", recipient)
                put("body", messageText)
                put("date", System.currentTimeMillis())
                put("read", 1) // Mark as read since we sent it
                put("type", 2) // 2 = sent (outbox)
            }
            val uri = context.contentResolver.insert(Uri.parse("content://sms/sent"), values)
            Log.d(TAG, "Saved sent SMS: $uri")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save sent SMS", e)
        }
    }

    // Mark all messages in a thread as read
    fun markThreadAsRead(context: Context, threadId: Long) {
        try {
            val values = ContentValues().apply {
                put("read", 1)
            }
            val selection = "thread_id = ? AND read = 0"
            val selectionArgs = arrayOf(threadId.toString())
            context.contentResolver.update(Uri.parse("content://sms"), values, selection, selectionArgs)
        } catch (e: Exception) {
            Log.e(TAG, "Error marking thread as read", e)
        }
    }

    // Delete a specific message thread
    fun deleteThread(context: Context, threadId: Long): Boolean {
        return try {
            val selection = "thread_id = ?"
            val selectionArgs = arrayOf(threadId.toString())
            val rowsDeleted = context.contentResolver.delete(Uri.parse("content://sms"), selection, selectionArgs)
            Log.d(TAG, "Deleted conversation thread $threadId, rows: $rowsDeleted")
            rowsDeleted > 0
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting thread $threadId", e)
            false
        }
    }
}
