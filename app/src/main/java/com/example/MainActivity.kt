package com.example

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Telephony
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {

    private val viewModel: SmsViewModel by viewModels()

    // Launcher for multi-permission request
    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        viewModel.checkStates()
    }

    // Launcher for default SMS app request
    private val defaultSmsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        viewModel.checkStates()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            MyApplicationTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    SmsAppContent(
                        viewModel = viewModel,
                        onRequestPermissions = { requestPermissions() },
                        onRequestDefaultSms = { requestDefaultSmsApp() }
                    )
                }
            }
        }

        // Handle intent if launched from notification
        handleNotificationIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleNotificationIntent(intent)
    }

    private fun handleNotificationIntent(intent: Intent?) {
        val senderNumber = intent?.getStringExtra("sender_number")
        if (!senderNumber.isNullOrEmpty()) {
            viewModel.selectNewRecipient(senderNumber)
        }
    }

    private fun requestPermissions() {
        val permissions = mutableListOf(
            android.Manifest.permission.SEND_SMS,
            android.Manifest.permission.RECEIVE_SMS,
            android.Manifest.permission.READ_SMS,
            android.Manifest.permission.READ_CONTACTS
        ).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        requestPermissionsLauncher.launch(permissions.toTypedArray())
    }

    private fun requestDefaultSmsApp() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val roleManager = getSystemService(android.app.role.RoleManager::class.java)
                if (roleManager != null && roleManager.isRoleAvailable(android.app.role.RoleManager.ROLE_SMS)) {
                    if (!roleManager.isRoleHeld(android.app.role.RoleManager.ROLE_SMS)) {
                        val intent = roleManager.createRequestRoleIntent(android.app.role.RoleManager.ROLE_SMS)
                        defaultSmsLauncher.launch(intent)
                        return
                    } else {
                        Toast.makeText(this, "यह ऐप पहले से ही डिफ़ॉल्ट एसएमएस ऐप है। / Already default.", Toast.LENGTH_SHORT).show()
                        viewModel.checkStates()
                        return
                    }
                }
            }
            
            val intent = Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT).apply {
                putExtra(Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, packageName)
            }
            defaultSmsLauncher.launch(intent)
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Failed to request default SMS role", e)
            Toast.makeText(this, "Could not open settings, please set default SMS manually.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.checkStates()
    }
}

// Navigation screen enum
enum class Screen {
    CONVERSATIONS_LIST,
    CHAT_DETAIL,
    NEW_CHAT
}

@OptIn(ExperimentalAnimationApi::class)
@Composable
fun SmsAppContent(
    viewModel: SmsViewModel,
    onRequestPermissions: () -> Unit,
    onRequestDefaultSms: () -> Unit
) {
    val permissionsGranted by viewModel.permissionsGranted.collectAsState()
    val isDefaultSms by viewModel.isDefaultSms.collectAsState()

    val activeThreadId by viewModel.activeThreadId.collectAsState()

    // Local screen route management
    var currentScreen by remember { mutableStateOf(Screen.CONVERSATIONS_LIST) }

    // Synchronize navigation screen with viewModel active states
    LaunchedEffect(activeThreadId) {
        currentScreen = when {
            activeThreadId != null -> Screen.CHAT_DETAIL
            else -> Screen.CONVERSATIONS_LIST
        }
    }

    if (!permissionsGranted) {
        PermissionOnboardingScreen(onRequestPermissions = onRequestPermissions)
    } else {
        AnimatedContent(
            targetState = currentScreen,
            transitionSpec = {
                if (targetState == Screen.CHAT_DETAIL || targetState == Screen.NEW_CHAT) {
                    slideInHorizontally { width -> width } + fadeIn() togetherWith
                            slideOutHorizontally { width -> -width } + fadeOut()
                } else {
                    slideInHorizontally { width -> -width } + fadeIn() togetherWith
                            slideOutHorizontally { width -> width } + fadeOut()
                }
            },
            label = "ScreenTransition"
        ) { screen ->
            when (screen) {
                Screen.CONVERSATIONS_LIST -> {
                    ConversationsListScreen(
                        viewModel = viewModel,
                        isDefaultSms = isDefaultSms,
                        onRequestDefaultSms = onRequestDefaultSms,
                        onNavigateToNewChat = { currentScreen = Screen.NEW_CHAT },
                        onNavigateToChat = { threadId, address ->
                            viewModel.selectThread(threadId, address)
                        }
                    )
                }
                Screen.CHAT_DETAIL -> {
                    ChatDetailScreen(
                        viewModel = viewModel,
                        isDefaultSms = isDefaultSms,
                        onRequestDefaultSms = onRequestDefaultSms,
                        onBack = { viewModel.clearActiveThread() }
                    )
                }
                Screen.NEW_CHAT -> {
                    NewChatScreen(
                        viewModel = viewModel,
                        onBack = { currentScreen = Screen.CONVERSATIONS_LIST },
                        onSelectContact = { number, name ->
                            viewModel.selectNewRecipient(number, name)
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun PermissionOnboardingScreen(onRequestPermissions: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
                        MaterialTheme.colorScheme.background
                    )
                )
            )
            .windowInsetsPadding(WindowInsets.safeDrawing),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Load custom generated welcome illustration
            Card(
                modifier = Modifier
                    .size(240.dp)
                    .clip(RoundedCornerShape(24.dp)),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
            ) {
                Image(
                    painter = painterResource(id = R.drawable.chat_welcome_hero),
                    contentDescription = "Welcome Illustration",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "SMS Messenger",
                style = MaterialTheme.typography.headlineLarge.copy(
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            )

            Text(
                text = "अपने दोस्तों और परिवार के साथ जुड़े रहें। संदेश भेजने और प्राप्त करने के लिए अनुमतियाँ दें।",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.padding(horizontal = 8.dp)
            )

            Text(
                text = "Stay connected with friends and family. Grant required permissions to load, send and receive SMS messages.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.padding(horizontal = 12.dp)
            )

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = onRequestPermissions,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .testTag("grant_permissions_button"),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "अनुमति दें / Grant Permissions",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
fun ConversationsListScreen(
    viewModel: SmsViewModel,
    isDefaultSms: Boolean,
    onRequestDefaultSms: () -> Unit,
    onNavigateToNewChat: () -> Unit,
    onNavigateToChat: (Long, String) -> Unit
) {
    val conversations by viewModel.filteredConversations.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    var showDeleteDialogForThread by remember { mutableStateOf<Long?>(null) }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .statusBarsPadding()
            ) {
                // Custom Search Bar inside top bar
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { viewModel.searchConversations(it) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .testTag("conversation_search_input"),
                    placeholder = { Text("संदेश या संपर्क खोजें... / Search...") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search") },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { viewModel.searchConversations("") }) {
                                Icon(Icons.Default.Clear, contentDescription = "Clear")
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(24.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = Color.Transparent
                    )
                )
            }
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onNavigateToNewChat,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shape = CircleShape,
                modifier = Modifier
                    .navigationBarsPadding()
                    .testTag("new_chat_fab")
            ) {
                Icon(Icons.Default.Add, contentDescription = "New Message", modifier = Modifier.size(28.dp))
            }
        },
        contentWindowInsets = WindowInsets.safeDrawing
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Hindi / English Default SMS Warning banner
            if (!isDefaultSms) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .clickable { onRequestDefaultSms() }
                        .testTag("default_sms_warning_banner"),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    shape = RoundedCornerShape(16.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = "Warning",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "डिफ़ॉल्ट एसएमएस ऐप सेट करें",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Text(
                                text = "संदेश भेजने और प्राप्त करने के लिए इस ऐप को डिफ़ॉल्ट बनाएं। नल करें।",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Set as default SMS app to enable sending and receiving features.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f)
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = onRequestDefaultSms,
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("Set Default", fontSize = 11.sp, color = Color.White)
                        }
                    }
                }
            }

            // Message threads list
            if (conversations.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(32.dp)
                    ) {
                        Icon(
                            Icons.Default.MailOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                            modifier = Modifier.size(64.dp)
                        )
                        Text(
                            text = "कोई संदेश नहीं मिला / No messages found",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "एक नई बातचीत शुरू करने के लिए '+' बटन दबाएं।",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(conversations) { item ->
                        ConversationItem(
                            conversation = item,
                            onClick = { onNavigateToChat(item.threadId, item.address) },
                            onLongClick = { showDeleteDialogForThread = item.threadId }
                        )
                    }
                }
            }
        }
    }

    // Delete confirmation dialog
    if (showDeleteDialogForThread != null) {
        AlertDialog(
            onDismissRequest = { showDeleteDialogForThread = null },
            title = { Text("बातचीत हटाएँ? / Delete thread?") },
            text = { Text("क्या आप इस बातचीत को हटाना चाहते हैं? यह क्रिया वापस नहीं ली जा सकती।\n\nAre you sure you want to delete this conversation? This action cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteDialogForThread?.let { viewModel.deleteConversation(it) }
                        showDeleteDialogForThread = null
                    }
                ) {
                    Text("हटाएं / Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialogForThread = null }) {
                    Text("रद्द करें / Cancel")
                }
            }
        )
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun ConversationItem(
    conversation: SmsConversationModel,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val formattedDate = remember(conversation.date) {
        val date = Date(conversation.date)
        val now = Calendar.getInstance()
        val msgTime = Calendar.getInstance().apply { time = date }

        if (now.get(Calendar.DATE) == msgTime.get(Calendar.DATE)) {
            SimpleDateFormat("hh:mm a", Locale.getDefault()).format(date)
        } else if (now.get(Calendar.WEEK_OF_YEAR) == msgTime.get(Calendar.WEEK_OF_YEAR)) {
            SimpleDateFormat("EEE", Locale.getDefault()).format(date)
        } else {
            SimpleDateFormat("MMM dd", Locale.getDefault()).format(date)
        }
    }

    val initials = remember(conversation.address, conversation.contactName) {
        val name = conversation.contactName ?: conversation.address
        name.split(" ")
            .filter { it.isNotEmpty() }
            .map { it[0] }
            .take(2)
            .joinToString("")
            .uppercase()
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .testTag("conversation_item_${conversation.threadId}"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Initials Avatar
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(CircleShape)
                .background(Color(conversation.avatarColor)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = if (initials.isEmpty()) "#" else initials,
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(modifier = Modifier.width(16.dp))

        // Text Info (Contact name, latest body text)
        Column(
            modifier = Modifier.weight(1f)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = conversation.contactName ?: conversation.address,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = if (!conversation.isRead) FontWeight.Bold else FontWeight.Normal,
                        color = if (!conversation.isRead) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onSurface
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                Text(
                    text = formattedDate,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontWeight = if (!conversation.isRead) FontWeight.Bold else FontWeight.Normal,
                        color = if (!conversation.isRead) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    ),
                    modifier = Modifier.padding(start = 8.dp)
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = conversation.body,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = if (!conversation.isRead) FontWeight.Bold else FontWeight.Normal,
                    color = if (!conversation.isRead) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onSurfaceVariant
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        // Unread Blue Dot
        if (!conversation.isRead) {
            Box(
                modifier = Modifier
                    .padding(start = 12.dp)
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatDetailScreen(
    viewModel: SmsViewModel,
    isDefaultSms: Boolean,
    onRequestDefaultSms: () -> Unit,
    onBack: () -> Unit
) {
    val messages by viewModel.currentMessages.collectAsState()
    val activeRecipient by viewModel.activeRecipient.collectAsState()
    val activeRecipientName by viewModel.activeRecipientName.collectAsState()
    val isSending by viewModel.isSending.collectAsState()

    var messageText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val keyboardController = LocalSoftwareKeyboardController.current

    // Auto-scroll to bottom of chat whenever messages list is populated or updated
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            coroutineScope.launch {
                listState.animateScrollToItem(messages.size - 1)
            }
        }
    }

    val context = LocalContext.current

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = activeRecipientName ?: activeRecipient ?: "Loading...",
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (activeRecipientName != null && activeRecipient != null) {
                            Text(
                                text = activeRecipient ?: "",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // Call Button to dial the recipient directly!
                    IconButton(
                        onClick = {
                            activeRecipient?.let {
                                val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$it"))
                                context.startActivity(intent)
                            }
                        }
                    ) {
                        Icon(Icons.Default.Phone, contentDescription = "Call Recipient")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        },
        bottomBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .navigationBarsPadding()
                    .imePadding()
            ) {
                // If not default SMS app, show warning banner inside chat screen
                if (!isDefaultSms) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.errorContainer)
                            .clickable { onRequestDefaultSms() }
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(Icons.Default.Error, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "नॉन-डिफ़ॉल्ट ऐप: संदेश भेजने के लिए टैप करें / Non-default: tap to configure",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = messageText,
                        onValueChange = { messageText = it },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("chat_message_input"),
                        placeholder = { Text("एसएमएस भेजें... / Send SMS...") },
                        maxLines = 4,
                        shape = RoundedCornerShape(24.dp),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Text,
                            imeAction = ImeAction.Default
                        ),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = Color.Transparent
                        )
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    IconButton(
                        onClick = {
                            if (messageText.trim().isNotEmpty() && !isSending) {
                                if (!isDefaultSms) {
                                    onRequestDefaultSms()
                                } else {
                                    val textToSend = messageText
                                    messageText = ""
                                    keyboardController?.hide()
                                    viewModel.sendCurrentSms(textToSend) { success ->
                                        if (!success) {
                                            messageText = textToSend // Restore on fail
                                            Toast.makeText(context, "Sms sending failed", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }
                            }
                        },
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(
                                if (messageText.trim().isEmpty() || isSending)
                                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
                                else
                                    MaterialTheme.colorScheme.primary
                            )
                            .testTag("chat_send_button"),
                        enabled = messageText.trim().isNotEmpty() && !isSending
                    ) {
                        if (isSending) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        } else {
                            Icon(
                                Icons.AutoMirrored.Filled.Send,
                                contentDescription = "Send Message",
                                tint = if (messageText.trim().isEmpty())
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                else
                                    MaterialTheme.colorScheme.onPrimary
                            )
                        }
                    }
                }
            }
        },
        contentWindowInsets = WindowInsets.safeDrawing
    ) { innerPadding ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.background),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            var lastDateText = ""
            items(messages) { message ->
                // Section date separator (e.g. July 5, 2026)
                val msgDateStr = SimpleDateFormat("MMMM dd, yyyy", Locale.getDefault()).format(Date(message.date))
                if (msgDateStr != lastDateText) {
                    lastDateText = msgDateStr
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(
                                text = msgDateStr,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            )
                        }
                    }
                }

                MessageBubble(message = message)
            }
        }
    }
}

@Composable
fun MessageBubble(message: SmsMessageModel) {
    val isSent = message.type == 2 // 2 is Sent, 1 is Inbox
    val bubbleColor = if (isSent) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }

    val textColor = if (isSent) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    val bubbleShape = if (isSent) {
        RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp, topEnd = 16.dp, bottomEnd = 2.dp)
    } else {
        RoundedCornerShape(topStart = 16.dp, bottomStart = 2.dp, topEnd = 16.dp, bottomEnd = 16.dp)
    }

    val timeStr = SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date(message.date))

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("message_bubble_${message.id}"),
        horizontalAlignment = if (isSent) Alignment.End else Alignment.Start
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 280.dp)
                .clip(bubbleShape)
                .background(bubbleColor)
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            Column {
                Text(
                    text = message.body,
                    color = textColor,
                    fontSize = 15.sp,
                    lineHeight = 20.sp
                )

                Spacer(modifier = Modifier.height(4.dp))

                Row(
                    modifier = Modifier.align(Alignment.End),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = timeStr,
                        color = textColor.copy(alpha = 0.7f),
                        fontSize = 10.sp
                    )
                    if (isSent) {
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            Icons.Default.Check,
                            contentDescription = "Delivered",
                            modifier = Modifier.size(12.dp),
                            tint = textColor.copy(alpha = 0.7f)
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewChatScreen(
    viewModel: SmsViewModel,
    onBack: () -> Unit,
    onSelectContact: (String, String) -> Unit
) {
    val filteredContacts by viewModel.filteredContacts.collectAsState()
    var rawInputQuery by remember { mutableStateOf("") }
    val keyboardController = LocalSoftwareKeyboardController.current

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("नई बातचीत / New Message", fontWeight = FontWeight.Bold, fontSize = 20.sp) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        },
        contentWindowInsets = WindowInsets.safeDrawing
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Input search for raw phone numbers or contact names
            OutlinedTextField(
                value = rawInputQuery,
                onValueChange = {
                    rawInputQuery = it
                    viewModel.searchContacts(it)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .testTag("contact_search_input"),
                placeholder = { Text("नाम या फ़ोन नंबर टाइप करें / Type name or phone...") },
                leadingIcon = { Icon(Icons.Default.Person, contentDescription = null) },
                trailingIcon = {
                    if (rawInputQuery.isNotEmpty()) {
                        IconButton(onClick = {
                            rawInputQuery = ""
                            viewModel.searchContacts("")
                        }) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear")
                        }
                    }
                },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Phone,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(
                    onDone = {
                        keyboardController?.hide()
                    }
                ),
                singleLine = true,
                shape = RoundedCornerShape(16.dp)
            )

            // Special option to directly message the typed query if it is a number
            val isNumberOnly = rawInputQuery.replace(Regex("[^0-9+]"), "").isNotEmpty()
            if (isNumberOnly && rawInputQuery.trim().length >= 3) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .clickable {
                            keyboardController?.hide()
                            onSelectContact(rawInputQuery, rawInputQuery)
                        }
                        .testTag("send_to_number_row"),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(16.dp))
                        Column {
                            Text(
                                text = "नंबर पर भेजें: $rawInputQuery",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Text(
                                text = "Send message to raw phone number",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
            }

            Text(
                text = "सभी संपर्क / All Contacts",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )

            if (filteredContacts.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "कोई संपर्क नहीं मिला / No contacts found",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(filteredContacts) { contact ->
                        ContactItem(
                            contact = contact,
                            onClick = {
                                keyboardController?.hide()
                                onSelectContact(contact.number, contact.name)
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ContactItem(
    contact: ContactModel,
    onClick: () -> Unit
) {
    // Select stable color for initials avatar
    val colorHash = Math.abs(contact.number.hashCode())
    val avatarColor = SmsHelper.avatarColors[colorHash % SmsHelper.avatarColors.size]

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .testTag("contact_item_${contact.number}"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(Color(avatarColor)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = contact.initials,
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(modifier = Modifier.width(16.dp))

        Column {
            Text(
                text = contact.name,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = contact.number,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
