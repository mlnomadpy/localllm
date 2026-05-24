package com.localllm.app

import com.google.gson.Gson
import com.google.gson.JsonNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Edge cases complementing [ApiTypesTest] — focused on the JsonNull branch of
 * `content`, the string-shaped `image_url` field, and `textChars` for the
 * unusual content shapes (null, JsonNull, parts with no text).
 */
class ApiTypesContentTest {

    private val gson = Gson()

    @Test
    fun `Kotlin null content yields no parts and empty contentString`() {
        val msg = Message(role = "assistant", content = null)
        assertNull(msg.contentString())
        assertEquals(emptyList<ContentPart>(), msg.contentParts())
        assertEquals(0, msg.textChars())
    }

    @Test
    fun `explicit JsonNull content behaves like Kotlin null`() {
        val msg = Message(role = "assistant", content = JsonNull.INSTANCE)
        assertNull("JsonNull is not a string primitive", msg.contentString())
        assertEquals("JsonNull yields no parts", emptyList<ContentPart>(), msg.contentParts())
        assertEquals("JsonNull contributes 0 to textChars", 0, msg.textChars())
    }

    @Test
    fun `content null deserialized from wire becomes a JsonNull or Kotlin null`() {
        // Gson sometimes maps `"content":null` to Kotlin null, sometimes to
        // JsonNull depending on TypeAdapter wiring. Both forms must be safe.
        val parsed = gson.fromJson("""{"role":"assistant","content":null}""", Message::class.java)
        assertNull(parsed.contentString())
        assertEquals(0, parsed.textChars())
        assertTrue(parsed.contentParts().isEmpty())
    }

    @Test
    fun `image_url as raw string is preserved as the part url`() {
        val json = """
            {"role":"user","content":[
              {"type":"image_url","image_url":"data:image/png;base64,QQQQ"}
            ]}
        """.trimIndent()
        val parts = gson.fromJson(json, Message::class.java).contentParts()
        assertEquals(1, parts.size)
        val p = parts[0]
        assertTrue("expected ImagePart, got $p", p is ContentPart.ImagePart)
        assertEquals("data:image/png;base64,QQQQ", (p as ContentPart.ImagePart).url)
    }

    @Test
    fun `image_url with non-string non-object value is skipped`() {
        // image_url is a number — the parser should ignore the part rather
        // than crash, per the "be permissive about forward-compat parts" doc.
        val json = """
            {"role":"user","content":[
              {"type":"image_url","image_url":42},
              {"type":"text","text":"survivor"}
            ]}
        """.trimIndent()
        val parts = gson.fromJson(json, Message::class.java).contentParts()
        assertEquals(1, parts.size)
        assertEquals("survivor", (parts[0] as ContentPart.TextPart).text)
    }

    @Test
    fun `unknown part types are silently skipped`() {
        val json = """
            {"role":"user","content":[
              {"type":"input_audio","data":"AAAA"},
              {"type":"text","text":"only this"}
            ]}
        """.trimIndent()
        val parts = gson.fromJson(json, Message::class.java).contentParts()
        assertEquals(1, parts.size)
        assertEquals("only this", (parts[0] as ContentPart.TextPart).text)
    }

    @Test
    fun `textChars counts text parts but ignores image parts`() {
        val json = """
            {"role":"user","content":[
              {"type":"text","text":"hello"},
              {"type":"image_url","image_url":"data:,"},
              {"type":"text","text":"world!"}
            ]}
        """.trimIndent()
        val msg = gson.fromJson(json, Message::class.java)
        assertEquals(5 + 6, msg.textChars())
    }

    @Test
    fun `textChars for a non-string non-array content falls back to JSON length`() {
        // Per the source: when content is a non-string primitive (number /
        // boolean), textChars uses its serialized length rather than 0.
        val json = """{"role":"user","content":12345}"""
        val msg = gson.fromJson(json, Message::class.java)
        assertEquals("12345".length, msg.textChars())
    }

    @Test
    fun `image_url object form with missing url field is skipped`() {
        val json = """
            {"role":"user","content":[
              {"type":"image_url","image_url":{}},
              {"type":"text","text":"keep"}
            ]}
        """.trimIndent()
        val parts = gson.fromJson(json, Message::class.java).contentParts()
        assertEquals(1, parts.size)
        assertEquals("keep", (parts[0] as ContentPart.TextPart).text)
    }
}
