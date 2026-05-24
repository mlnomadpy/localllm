package com.localllm.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.localllm.app.AVAILABLE_MODELS
import com.localllm.app.LogManager
import com.localllm.app.R
import com.localllm.app.inference.aicore.AICoreEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Translate a bare model filename (or id) into a friendly label using the
 * built-in [AVAILABLE_MODELS] catalog. Custom / unknown models fall back to
 * the bare filename so the user still sees a stable identifier.
 */
fun displayLabelFor(filename: String): String {
    val bare = filename.removeSuffix(".litertlm").removeSuffix(".task")
    val known = AVAILABLE_MODELS.firstOrNull { it.id == bare }
    return known?.name ?: bare
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ChatTab(
    existingModels: Set<String>,
    selectedModel: String,
    onModelChange: (String) -> Unit,
    chatMessages: List<UiMessage>,
    chatInput: String,
    onInputChange: (String) -> Unit,
    isChatting: Boolean,
    onSend: (systemPrompt: String) -> Unit,
    onStop: () -> Unit,
    chatListState: LazyListState,
    tokenRate: Double = 0.0,
    tokenCount: Int = 0,
    streamElapsedMs: Long = 0L,
    /** Increment to open the system-prompt bottom sheet (driven by the app-bar Tune icon). */
    openSystemPromptTrigger: Int = 0,
    /** Increment to clear all chat messages (driven by the app-bar Refresh icon). */
    clearChatTrigger: Int = 0,
    onClearChat: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current

    // No early-return on `existingModels.isEmpty()` anymore — AICore (Gemini
    // Nano) is the default model and has no file on disk, so a fresh install
    // with zero `.litertlm` files is a perfectly valid chat state. Failures
    // (e.g. AICore not provisioned on this device) surface via the structured
    // error envelope on the actual send.

    // System-prompt state. Sheet is opened either by tapping the inline
    // indicator strip's edit icon, or by the Tune action in the app bar via
    // [openSystemPromptTrigger].
    var systemPrompt by remember { mutableStateOf("") }
    var sheetOpen by remember { mutableStateOf(false) }
    LaunchedEffect(openSystemPromptTrigger) {
        if (openSystemPromptTrigger > 0) sheetOpen = true
    }
    LaunchedEffect(clearChatTrigger) {
        if (clearChatTrigger > 0) onClearChat()
    }

    // Track streaming delta for the assistant fade-in animation. Mirrors v1.
    val lastAssistantLengthState = remember { mutableStateOf(0) }
    val lastDeltaState = remember { mutableStateOf(0) }
    val streamingAssistantIndex = chatMessages.indexOfLast { it.role == "assistant" }
    val currentAssistantLength =
        if (isChatting && streamingAssistantIndex >= 0) chatMessages[streamingAssistantIndex].content.length
        else 0
    SideEffect {
        if (isChatting && currentAssistantLength > lastAssistantLengthState.value) {
            lastDeltaState.value = currentAssistantLength - lastAssistantLengthState.value
            lastAssistantLengthState.value = currentAssistantLength
        } else if (!isChatting) {
            lastAssistantLengthState.value = 0
            lastDeltaState.value = 0
        }
    }
    val lastDelta = lastDeltaState.value

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 8.dp)
    ) {
        // Compact header row: model selector chip + (while streaming) tok/s chip.
        ChatHeaderRow(
            existingModels = existingModels,
            selectedModel = selectedModel,
            onModelChange = onModelChange,
            isChatting = isChatting,
            tokenRate = tokenRate,
            tokenCount = tokenCount,
            streamElapsedMs = streamElapsedMs,
        )

        // Persistent system-prompt indicator strip, only when set.
        if (systemPrompt.isNotBlank()) {
            SystemPromptStrip(
                text = systemPrompt,
                onEdit = { sheetOpen = true },
            )
        }

        // Message list.
        LazyColumn(
            state = chatListState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
        ) {
            if (chatMessages.isEmpty()) {
                item {
                    ChatEmptyState(onPromptSelected = onInputChange)
                }
            }
            itemsIndexed(chatMessages) { index, msg ->
                val isStreaming =
                    isChatting && index == streamingAssistantIndex && msg.role == "assistant"
                ChatBubble(
                    msg = msg,
                    isStreaming = isStreaming,
                    lastDeltaLength = if (isStreaming) lastDelta else 0,
                )
            }
            if (isChatting) {
                item {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .size(24.dp)
                            .padding(8.dp),
                        strokeWidth = 2.dp,
                    )
                }
            }
        }

        // Input row.
        ChatInputBar(
            value = chatInput,
            onValueChange = onInputChange,
            isChatting = isChatting,
            onSend = {
                onSend(systemPrompt)
                focusManager.clearFocus()
            },
            onStop = onStop,
        )
    }

    if (sheetOpen) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { sheetOpen = false },
            sheetState = sheetState,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 16.dp)
                    .imePadding(),
            ) {
                Text(
                    text = stringResource(R.string.chat_system_prompt_label),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.size(12.dp))
                OutlinedTextField(
                    value = systemPrompt,
                    onValueChange = { systemPrompt = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = {
                        Text(stringResource(R.string.chat_system_prompt_placeholder))
                    },
                    maxLines = 8,
                )
                Spacer(Modifier.size(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    Button(onClick = { scope.launch { sheetOpen = false } }) {
                        Text("Save")
                    }
                }
                Spacer(Modifier.size(8.dp))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatHeaderRow(
    existingModels: Set<String>,
    selectedModel: String,
    onModelChange: (String) -> Unit,
    isChatting: Boolean,
    tokenRate: Double,
    tokenCount: Int,
    streamElapsedMs: Long,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            AssistChip(
                onClick = { menuOpen = true },
                label = {
                    Text(
                        text = displayLabelFor(selectedModel),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Outlined.AutoAwesome,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                },
                trailingIcon = {
                    Icon(
                        imageVector = Icons.Outlined.ArrowDropDown,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                },
            )
            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
            ) {
                // AICore (Gemini Nano) — always selectable. It's a virtual
                // catalog entry with no file on disk, so it's not in
                // `existingModels`; surface it unconditionally so the user
                // can pick the default model without first downloading a
                // `.litertlm` file.
                DropdownMenuItem(
                    text = { Text("Gemini Nano · AICore  (default)") },
                    onClick = {
                        onModelChange(AICoreEngine.MODEL_ID)
                        menuOpen = false
                    },
                )
                existingModels.forEach { modelName ->
                    DropdownMenuItem(
                        text = { Text(displayLabelFor(modelName)) },
                        onClick = {
                            onModelChange(
                                modelName.removeSuffix(".litertlm").removeSuffix(".task")
                            )
                            menuOpen = false
                        },
                    )
                }
            }
        }

        if (isChatting) {
            Spacer(Modifier.width(8.dp))
            val label = if (streamElapsedMs > 200L && tokenCount > 0) {
                "%.1f tok/s · %d".format(tokenRate, tokenCount)
            } else {
                "streaming…"
            }
            AssistChip(
                onClick = {},
                label = {
                    Text(
                        text = label,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Outlined.Bolt,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                },
            )
        }
    }
}

@Composable
private fun SystemPromptStrip(
    text: String,
    onEdit: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onEdit)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Icon(
                imageVector = Icons.Outlined.Edit,
                contentDescription = "Edit system prompt",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun ChatInputBar(
    value: String,
    onValueChange: (String) -> Unit,
    isChatting: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    val placeholder = stringResource(R.string.chat_placeholder)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 12.dp)
            .imePadding(),
        tonalElevation = 2.dp,
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (value.isEmpty()) {
                    Text(
                        text = placeholder,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    enabled = !isChatting,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    maxLines = 5,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.width(8.dp))
            SendStopButton(
                isChatting = isChatting,
                canSend = value.isNotBlank(),
                onSend = onSend,
                onStop = onStop,
            )
        }
    }
}

@Composable
private fun SendStopButton(
    isChatting: Boolean,
    canSend: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    val bg = if (isChatting) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    val fg = if (isChatting) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onPrimary
    val cd = if (isChatting) "Stop streaming" else "Send message"
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(bg)
            .semantics { contentDescription = cd },
        contentAlignment = Alignment.Center,
    ) {
        IconButton(
            onClick = { if (isChatting) onStop() else if (canSend) onSend() },
            enabled = isChatting || canSend,
        ) {
            Icon(
                imageVector = if (isChatting) Icons.Outlined.Stop else Icons.AutoMirrored.Outlined.Send,
                contentDescription = null,
                tint = fg,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

/**
 * In-app chat client. Speaks the same OpenAI-compatible API the server exposes
 * by hitting the local server. Streams via SSE and appends to [messages] in
 * place so the Chat list recomposes incrementally.
 *
 * Returns the launched [Job] so the caller can `.cancel()` it (used by the
 * Stop button to abort an in-flight request).
 */
fun sendChatMessage(
    messages: MutableList<UiMessage>,
    input: String,
    model: String,
    baseUrl: String,
    apiKey: String,
    coroutineScope: CoroutineScope,
    listState: LazyListState,
    onChattingChange: (Boolean) -> Unit,
    systemPrompt: String = "",
    onTokenRate: (rate: Double, count: Int, elapsedMs: Long) -> Unit = { _, _, _ -> }
): Job {
    onChattingChange(true)
    messages.add(UiMessage("user", input))
    val assistantIndex = messages.size
    messages.add(UiMessage("assistant", ""))

    val job = coroutineScope.launch {
        var startTimeMs = 0L
        var tokenCount = 0
        var eventSource: EventSource? = null
        try {
            val client = OkHttpClient.Builder()
                .readTimeout(10, TimeUnit.MINUTES)
                .build()

            val reqBody = JSONObject().apply {
                put("model", model)
                put("stream", true)
                put("session_id", "local_chat_test")

                val msgArray = org.json.JSONArray()
                if (systemPrompt.isNotBlank()) {
                    val sys = JSONObject()
                    sys.put("role", "system")
                    sys.put("content", systemPrompt)
                    msgArray.put(sys)
                }
                for (i in 0 until messages.size - 1) {
                    val m = JSONObject()
                    m.put("role", messages[i].role)
                    m.put("content", messages[i].content)
                    msgArray.put(m)
                }
                put("messages", msgArray)
            }.toString().toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url("${baseUrl.removeSuffix("/")}/v1/chat/completions")
                .post(reqBody).apply {
                    if (apiKey.isNotEmpty()) {
                        header("Authorization", "Bearer $apiKey")
                    }
                }
                .build()

            val factory = EventSources.createFactory(client)

            // Suspend until the SSE stream terminates (DONE / failure / close)
            // so that this coroutine's lifetime maps to the request lifetime.
            // Cancelling the Job closes the underlying OkHttp call.
            suspendCancellableCoroutine<Unit> { cont ->
                val source = factory.newEventSource(request, object : EventSourceListener() {
                    @Volatile private var done = false
                    private fun finishOnce() {
                        if (done) return
                        done = true
                        if (cont.isActive) cont.resume(Unit)
                    }

                    override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                        if (data == "[DONE]") {
                            finishOnce()
                            return
                        }
                        try {
                            val obj = JSONObject(data)
                            val choices = obj.optJSONArray("choices")
                            if (choices != null && choices.length() > 0) {
                                val delta = choices.getJSONObject(0).optJSONObject("delta")
                                val content = delta?.optString("content")
                                if (!content.isNullOrEmpty()) {
                                    if (startTimeMs == 0L) startTimeMs = System.currentTimeMillis()
                                    tokenCount += 1
                                    val elapsed = System.currentTimeMillis() - startTimeMs
                                    val rate = if (elapsed > 0) tokenCount * 1000.0 / elapsed else 0.0
                                    onTokenRate(rate, tokenCount, elapsed)

                                    val current = messages[assistantIndex]
                                    messages[assistantIndex] = current.copy(content = current.content + content)
                                    coroutineScope.launch { listState.animateScrollToItem(messages.size - 1) }
                                }
                            }
                        } catch (e: Exception) {
                            LogManager.e("Chat", "Parse error", e)
                        }
                    }

                    override fun onFailure(eventSource: EventSource, t: Throwable?, response: okhttp3.Response?) {
                        val errorMsg = response?.body?.string() ?: t?.message ?: "Unknown SSE failure"
                        // A cancelled call shows up here too — don't spam logs with our own cancellation.
                        if (cont.isActive) LogManager.e("Chat", "SSE Failure: $errorMsg", t)
                        finishOnce()
                    }

                    override fun onClosed(eventSource: EventSource) {
                        finishOnce()
                    }
                })
                eventSource = source
                cont.invokeOnCancellation {
                    // Close the OkHttp call so the server sees the disconnect.
                    try { source.cancel() } catch (_: Throwable) {}
                }
            }
        } catch (e: CancellationException) {
            LogManager.i("Chat", "Request cancelled")
            try { eventSource?.cancel() } catch (_: Throwable) {}
            throw e
        } catch (e: Exception) {
            LogManager.e("Chat", "Request failed", e)
        } finally {
            onChattingChange(false)
        }
    }

    return job
}
