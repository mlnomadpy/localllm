package com.localllm.app.download

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ModelDirectoryScannerTest {

    private lateinit var context: Context
    private lateinit var modelDir: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        modelDir = context.getExternalFilesDir(null)!!
        modelDir.listFiles()?.forEach { it.delete() }
    }

    private fun writeFile(name: String, bytes: ByteArray): File {
        val f = File(modelDir, name)
        f.writeBytes(bytes)
        return f
    }

    @Test
    fun `scanOnce on empty dir returns an empty snapshot`() {
        val snap = ModelDirectoryScanner(context).scanOnce()
        assertTrue(snap.existingModels.isEmpty())
        assertTrue(snap.modelSizes.isEmpty())
        assertTrue(snap.modelMtimes.isEmpty())
    }

    @Test
    fun `scanOnce filters out non-litertlm files`() {
        writeFile("ignored.txt", ByteArray(10))
        writeFile("good.litertlm", ByteArray(100))
        writeFile("nope.onnx", ByteArray(50))
        val snap = ModelDirectoryScanner(context).scanOnce()
        assertEquals(setOf("good.litertlm"), snap.existingModels)
    }

    @Test
    fun `scanOnce reports byte sizes for every matched file`() {
        writeFile("a.litertlm", ByteArray(123))
        writeFile("b.litertlm", ByteArray(456))
        val snap = ModelDirectoryScanner(context).scanOnce()
        assertEquals(123L, snap.modelSizes["a.litertlm"])
        assertEquals(456L, snap.modelSizes["b.litertlm"])
        // mtimes are present and > 0 for every entry.
        snap.modelMtimes.values.forEach { assertTrue(it > 0L) }
    }

    @Test
    fun `EMPTY constant is reusable across callers`() {
        assertEquals(ModelDirSnapshot.EMPTY, ModelDirSnapshot.EMPTY)
        assertTrue(ModelDirSnapshot.EMPTY.existingModels.isEmpty())
    }
}
