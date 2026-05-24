package com.localllm.app.inference.litert

import android.util.LruCache
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.Message as LlmMessage
import com.localllm.app.ChatRequest
import com.localllm.app.LogManager
import com.localllm.app.Message
import com.localllm.app.ToolDef
import com.localllm.app.contentString
import com.localllm.app.inference.EngineKey
import com.localllm.app.inference.EngineRegistry
import com.localllm.app.messagesPrefixHash

/**
 * Owns LiteRT-LM [Conversation] lifecycle and caching. Separated from the
 * chat route so the route can stay focused on HTTP wiring while this class
 * holds the (substantial) bookkeeping that lets the engine reuse KV-cached
 * conversations across turns.
 *
 * Two tables of state:
 *   - [sessions]: cached `Conversation`s keyed by `session_id + engineKey`,
 *     reused across turns when the client replays a matching prefix.
 *   - [activeConversations] (lives on [EngineRegistry]): at most one live
 *     [Conversation] per engine — LiteRT-LM enforces this on the native
 *     side, so we keep a marker here to close any prior conversation
 *     before creating a new one.
 */
class SessionManager(private val registry: EngineRegistry) {

    /** What [resolve] returns. Caller commits or invalidates after inference. */
    data class Resolved(
        val conversation: Conversation,
        /** The freshly-arrived message to send via sendMessage[Async]. */
        val prompt: LlmMessage,
        val cacheKey: String?,
        val engineKey: EngineKey,
        val temperature: Float,
        val topK: Int,
    ) {
        val isCached: Boolean get() = cacheKey != null
    }

    private data class CachedSession(
        val conversation: Conversation,
        val engineKey: EngineKey,
        val temperature: Float,
        val topK: Int,
        val prefixHash: Long,
        val seenCount: Int,
        val createdAt: Long,
    )

    private val sessions = object : LruCache<String, CachedSession>(4) {
        override fun entryRemoved(
            evicted: Boolean,
            key: String?,
            oldValue: CachedSession?,
            newValue: CachedSession?,
        ) {
            if (oldValue != null && oldValue.conversation !== newValue?.conversation) {
                try { oldValue.conversation.close() } catch (_: Exception) {}
            }
        }
    }

    init {
        // Wire the engine-eviction listener: when an engine entry leaves
        // the registry's LRU, every conversation tied to it MUST be closed
        // first (a conversation outliving its engine is undefined on the
        // native side).
        registry.onLiteRtEvicted = { engineKey: EngineKey ->
            val staleKeys = sessions.snapshot().filter { it.value.engineKey == engineKey }.keys
            staleKeys.forEach { sessions.remove(it) }
            registry.activeConversations.remove(engineKey)?.let { conv ->
                try { conv.close() } catch (_: Exception) {}
            }
        }
    }

    /**
     * Decide whether to reuse a cached conversation or build a fresh one,
     * and compute the prompt fragment to send accordingly.
     */
    fun resolve(
        req: ChatRequest,
        acquired: EngineRegistry.AcquiredEngine.LiteRt,
        temperature: Float,
        topK: Int,
    ): Resolved {
        val systemText = req.messages.firstOrNull { it.role == "system" }?.contentString()
        val nonSystem = req.messages.filter { it.role != "system" }
        if (nonSystem.isEmpty()) {
            throw IllegalArgumentException("Request has no non-system messages")
        }
        val last = nonSystem.last()
        if (last.role != "user" && last.role != "tool") {
            throw IllegalArgumentException("Last message must have role=user or role=tool")
        }
        val lastPrompt = LlmMessageConverter.apiToLlmMessage(last)
        val prior = nonSystem.dropLast(1)

        // Honor `tool_choice: "none"` by suppressing the tools list entirely.
        val tools: List<ToolDef>? = req.tools?.takeIf {
            val choice = req.toolChoice
            !(choice != null && choice.isJsonPrimitive && choice.asJsonPrimitive.isString
                && choice.asString.equals("none", ignoreCase = true))
        }

        // Stateless path.
        if (req.sessionId.isNullOrEmpty()) {
            val conversation = createConversation(acquired, temperature, topK, systemText, prior, tools)
            return Resolved(conversation, lastPrompt, null, acquired.cacheKey, temperature, topK)
        }

        val cacheKey = "${req.sessionId}_${acquired.cacheKey.asString()}"
        val cached = sessions.get(cacheKey)

        val canReuse = cached != null &&
            cached.temperature == temperature &&
            cached.topK == topK &&
            cached.seenCount < req.messages.size &&
            cached.prefixHash == messagesPrefixHash(req.messages, cached.seenCount) &&
            run {
                val newRange = req.messages.subList(cached.seenCount, req.messages.size)
                val driving = newRange.filter { it.role != "assistant" }
                driving.size == 1 && (driving[0].role == "user" || driving[0].role == "tool")
            }

        if (canReuse) {
            cached!!
            val newDriving = req.messages.subList(cached.seenCount, req.messages.size)
                .first { it.role != "assistant" }
            LogManager.i("SessionManager", "Session $cacheKey reused (sending 1 new ${newDriving.role} turn)")
            return Resolved(
                conversation = cached.conversation,
                prompt = LlmMessageConverter.apiToLlmMessage(newDriving),
                cacheKey = cacheKey,
                engineKey = acquired.cacheKey,
                temperature = temperature,
                topK = topK,
            )
        }

        if (cached != null) sessions.remove(cacheKey)
        val conversation = createConversation(acquired, temperature, topK, systemText, prior, tools)
        return Resolved(conversation, lastPrompt, cacheKey, acquired.cacheKey, temperature, topK)
    }

    private fun createConversation(
        acquired: EngineRegistry.AcquiredEngine.LiteRt,
        temperature: Float,
        topK: Int,
        systemText: String?,
        prior: List<Message>,
        tools: List<ToolDef>?,
    ): Conversation {
        purgeConversationsOnEngine(acquired.cacheKey)
        val conv = try {
            LlmMessageConverter.createConversation(
                engine = acquired.engine.native,
                temperature = temperature,
                topK = topK,
                systemText = systemText,
                initial = prior,
                tools = tools,
            )
        } catch (e: Exception) {
            if (e.message?.contains("session already exists", ignoreCase = true) == true) {
                LogManager.w("SessionManager", "Engine ${acquired.cacheKey.asString()} stuck; evicting.")
                registry.dropLiteRt(acquired.cacheKey)
                throw IllegalStateException("Engine had a stuck conversation; evicted. Please retry the request.", e)
            }
            throw e
        }
        registry.activeConversations[acquired.cacheKey] = conv
        return conv
    }

    private fun purgeConversationsOnEngine(engineKey: EngineKey) {
        val staleKeys = sessions.snapshot().filter { it.value.engineKey == engineKey }.keys
        staleKeys.forEach { sessions.remove(it) }
        registry.activeConversations.remove(engineKey)?.let { prior ->
            try { prior.close() } catch (_: Exception) {}
        }
    }

    /** Successful generation — commit (or refresh) the cached conversation. */
    fun commit(resolved: Resolved, messages: List<Message>) {
        val cacheKey = resolved.cacheKey ?: return
        sessions.put(cacheKey, CachedSession(
            conversation = resolved.conversation,
            engineKey = resolved.engineKey,
            temperature = resolved.temperature,
            topK = resolved.topK,
            prefixHash = messagesPrefixHash(messages, messages.size),
            seenCount = messages.size,
            createdAt = sessions.get(cacheKey)?.createdAt ?: System.currentTimeMillis(),
        ))
        registry.activeConversations[resolved.engineKey] = resolved.conversation
    }

    /** Failure path — drop the (possibly half-initialized) conversation. */
    fun invalidate(resolved: Resolved) {
        registry.activeConversations.remove(resolved.engineKey, resolved.conversation)
        val cacheKey = resolved.cacheKey
        if (cacheKey != null) {
            sessions.remove(cacheKey)
        } else {
            try { resolved.conversation.close() } catch (_: Exception) {}
        }
    }

    /** Stateless cleanup helper. */
    fun closeIfStateless(resolved: Resolved) {
        if (!resolved.isCached) {
            registry.activeConversations.remove(resolved.engineKey, resolved.conversation)
            try { resolved.conversation.close() } catch (_: Exception) {}
        }
    }

    /** Drop every cached session. Memory-pressure path. */
    fun evictAll() {
        sessions.evictAll()
    }

    fun size(): Int = sessions.size()
}

