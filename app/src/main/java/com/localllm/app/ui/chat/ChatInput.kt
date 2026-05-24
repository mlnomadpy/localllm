package com.localllm.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.localllm.app.R

@Composable
internal fun ChatInputBar(
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
