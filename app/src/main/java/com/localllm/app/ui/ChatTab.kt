package com.localllm.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import com.localllm.app.LogManager
import com.localllm.app.ui.chat.ChatHeaderRow
import com.localllm.app.ui.chat.ChatInputBar
import com.localllm.app.ui.chat.MessageList
import com.localllm.app.ui.chat.SystemPromptSheet
import com.localllm.app.ui.chat.SystemPromptStrip
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
        MessageList(
            chatMessages = chatMessages,
            chatListState = chatListState,
            isChatting = isChatting,
            streamingAssistantIndex = streamingAssistantIndex,
            lastDelta = lastDelta,
            onInputChange = onInputChange,
        )

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
        SystemPromptSheet(
            systemPrompt = systemPrompt,
            onSystemPromptChange = { systemPrompt = it },
            onDismiss = { sheetOpen = false },
        )
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
