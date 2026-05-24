package com.localllm.app.inference.litert

import com.google.ai.edge.litertlm.Content
import com.localllm.app.ContentPart
import com.localllm.app.Message
import com.localllm.app.ToolCallApi
import com.localllm.app.ToolCallFunction
import android.graphics.Bitmap
import com.localllm.app.partsContent
import com.localllm.app.stringContent
import java.io.ByteArrayOutputStream
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pure conversion helpers between OpenAI wire types and LiteRT-LM's native
 * message model. Image loading is exercised via the data: URL path which is
 * fully synchronous and avoids the OkHttp fetch branch (covered separately
 * by the SSRF tests in MessageHelpersTest).
 *
 * Bitmap decode requires Robolectric so we can resolve `BitmapFactory`.
 */
@RunWith(RobolectricTestRunner::class)
class LlmMessageConverterTest {

    private fun textMsg(role: String, text: String) =
        Message(role = role, content = stringContent(text))

    @Test
    fun `messageText concatenates Text parts and ignores everything else`() {
        val msg = com.google.ai.edge.litertlm.Message.user(
            com.google.ai.edge.litertlm.Contents.of(
                listOf(
                    Content.Text("hello "),
                    Content.Text("world"),
                ),
            ),
        )
        assertEquals("hello world", LlmMessageConverter.messageText(msg))
    }

    @Test
    fun `messageText on an empty contents returns empty string`() {
        val msg = com.google.ai.edge.litertlm.Message.user(
            com.google.ai.edge.litertlm.Contents.of(emptyList()),
        )
        assertEquals("", LlmMessageConverter.messageText(msg))
    }

    @Test
    fun `apiToLlmMessage on user role builds a user message with text content`() {
        val out = LlmMessageConverter.apiToLlmMessage(textMsg("user", "hi"))
        assertEquals("hi", LlmMessageConverter.messageText(out))
    }

    @Test
    fun `apiToLlmMessage on system role builds a system message`() {
        val out = LlmMessageConverter.apiToLlmMessage(textMsg("system", "be brief"))
        assertEquals("be brief", LlmMessageConverter.messageText(out))
        // System messages carry no toolCalls.
        assertTrue(out.toolCalls.orEmpty().isEmpty())
    }

    @Test
    fun `apiToLlmMessage on assistant with tool_calls propagates them`() {
        val msg = Message(
            role = "assistant",
            content = null,
            toolCalls = listOf(
                ToolCallApi(
                    id = "call_1",
                    type = "function",
                    function = ToolCallFunction(
                        name = "lookup",
                        arguments = "{\"q\":\"hello\"}",
                    ),
                ),
            ),
        )
        val out = LlmMessageConverter.apiToLlmMessage(msg)
        assertEquals(1, out.toolCalls?.size)
        assertEquals("lookup", out.toolCalls?.first()?.name)
        // The arguments string is parsed into a map keyed "q".
        assertEquals("hello", out.toolCalls?.first()?.arguments?.get("q"))
    }

    @Test
    fun `apiToLlmMessage on tool role uses the tool_call_id as the response name`() {
        val msg = Message(
            role = "tool",
            content = stringContent("{\"ok\":true}"),
            toolCallId = "call_42",
        )
        val out = LlmMessageConverter.apiToLlmMessage(msg)
        val toolResp = out.contents.contents.firstOrNull { it is Content.ToolResponse } as? Content.ToolResponse
        assertNotNull(toolResp)
    }

    @Test
    fun `buildContents flattens text parts and drops empty strings`() {
        val msg = Message(
            role = "user",
            content = partsContent(
                listOf(
                    ContentPart.TextPart("hello"),
                    ContentPart.TextPart(""),
                    ContentPart.TextPart("world"),
                ),
            ),
        )
        val out = LlmMessageConverter.buildContents(msg)
        assertEquals(2, out.size)
        assertTrue(out.all { it is Content.Text })
    }

    @Test
    fun `loadImageBytesForAICore decodes a small data url verbatim`() {
        // Generate a real 4x4 RGB PNG via ImageIO so Robolectric's
        // BitmapFactory can decode it. Under MAX_IMAGE_DIM so no
        // downscale path runs and the bytes round-trip unchanged.
        val pngBytes = tinyPng(width = 4, height = 4)
        val b64 = Base64.getEncoder().encodeToString(pngBytes)
        val out = LlmMessageConverter.loadImageBytesForAICore("data:image/png;base64,$b64")
        assertEquals(pngBytes.size, out.size)
    }

    private fun tinyPng(width: Int, height: Int): ByteArray {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        return out.toByteArray()
    }

    @Test
    fun `loadImageBytesForAICore rejects non-data, non-loopback schemes`() {
        val ex = runCatching {
            LlmMessageConverter.loadImageBytesForAICore("https://example.com/cat.png")
        }.exceptionOrNull()
        assertNotNull(ex)
        assertTrue(
            "expected scheme rejection, got '${ex?.message}'",
            ex is IllegalArgumentException && ex.message?.contains("scheme") == true,
        )
    }

}
