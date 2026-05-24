package com.localllm.app

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.google.gson.annotations.SerializedName

/**
 * OpenAI-compatible wire types. Kept in one place so the server and any
 * future API clients can share them.
 *
 * Field names follow the OpenAI Chat Completions contract; @SerializedName
 * maps the snake_case JSON keys to camelCase Kotlin properties where they
 * differ.
 *
 * `Message.content` is intentionally [JsonElement] (Option 1 — polymorphic)
 * because OpenAI's API now allows either:
 *   - a plain string (`"content": "hello"`)
 *   - an array of typed parts (`"content": [{"type":"text", ...}, {"type":"image_url", ...}]`)
 *   - `null` on an assistant message that carries only `tool_calls`
 * Inspect [Message.contentParts] / [Message.contentString] to dispatch.
 */

data class Message(
    val role: String,
    /**
     * Polymorphic per OpenAI: string, array of parts, or null (when the
     * message is an assistant turn carrying only `tool_calls`, or a tool
     * follow-up that puts its serialized payload in `content` as a string).
     */
    val content: JsonElement? = null,
    @SerializedName("tool_calls") val toolCalls: List<ToolCallApi>? = null,
    /** Present on `role: "tool"` follow-up turns; references the call id we emitted. */
    @SerializedName("tool_call_id") val toolCallId: String? = null,
)

/** Convenience: returns the string form of [Message.content] iff it's a primitive. */
fun Message.contentString(): String? {
    val c = content ?: return null
    return if (c.isJsonPrimitive && c.asJsonPrimitive.isString) c.asString else null
}

/** Convenience: returns parsed content parts, handling all three shapes. */
fun Message.contentParts(): List<ContentPart> = content?.toContentParts() ?: emptyList()

/** Aggregate character count across textual parts (used for prompt-size capping). */
fun Message.textChars(): Int {
    val c = content ?: return 0
    return when {
        c.isJsonNull -> 0
        c.isJsonPrimitive -> if (c.asJsonPrimitive.isString) c.asString.length else c.toString().length
        c.isJsonArray -> c.asJsonArray.sumOf { el ->
            if (el.isJsonObject) {
                val o = el.asJsonObject
                when {
                    o.has("text") && o["text"].isJsonPrimitive -> o["text"].asString.length
                    else -> 0
                }
            } else 0
        }
        else -> 0
    }
}

/**
 * A typed content fragment after parsing [Message.content]. Mirrors the
 * `{type, ...}` discriminated union OpenAI uses.
 */
sealed class ContentPart {
    data class TextPart(val text: String) : ContentPart()
    /** `url` is either `data:image/...;base64,...` or `http://localhost...`. */
    data class ImagePart(val url: String) : ContentPart()
}

/**
 * Decode any of the three `content` shapes into a uniform list of parts.
 * - `JsonPrimitive(string)` → one [ContentPart.TextPart].
 * - `JsonArray` → each element's `type` discriminator selects the variant.
 * - anything else → empty list.
 *
 * Unknown `type` values are skipped (rather than erroring) — OpenAI is
 * permissive about forward-compat parts; we don't want to 400 on a
 * `{"type":"input_audio", ...}` we just don't understand.
 */
fun JsonElement.toContentParts(): List<ContentPart> {
    if (isJsonPrimitive && asJsonPrimitive.isString) {
        return listOf(ContentPart.TextPart(asString))
    }
    if (!isJsonArray) return emptyList()
    val out = mutableListOf<ContentPart>()
    for (el in asJsonArray) {
        if (!el.isJsonObject) continue
        val o = el.asJsonObject
        val type = o["type"]?.takeIf { it.isJsonPrimitive }?.asString ?: continue
        when (type) {
            "text" -> {
                val t = o["text"]?.takeIf { it.isJsonPrimitive }?.asString ?: continue
                out += ContentPart.TextPart(t)
            }
            "image_url" -> {
                val urlElem = o["image_url"] ?: continue
                val url = when {
                    urlElem.isJsonPrimitive -> urlElem.asString
                    urlElem.isJsonObject -> urlElem.asJsonObject["url"]
                        ?.takeIf { it.isJsonPrimitive }?.asString ?: continue
                    else -> continue
                }
                out += ContentPart.ImagePart(url)
            }
            else -> { /* skip unknown */ }
        }
    }
    return out
}

/** Helper to construct a string-shaped content quickly (used by tests + responses). */
fun stringContent(s: String): JsonElement = JsonPrimitive(s)

/** Helper to construct a parts-array content (used by tests). */
fun partsContent(parts: List<ContentPart>): JsonElement {
    val arr = JsonArray()
    for (p in parts) {
        val o = JsonObject()
        when (p) {
            is ContentPart.TextPart -> {
                o.addProperty("type", "text")
                o.addProperty("text", p.text)
            }
            is ContentPart.ImagePart -> {
                o.addProperty("type", "image_url")
                val urlObj = JsonObject()
                urlObj.addProperty("url", p.url)
                o.add("image_url", urlObj)
            }
        }
        arr.add(o)
    }
    return arr
}

data class ChatRequest(
    val model: String,
    val messages: List<Message>,
    val stream: Boolean = false,
    /**
     * Opaque conversation ID for KV-cache reuse across turns. When empty
     * (the default) every request runs in a fresh session. When non-empty
     * the server caches a LiteRT-LM `Conversation` for this ID and only
     * feeds the new user turn into it on follow-up requests — the KV cache
     * for the prior turns is preserved on the engine side.
     */
    @SerializedName("session_id") val sessionId: String? = null,
    val temperature: Float? = null,
    @SerializedName("top_k") val topK: Int? = null,
    @SerializedName("max_tokens") val maxTokens: Int? = null,
    /** OpenAI function/tool definitions advertised to the model. */
    val tools: List<ToolDef>? = null,
    /**
     * `"auto"` (default behavior), `"none"`, or `{"type":"function","function":{"name":"..."}}`.
     * Polymorphic on the wire, so we hold it as a raw [JsonElement] and
     * inspect at the call site.
     */
    @SerializedName("tool_choice") val toolChoice: JsonElement? = null,
)

data class ToolDef(
    val type: String = "function",
    val function: FunctionDef,
)

data class FunctionDef(
    val name: String,
    val description: String? = null,
    val parameters: JsonObject,
)

data class ToolCallApi(
    val id: String,
    val type: String = "function",
    val function: ToolCallFunction,
)

/** `arguments` is a JSON-encoded string per the OpenAI contract — NOT a JsonObject. */
data class ToolCallFunction(
    val name: String,
    val arguments: String,
)

data class ChatResponse(
    val id: String,
    val `object`: String,
    val created: Long,
    val model: String,
    val choices: List<Choice>
)

data class Choice(
    val index: Int,
    val message: Message,
    @SerializedName("finish_reason") val finishReason: String
)

data class StreamResponse(
    val id: String,
    val `object`: String,
    val created: Long,
    val model: String,
    val choices: List<StreamChoice>
)

data class StreamChoice(
    val index: Int,
    val delta: StreamDelta,
    @SerializedName("finish_reason") val finishReason: String?
)

data class StreamDelta(
    val role: String? = null,
    val content: String? = null,
    @SerializedName("tool_calls") val toolCalls: List<ToolCallApi>? = null,
)

data class ErrorResponse(val error: ErrorDetails)

data class ErrorDetails(
    val message: String,
    val type: String,
    val code: Int
)

/**
 * Richer error envelope used by the AICore (Gemini Nano) and LiteRT-LM
 * failure paths in `/v1/chat/completions`. Extension to the baseline OpenAI
 * error shape — clients that only know OpenAI still get a readable
 * `message` / `type`. Clients aware of our extension can branch on [code]
 * (string like `AICORE_DOWNLOADABLE`, `LITERT_INIT_FAILED`), surface
 * [aicoreStatus] in the UI, and present [nextSteps] verbatim to the user.
 *
 *  - [code]: stable machine-readable identifier (string, not the int HTTP code
 *    in [ErrorDetails]).
 *  - [aicoreStatus]: present for AICore-related failures; mirrors
 *    `AICoreEngine.statusLabel`.
 *  - [actionable]: true iff [nextSteps] can resolve the failure.
 */
data class RichErrorResponse(val error: RichErrorDetails)

data class RichErrorDetails(
    val message: String,
    val type: String,
    val code: String,
    @SerializedName("aicore_status") val aicoreStatus: String? = null,
    val actionable: Boolean = false,
    @SerializedName("next_steps") val nextSteps: List<String> = emptyList(),
)

data class ModelListResponse(
    val `object`: String = "list",
    val data: List<ModelData>
)

data class ModelData(
    val id: String,
    val `object`: String = "model",
    val created: Long = 0,
    @SerializedName("owned_by") val ownedBy: String = "local"
)

/**
 * `POST /v1/embeddings` request. Per OpenAI:
 *   - `input` is polymorphic: a single string OR an array of strings
 *     (arrays of token-id lists are not supported on this server)
 *   - `model` selects the embedding model id (e.g. "bge-small-en-v1.5")
 *   - `encoding_format` defaults to "float"; "base64" is not implemented
 *     (we return 400 if the client asks for it)
 */
data class EmbeddingRequest(
    val input: JsonElement,
    val model: String,
    @SerializedName("encoding_format") val encodingFormat: String? = null,
    val user: String? = null,
)

/**
 * Normalised view of [EmbeddingRequest.input]. Throws [IllegalArgumentException]
 * for shapes we don't accept (numbers, nested arrays, nulls, empty strings).
 */
fun EmbeddingRequest.inputStrings(): List<String> {
    val el = input
    return when {
        el.isJsonPrimitive && el.asJsonPrimitive.isString -> listOf(el.asString)
        el.isJsonArray -> {
            val arr = el.asJsonArray
            require(arr.size() > 0) { "input array must not be empty" }
            arr.map { item ->
                require(item.isJsonPrimitive && item.asJsonPrimitive.isString) {
                    "input array entries must be strings"
                }
                item.asString
            }
        }
        else -> throw IllegalArgumentException(
            "input must be a string or an array of strings"
        )
    }
}

data class EmbeddingResponse(
    val `object`: String = "list",
    val data: List<EmbeddingData>,
    val model: String,
    val usage: EmbeddingUsage,
)

data class EmbeddingData(
    val `object`: String = "embedding",
    val embedding: FloatArray,
    val index: Int,
)

data class EmbeddingUsage(
    @SerializedName("prompt_tokens") val promptTokens: Int,
    @SerializedName("total_tokens") val totalTokens: Int,
)

/* ---------- RAG: documents + search wire types ---------- */

/**
 * `POST /v1/documents` request. The server chunks [text] (~400 chars per
 * window with ~60-char overlap), embeds each chunk with [model], and
 * persists them under the client-supplied [id]. Re-using the same [id]
 * replaces the prior chunks for that document.
 */
data class DocumentRequest(
    val id: String,
    val text: String,
    val model: String,
    val metadata: JsonElement? = null,
)

data class DocumentSummaryResponse(
    @SerializedName("document_id") val documentId: String,
    @SerializedName("chunk_count") val chunkCount: Int,
    val model: String,
    @SerializedName("tenant_id") val tenantId: String,
)

data class DocumentListResponse(
    val `object`: String = "list",
    val data: List<DocumentSummaryResponse>,
    @SerializedName("tenant_id") val tenantId: String,
)

data class DocumentDeleteResponse(
    @SerializedName("document_id") val documentId: String,
    val deleted: Boolean,
    @SerializedName("chunks_removed") val chunksRemoved: Int,
    @SerializedName("tenant_id") val tenantId: String,
)

data class SearchRequest(
    val query: String,
    val model: String,
    val k: Int? = null,
)

data class SearchHit(
    @SerializedName("document_id") val documentId: String,
    @SerializedName("chunk_index") val chunkIndex: Int,
    val text: String,
    val score: Float,
    val metadata: JsonElement? = null,
)

data class SearchResponse(
    val `object`: String = "list",
    val data: List<SearchHit>,
    val model: String,
    @SerializedName("tenant_id") val tenantId: String,
)

data class TenantSummaryResponse(
    @SerializedName("tenant_id") val tenantId: String,
    @SerializedName("document_count") val documentCount: Int,
    @SerializedName("chunk_count") val chunkCount: Int,
)

data class TenantListResponse(
    val `object`: String = "list",
    val data: List<TenantSummaryResponse>,
)

data class TenantDeleteResponse(
    @SerializedName("tenant_id") val tenantId: String,
    val deleted: Boolean,
    @SerializedName("chunks_removed") val chunksRemoved: Int,
)
