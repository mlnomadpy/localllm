package com.localllm.app.composables

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import com.localllm.app.LogManager
import java.io.File
import kotlinx.coroutines.launch

/**
 * Wraps the `OpenDocument` activity-result launcher used to import an
 * external `.litertlm` model file into the app's external files dir.
 *
 * Returns a launch function. Calling it opens the system picker; on a
 * successful pick the file is copied to `getExternalFilesDir(null)/<name>`,
 * the name is normalized to end with `.litertlm`, and [onImported] fires
 * with the final on-disk filename so the caller can refresh its model list.
 */
@Composable
fun rememberFilePicker(onImported: (filename: String) -> Unit): () -> Unit {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            coroutineScope.launch {
                try {
                    val cursor = context.contentResolver.query(uri, null, null, null, null)
                    val nameIndex = cursor?.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    cursor?.moveToFirst()
                    var name = if (nameIndex != null && nameIndex >= 0)
                        cursor?.getString(nameIndex) ?: "imported_model.litertlm"
                    else "imported_model.litertlm"
                    cursor?.close()
                    if (!name.endsWith(".litertlm")) name = "$name.litertlm"

                    val destFile = File(context.getExternalFilesDir(null), name)
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        destFile.outputStream().use { output -> input.copyTo(output) }
                    }
                    onImported(name)
                    LogManager.i("FilePicker", "Imported $name")
                } catch (e: Exception) {
                    LogManager.e("FilePicker", "Failed to import model", e)
                }
            }
        }
    }
    return { launcher.launch(arrayOf("*/*")) }
}
