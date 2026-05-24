package com.localllm.app.inference.litert

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine as LiteRtNativeEngine
import com.google.ai.edge.litertlm.Message as LlmMessage
import com.google.ai.edge.litertlm.OpenApiTool
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ToolCall
import com.google.ai.edge.litertlm.ToolProvider
import com.google.ai.edge.litertlm.tool
import com.localllm.app.ContentPart
import com.localllm.app.LogManager
import com.localllm.app.Message
import com.localllm.app.ToolDef
import com.localllm.app.buildToolDescriptionJson
import com.localllm.app.contentParts
import com.localllm.app.contentString
import com.localllm.app.decodeDataImageUrl
import com.localllm.app.isLoopbackHttpUrl
import com.localllm.app.parseToolArguments
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request as OkRequest

/**
 * Pure conversion helpers between OpenAI-shaped API types and LiteRT-LM's
 * native message model. Extracted from LLMServerService so the chat route
 * can stay focused on HTTP wiring; nothing here touches Ktor or
 * SharedPreferences.
 *
 * Image-fetch policy:
 *   - `data:` URLs: inline base64 decode
 *   - `http://localhost(:port)/...`: fetch via OkHttp on the calling thread
 *     (capped at 5 MB; >1024 px is downscaled to JPEG@85%)
 *   - anything else: throws — SSRF protection.
 */
object LlmMessageConverter {

    private val imageHttp: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    private const val MAX_IMAGE_BYTES = 5L * 1024L * 1024L
    private const val MAX_IMAGE_DIM = 1024

    /** Plain-text view of a [LlmMessage] — image / audio parts are dropped. */
    fun messageText(msg: LlmMessage): String {
        val parts = msg.contents.contents
        if (parts.isEmpty()) return ""
        val sb = StringBuilder()
        for (p in parts) {
            if (p is Content.Text) sb.append(p.text)
        }
        return sb.toString()
    }

    fun apiToLlmMessage(m: Message): LlmMessage {
        return when (m.role) {
            "assistant" -> {
                val toolCalls = m.toolCalls?.map { tc ->
                    ToolCall(tc.function.name, parseToolArguments(tc.function.arguments))
                } ?: emptyList()
                val contents = buildContents(m)
                LlmMessage.Companion.model(
                    Contents.of(contents),
                    toolCalls,
                    emptyMap(),
                )
            }
            "system" -> LlmMessage.Companion.system(Contents.of(buildContents(m)))
            "tool" -> {
                val payload = m.contentString() ?: (m.content?.toString() ?: "")
                val name = m.toolCallId ?: "tool"
                LlmMessage.Companion.tool(Contents.of(Content.ToolResponse(name, payload)))
            }
            else -> LlmMessage.Companion.user(Contents.of(buildContents(m)))
        }
    }

    fun buildContents(message: Message): List<Content> {
        val parts = message.contentParts()
        if (parts.isEmpty()) return emptyList()
        val out = mutableListOf<Content>()
        for (p in parts) {
            when (p) {
                is ContentPart.TextPart -> if (p.text.isNotEmpty()) out += Content.Text(p.text)
                is ContentPart.ImagePart -> out += Content.ImageBytes(loadImageBytes(p.url))
            }
        }
        return out
    }

    fun buildToolProvider(def: ToolDef): ToolProvider {
        val name = def.function.name
        val descJson = buildToolDescriptionJson(name, def.function.description, def.function.parameters)
        val openApi = object : OpenApiTool {
            override fun getToolDescriptionJsonString(): String = descJson
            override fun execute(paramsJsonString: String): String {
                return "{\"error\":\"tool_execution_not_implemented\",\"tool\":\"$name\"}"
            }
        }
        return tool(openApi)
    }

    /**
     * Build a pre-loaded [Conversation]. The caller is responsible for
     * closing it (or registering it as the active conversation for the
     * engine and letting [EngineRegistry] clean it up on eviction).
     *
     * `automaticToolCalling` MUST be false: the OpenAI contract is "model
     * emits tool_calls, client executes, client sends a role:tool follow-up",
     * not "engine invokes our stub OpenApiTool.execute() and feeds the
     * result back to the model".
     */
    fun createConversation(
        engine: LiteRtNativeEngine,
        temperature: Float,
        topK: Int,
        systemText: String?,
        initial: List<Message>,
        tools: List<ToolDef>?,
    ): Conversation {
        val systemInstruction = systemText?.takeIf { it.isNotBlank() }?.let { Contents.of(it) }
        val priorMessages = initial.map { apiToLlmMessage(it) }
        val toolProviders = tools?.map { buildToolProvider(it) } ?: emptyList()
        val cfg = ConversationConfig(
            systemInstruction,
            priorMessages,
            toolProviders,
            SamplerConfig(topK, /*topP=*/0.95, temperature.toDouble(), /*seed=*/0),
            /*automaticToolCalling=*/ false,
        )
        return engine.createConversation(cfg)
    }

    /**
     * Public alias for [loadImageBytes] — used by the AICore branch of the
     * chat route so it can reuse the SSRF policy + downscale logic without
     * duplicating it. Same contract: throws on disallowed schemes, oversized
     * payloads, or undecodable images.
     */
    fun loadImageBytesForAICore(url: String): ByteArray = loadImageBytes(url)

    private fun loadImageBytes(url: String): ByteArray {
        val raw: ByteArray = when {
            url.startsWith("data:") -> decodeDataImageUrl(url)
            isLoopbackHttpUrl(url) -> {
                val resp = imageHttp.newCall(OkRequest.Builder().url(url).build()).execute()
                resp.use { r ->
                    if (!r.isSuccessful) {
                        throw IllegalArgumentException("Failed to fetch image_url: HTTP ${r.code}")
                    }
                    val cl = r.body?.contentLength() ?: -1L
                    if (cl in 1..Long.MAX_VALUE && cl > MAX_IMAGE_BYTES) {
                        throw IllegalArgumentException("Image too large: $cl bytes (max $MAX_IMAGE_BYTES)")
                    }
                    val src = r.body?.byteStream() ?: throw IllegalArgumentException("Empty body")
                    val buf = ByteArrayOutputStream()
                    val tmp = ByteArray(16 * 1024)
                    var total = 0L
                    while (true) {
                        val n = src.read(tmp)
                        if (n <= 0) break
                        total += n
                        if (total > MAX_IMAGE_BYTES) {
                            throw IllegalArgumentException("Image exceeds $MAX_IMAGE_BYTES-byte cap")
                        }
                        buf.write(tmp, 0, n)
                    }
                    buf.toByteArray()
                }
            }
            else -> throw IllegalArgumentException(
                "image_url scheme not allowed; only data: and http://localhost are supported"
            )
        }
        return downscaleIfNeeded(raw)
    }

    private fun downscaleIfNeeded(bytes: ByteArray): ByteArray {
        val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, boundsOpts)
        val w = boundsOpts.outWidth
        val h = boundsOpts.outHeight
        if (w <= 0 || h <= 0) {
            throw IllegalArgumentException("Could not decode image (invalid format or corrupt bytes)")
        }
        if (w <= MAX_IMAGE_DIM && h <= MAX_IMAGE_DIM) return bytes

        var sample = 1
        while (w / sample > MAX_IMAGE_DIM || h / sample > MAX_IMAGE_DIM) sample *= 2

        val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOpts)
            ?: throw IllegalArgumentException("Image decode returned null")
        val out = ByteArrayOutputStream()
        try {
            bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
        } finally {
            bmp.recycle()
        }
        return out.toByteArray()
    }

    init {
        // No-op anchor for IDE navigation; ensures LogManager is referenced
        // somewhere (we use it elsewhere via the chat route).
        @Suppress("ConstantConditionIf")
        if (false) LogManager.i("LlmMessageConverter", "init")
    }
}
