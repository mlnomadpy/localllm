package com.localllm.app

import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.localllm.app.ui.AppTab
import com.localllm.app.ui.ChatTab
import com.localllm.app.ui.ConsoleTab
import com.localllm.app.ui.DashboardTab
import com.localllm.app.ui.DocumentsTab
import com.localllm.app.ui.ModelsTab
import com.localllm.app.ui.SettingsTab
import com.localllm.app.ui.StatusDot
import com.localllm.app.ui.UiMessage
import com.localllm.app.ui.sendChatMessage
import java.io.BufferedInputStream
import java.io.File
import java.security.DigestInputStream
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Top-level Activity. All it does is:
 *
 *   1. Own the cross-tab UI state (active tab, model list, chat messages…).
 *   2. Run lifecycle effects (file picker, model directory polling, autostart).
 *   3. Compose a Material 3 [Scaffold] with a compact top app bar and a 6-item
 *      bottom navigation bar; render the right tab's composable in the body.
 *
 * All tab contents and presentation logic live in [com.localllm.app.ui].
 */
class MainActivity : ComponentActivity() {

    private fun getModelFile(filename: String): File =
        File(getExternalFilesDir(null), filename)

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            val context = LocalContext.current
            val coroutineScope = rememberCoroutineScope()

            var existingModels by remember { mutableStateOf(setOf<String>()) }
            var modelSizes by remember { mutableStateOf(mapOf<String, Long>()) }
            var modelMtimes by remember { mutableStateOf(mapOf<String, Long>()) }
            val activeDownloads = remember { mutableStateMapOf<String, Long>() }
            val downloadProgress = remember { mutableStateMapOf<String, Float>() }
            val downloadTotalBytes = remember { mutableStateMapOf<String, Long>() }
            var activeTab by remember { mutableStateOf(AppTab.MODELS) }
            val logs by LogManager.logs.collectAsState(initial = LogEntry(0, "INFO", "Initializing..."))
            val logHistory = remember { mutableStateListOf<LogEntry>() }
            val listState = rememberLazyListState()

            val serverStatus by ServerState.status.collectAsState()
            val serverUrl by ServerState.boundUrl.collectAsState()

            val chatMessages = remember { mutableStateListOf<UiMessage>() }
            var chatInput by remember { mutableStateOf("") }
            var isChatting by remember { mutableStateOf(false) }
            val chatListState = rememberLazyListState()
            var selectedModel by remember { mutableStateOf("") }
            var chatJob by remember { mutableStateOf<Job?>(null) }
            var chatTokenRate by remember { mutableStateOf(0.0) }
            var chatTokenCount by remember { mutableStateOf(0) }
            var chatElapsedMs by remember { mutableStateOf(0L) }

            // Triggers passed to ChatTab so the top-app-bar action buttons can
            // drive sheet/dialog state living inside the tab. Incrementing the
            // counter is observed by a LaunchedEffect inside ChatTab.
            var systemPromptTrigger by remember { mutableStateOf(0) }
            var clearChatTrigger by remember { mutableStateOf(0) }

            var customUrls by remember { mutableStateOf(Settings.customModelUrls(context)) }

            LaunchedEffect(existingModels) {
                if (selectedModel.isEmpty() && existingModels.isNotEmpty()) {
                    selectedModel = existingModels.first().removeSuffix(".litertlm")
                }
            }

            LaunchedEffect(logs) {
                logHistory.add(logs)
                if (logHistory.size > 200) logHistory.removeAt(0)
                if (activeTab == AppTab.CONSOLE && logHistory.isNotEmpty()) {
                    listState.animateScrollToItem(logHistory.size - 1)
                }
            }

            LaunchedEffect(activeTab) {
                refreshExistingModels(context) { names, sizes, mtimes ->
                    existingModels = names
                    modelSizes = sizes
                    modelMtimes = mtimes
                }
                if (activeTab == AppTab.MODELS || activeTab == AppTab.CHAT) {
                    while (true) {
                        delay(2_000)
                        refreshExistingModels(context) { names, sizes, mtimes ->
                            existingModels = names
                            modelSizes = sizes
                            modelMtimes = mtimes
                        }
                    }
                }
            }

            LaunchedEffect(activeDownloads.toMap()) {
                val manager = context.getSystemService(DownloadManager::class.java)
                while (activeDownloads.isNotEmpty()) {
                    val toRemove = mutableListOf<String>()
                    for ((filename, downloadId) in activeDownloads) {
                        val query = DownloadManager.Query().setFilterById(downloadId)
                        val cursor = manager.query(query)
                        if (cursor.moveToFirst()) {
                            val statusIdx = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                            val downloadedIdx = cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                            val totalIdx = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                            if (statusIdx != -1) {
                                val status = cursor.getInt(statusIdx)
                                if (status == DownloadManager.STATUS_SUCCESSFUL || status == DownloadManager.STATUS_FAILED) {
                                    toRemove.add(filename)
                                    if (status == DownloadManager.STATUS_SUCCESSFUL) {
                                        coroutineScope.launch {
                                            val ok = withContext(Dispatchers.IO) {
                                                verifyDownloadedModel(filename)
                                            }
                                            if (ok == false) {
                                                existingModels = existingModels - filename
                                                Toast.makeText(
                                                    context,
                                                    "Model verification failed: $filename",
                                                    Toast.LENGTH_LONG
                                                ).show()
                                            }
                                        }
                                    }
                                }
                            }
                            if (downloadedIdx != -1 && totalIdx != -1) {
                                val downloaded = cursor.getLong(downloadedIdx)
                                val total = cursor.getLong(totalIdx)
                                if (total > 0) downloadProgress[filename] = downloaded.toFloat() / total
                                downloadTotalBytes[filename] = total
                            }
                        } else {
                            toRemove.add(filename)
                        }
                        cursor.close()
                    }
                    toRemove.forEach {
                        activeDownloads.remove(it)
                        downloadProgress.remove(it)
                        downloadTotalBytes.remove(it)
                    }
                    delay(1_000)
                }
            }

            var hasNotificationPermission by remember {
                mutableStateOf(
                    if (Build.VERSION.SDK_INT >= 33) {
                        ContextCompat.checkSelfPermission(context, "android.permission.POST_NOTIFICATIONS") == PackageManager.PERMISSION_GRANTED
                    } else true
                )
            }

            val permLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestPermission(),
                onResult = { hasNotificationPermission = it }
            )

            val filePickerLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.OpenDocument()
            ) { uri: Uri? ->
                if (uri != null) {
                    coroutineScope.launch {
                        try {
                            val cursor = context.contentResolver.query(uri, null, null, null, null)
                            val nameIndex = cursor?.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                            cursor?.moveToFirst()
                            var name = if (nameIndex != null && nameIndex >= 0)
                                cursor?.getString(nameIndex) ?: "imported_model.litertlm"
                            else "imported_model.litertlm"
                            cursor?.close()
                            if (!name.endsWith(".litertlm")) name = "$name.litertlm"

                            val destFile = getModelFile(name)
                            context.contentResolver.openInputStream(uri)?.use { input ->
                                destFile.outputStream().use { output -> input.copyTo(output) }
                            }
                            existingModels = existingModels + name
                            LogManager.i("FilePicker", "Imported $name")
                        } catch (e: Exception) {
                            LogManager.e("FilePicker", "Failed to import model", e)
                        }
                    }
                }
            }

            // Autostart no longer requires a `.litertlm` on disk — AICore
            // (the default model) is a virtual catalog entry served by the
            // system, so a fresh install with zero local models should still
            // bring the server up. LiteRT models remain selectable on top.
            LaunchedEffect(hasNotificationPermission) {
                if (!hasNotificationPermission && Build.VERSION.SDK_INT >= 33) {
                    permLauncher.launch("android.permission.POST_NOTIFICATIONS")
                } else if (Settings.autostart(context) &&
                    serverStatus == ServerState.Status.STOPPED
                ) {
                    startServer()
                }
            }

            val lifecycleOwner = LocalLifecycleOwner.current
            DisposableEffect(lifecycleOwner) {
                val observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_START &&
                        ServerState.status.value == ServerState.Status.STOPPED &&
                        Settings.autostart(context) &&
                        hasNotificationPermission
                    ) {
                        startServer()
                    }
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
            }

            val copyUrl: () -> Unit = {
                val url = serverUrl
                if (url != null) {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.app_name), url))
                    Toast.makeText(context, getString(R.string.toast_url_copied, url), Toast.LENGTH_SHORT).show()
                }
            }

            MaterialTheme(colorScheme = DarkColors) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    Scaffold(
                        topBar = {
                            CompactAppBar(
                                serverStatus = serverStatus,
                                serverUrl = serverUrl,
                                onCopyUrl = copyUrl,
                                activeTab = activeTab,
                                onOpenSystemPrompt = { systemPromptTrigger++ },
                                onClearChat = { clearChatTrigger++ },
                            )
                        },
                        bottomBar = {
                            AppNavigationBar(
                                activeTab = activeTab,
                                onTabSelected = { activeTab = it },
                            )
                        },
                        containerColor = MaterialTheme.colorScheme.background,
                    ) { paddingValues ->
                        Box(
                            modifier = Modifier
                                .padding(paddingValues)
                                .fillMaxSize()
                        ) {
                            when (activeTab) {
                                AppTab.MODELS -> Box(Modifier.padding(16.dp)) {
                                    ModelsTab(
                                        builtIn = AVAILABLE_MODELS,
                                        customUrls = customUrls,
                                        existingModels = existingModels,
                                        modelSizes = modelSizes,
                                        modelMtimes = modelMtimes,
                                        activeDownloads = activeDownloads,
                                        downloadProgress = downloadProgress,
                                        downloadTotalBytes = downloadTotalBytes,
                                        onDownload = { model ->
                                            activeDownloads[model.filename] = startDownload(context, model)
                                        },
                                        onCancel = { model ->
                                            val id = activeDownloads[model.filename]
                                            if (id != null) {
                                                val manager = context.getSystemService(DownloadManager::class.java)
                                                manager.remove(id)
                                                activeDownloads.remove(model.filename)
                                                downloadProgress.remove(model.filename)
                                                downloadTotalBytes.remove(model.filename)
                                            }
                                        },
                                        onDelete = { model ->
                                            getModelFile(model.filename).delete()
                                            existingModels = existingModels - model.filename
                                        },
                                        onImport = { filePickerLauncher.launch(arrayOf("*/*")) }
                                    )
                                }
                                AppTab.DASHBOARD -> Box(Modifier.padding(16.dp)) {
                                    DashboardTab(coroutineScope = coroutineScope)
                                }
                                AppTab.CONSOLE -> Box(Modifier.padding(16.dp)) {
                                    ConsoleTab(logHistory = logHistory, listState = listState)
                                }
                                AppTab.CHAT -> ChatTab(
                                    existingModels = existingModels,
                                    selectedModel = selectedModel,
                                    onModelChange = { selectedModel = it },
                                    chatMessages = chatMessages,
                                    chatInput = chatInput,
                                    onInputChange = { chatInput = it },
                                    isChatting = isChatting,
                                    onSend = { systemPrompt ->
                                        val toSend = chatInput.trim()
                                        val url = ServerState.boundUrl.value
                                        if (toSend.isNotEmpty() && !isChatting && url != null) {
                                            chatInput = ""
                                            chatTokenRate = 0.0
                                            chatTokenCount = 0
                                            chatElapsedMs = 0L
                                            chatJob = sendChatMessage(
                                                messages = chatMessages,
                                                input = toSend,
                                                model = selectedModel,
                                                baseUrl = url,
                                                apiKey = Settings.apiKey(context),
                                                coroutineScope = coroutineScope,
                                                listState = chatListState,
                                                onChattingChange = { isChatting = it },
                                                systemPrompt = systemPrompt,
                                                onTokenRate = { rate, count, elapsed ->
                                                    chatTokenRate = rate
                                                    chatTokenCount = count
                                                    chatElapsedMs = elapsed
                                                }
                                            )
                                        }
                                    },
                                    onStop = { chatJob?.cancel() },
                                    chatListState = chatListState,
                                    tokenRate = chatTokenRate,
                                    tokenCount = chatTokenCount,
                                    streamElapsedMs = chatElapsedMs,
                                    openSystemPromptTrigger = systemPromptTrigger,
                                    clearChatTrigger = clearChatTrigger,
                                    onClearChat = { chatMessages.clear() },
                                )
                                AppTab.DOCUMENTS -> Box(Modifier.padding(16.dp)) {
                                    DocumentsTab(
                                        serverStatus = serverStatus,
                                        baseUrl = serverUrl,
                                        apiKey = Settings.apiKey(context),
                                    )
                                }
                                AppTab.SETTINGS -> Box(Modifier.padding(16.dp)) {
                                    SettingsTab(
                                        context = context,
                                        serverStatus = serverStatus,
                                        customUrls = customUrls,
                                        onCustomUrlsChange = {
                                            customUrls = it
                                            Settings.setCustomModelUrls(context, it)
                                        },
                                        onStartServer = { startServer() },
                                        onStopServer = { stopServer() }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun startDownload(context: Context, model: ModelInfo): Long {
        val file = getModelFile(model.filename)
        file.parentFile?.mkdirs()

        val request = DownloadManager.Request(Uri.parse(model.url))
            .setTitle("Downloading ${model.name}")
            .setDescription("Required for LocalLLM Service")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(context, null, model.filename)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)

        val manager = context.getSystemService(DownloadManager::class.java)
        return manager.enqueue(request)
    }

    private fun startServer() {
        startForegroundService(Intent(this, LLMServerService::class.java))
    }

    private fun stopServer() {
        stopService(Intent(this, LLMServerService::class.java))
    }

    private fun verifyDownloadedModel(filename: String): Boolean? {
        val expected = AVAILABLE_MODELS.firstOrNull { it.filename == filename }?.sha256
        if (expected == null) {
            LogManager.w("DownloadVerify", "$filename has no expected hash; skipping verification")
            return null
        }
        val file = getModelFile(filename)
        if (!file.exists()) {
            LogManager.e("DownloadVerify", "$filename missing on disk; cannot verify")
            return false
        }
        val digest = MessageDigest.getInstance("SHA-256")
        DigestInputStream(BufferedInputStream(file.inputStream(), 64 * 1024), digest).use { input ->
            val buf = ByteArray(64 * 1024)
            while (input.read(buf) != -1) { /* digest updated as a side effect */ }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        return if (actual == expected.lowercase()) {
            LogManager.i("DownloadVerify", "Verified $filename (sha256 match)")
            true
        } else {
            LogManager.e(
                "DownloadVerify",
                "Hash mismatch for $filename: expected=$expected actual=$actual"
            )
            file.delete()
            false
        }
    }
}

/**
 * Compact single-line top app bar. Leading status dot, middle-truncated URL
 * title (italic placeholder when server is stopped/starting), and contextual
 * trailing actions:
 *
 *  - Chat tab: Tune (system prompt) + Refresh (clear chat).
 *  - All other tabs: Copy URL.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CompactAppBar(
    serverStatus: ServerState.Status,
    serverUrl: String?,
    onCopyUrl: () -> Unit,
    activeTab: AppTab,
    onOpenSystemPrompt: () -> Unit,
    onClearChat: () -> Unit,
) {
    CenterAlignedTopAppBar(
        title = {
            val (label, italic) = when {
                serverStatus == ServerState.Status.RUNNING && serverUrl != null ->
                    serverUrl to false
                serverStatus == ServerState.Status.STARTING ->
                    stringResource(R.string.status_starting) to true
                serverStatus == ServerState.Status.ERROR ->
                    stringResource(R.string.status_error) to true
                else ->
                    stringResource(R.string.status_stopped) to true
            }
            val canCopy = serverStatus == ServerState.Status.RUNNING && serverUrl != null
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = middleEllipsize(label, maxChars = 32),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (italic) MaterialTheme.colorScheme.onSurfaceVariant
                            else MaterialTheme.colorScheme.onSurface,
                    fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (canCopy && activeTab != AppTab.CHAT) {
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        imageVector = Icons.Outlined.ContentCopy,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        },
        navigationIcon = {
            Box(
                modifier = Modifier
                    .size(48.dp),
                contentAlignment = Alignment.Center,
            ) {
                StatusDot(status = serverStatus)
            }
        },
        actions = {
            if (activeTab == AppTab.CHAT) {
                IconButton(onClick = onOpenSystemPrompt) {
                    Icon(
                        imageVector = Icons.Outlined.Tune,
                        contentDescription = "Edit system prompt",
                    )
                }
                IconButton(onClick = onClearChat) {
                    Icon(
                        imageVector = Icons.Outlined.Refresh,
                        contentDescription = "Clear chat",
                    )
                }
            } else {
                IconButton(
                    onClick = onCopyUrl,
                    enabled = serverStatus == ServerState.Status.RUNNING && serverUrl != null,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.ContentCopy,
                        contentDescription = stringResource(R.string.action_copy_url),
                    )
                }
            }
        },
        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    )
}

@Composable
private fun AppNavigationBar(
    activeTab: AppTab,
    onTabSelected: (AppTab) -> Unit,
) {
    NavigationBar(
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        AppTab.values().forEach { tab ->
            NavigationBarItem(
                selected = activeTab == tab,
                onClick = { onTabSelected(tab) },
                icon = { Icon(iconForTab(tab), contentDescription = null) },
                label = {
                    Text(
                        text = stringResource(tab.labelRes),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
            )
        }
    }
}

private fun iconForTab(tab: AppTab): ImageVector = when (tab) {
    AppTab.MODELS -> Icons.Outlined.FolderOpen
    AppTab.DASHBOARD -> Icons.Outlined.BarChart
    AppTab.CONSOLE -> Icons.Outlined.Terminal
    AppTab.CHAT -> Icons.AutoMirrored.Outlined.Chat
    AppTab.DOCUMENTS -> Icons.Outlined.Description
    AppTab.SETTINGS -> Icons.Outlined.Settings
}

/**
 * Cheap middle-ellipsis for the app-bar URL. Compose doesn't offer
 * `TextOverflow.MiddleEllipsis` natively (it's experimental as of Compose
 * 1.7), so we do it manually on the string. Truncates only when the input
 * exceeds [maxChars], preserving the prefix (scheme://host) and the suffix
 * (port + path).
 */
private fun middleEllipsize(s: String, maxChars: Int): String {
    if (s.length <= maxChars) return s
    val keep = maxChars - 1 // one char for the ellipsis
    val head = keep / 2
    val tail = keep - head
    return s.substring(0, head) + "…" + s.substring(s.length - tail)
}

private inline fun refreshExistingModels(
    context: Context,
    update: (Set<String>, Map<String, Long>, Map<String, Long>) -> Unit
) {
    val dir = context.getExternalFilesDir(null)
    val files = dir?.listFiles { file -> file.name.endsWith(".litertlm") } ?: emptyArray()
    val names = files.map { it.name }.toSet()
    val sizes = files.associate { it.name to it.length() }
    val mtimes = files.associate { it.name to it.lastModified() }
    update(names, sizes, mtimes)
}
