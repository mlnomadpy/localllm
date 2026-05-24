package com.localllm.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.localllm.app.composables.AppNavigationBar
import com.localllm.app.composables.AutostartEffect
import com.localllm.app.composables.CompactAppBar
import com.localllm.app.composables.rememberFilePicker
import com.localllm.app.download.DownloadRepository
import com.localllm.app.download.DownloadStatus
import com.localllm.app.download.rememberModelDirSnapshot
import com.localllm.app.permission.rememberNotificationPermissionState
import com.localllm.app.ui.AppTab
import com.localllm.app.ui.ChatTab
import com.localllm.app.ui.ConsoleTab
import com.localllm.app.ui.DashboardTab
import com.localllm.app.ui.DocumentsTab
import com.localllm.app.ui.ModelsTab
import com.localllm.app.ui.SettingsTab
import com.localllm.app.ui.UiMessage
import com.localllm.app.ui.sendChatMessage
import java.io.BufferedInputStream
import java.io.File
import java.security.DigestInputStream
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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

            val modelDirSnapshot by rememberModelDirSnapshot(
                active = activeTab == AppTab.MODELS || activeTab == AppTab.CHAT,
            )
            LaunchedEffect(modelDirSnapshot) {
                existingModels = modelDirSnapshot.existingModels
                modelSizes = modelDirSnapshot.modelSizes
                modelMtimes = modelDirSnapshot.modelMtimes
            }

            val downloadRepo = remember(context) { DownloadRepository(context) }

            LaunchedEffect(activeDownloads.toMap()) {
                downloadRepo.pollAll(activeDownloads).collect { snapshots ->
                    val toRemove = mutableListOf<String>()
                    for (snap in snapshots) {
                        if (snap.totalBytes > 0) downloadProgress[snap.filename] = snap.progress
                        if (snap.totalBytes > 0) downloadTotalBytes[snap.filename] = snap.totalBytes
                        when (snap.status) {
                            DownloadStatus.State.SUCCESSFUL -> {
                                toRemove.add(snap.filename)
                                coroutineScope.launch {
                                    val ok = withContext(Dispatchers.IO) {
                                        verifyDownloadedModel(snap.filename)
                                    }
                                    if (ok == false) {
                                        existingModels = existingModels - snap.filename
                                        Toast.makeText(
                                            context,
                                            "Model verification failed: ${snap.filename}",
                                            Toast.LENGTH_LONG
                                        ).show()
                                    }
                                }
                            }
                            DownloadStatus.State.FAILED,
                            DownloadStatus.State.GONE -> toRemove.add(snap.filename)
                            DownloadStatus.State.IN_PROGRESS -> Unit
                        }
                    }
                    toRemove.forEach {
                        activeDownloads.remove(it)
                        downloadProgress.remove(it)
                        downloadTotalBytes.remove(it)
                    }
                }
            }

            val notificationPermission = rememberNotificationPermissionState()
            val hasNotificationPermission = notificationPermission.granted

            val launchFilePicker = rememberFilePicker { name ->
                existingModels = existingModels + name
            }

            LaunchedEffect(hasNotificationPermission) {
                if (!hasNotificationPermission && Build.VERSION.SDK_INT >= 33) {
                    notificationPermission.request()
                }
            }
            AutostartEffect(
                hasNotificationPermission = hasNotificationPermission,
                onStart = { startServer() },
            )

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
                                            activeDownloads[model.filename] = downloadRepo.enqueue(model)
                                        },
                                        onCancel = { model ->
                                            val id = activeDownloads[model.filename]
                                            if (id != null) {
                                                downloadRepo.cancel(id)
                                                activeDownloads.remove(model.filename)
                                                downloadProgress.remove(model.filename)
                                                downloadTotalBytes.remove(model.filename)
                                            }
                                        },
                                        onDelete = { model ->
                                            getModelFile(model.filename).delete()
                                            existingModels = existingModels - model.filename
                                        },
                                        onImport = { launchFilePicker() }
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

