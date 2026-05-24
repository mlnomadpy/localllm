package com.localllm.app.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.localllm.app.inference.aicore.AICoreEngine

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatHeaderRow(
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
