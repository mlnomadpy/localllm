package com.localllm.app.ui.chat

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.localllm.app.ui.ChatBubble
import com.localllm.app.ui.ChatEmptyState
import com.localllm.app.ui.UiMessage

@Composable
internal fun ColumnScope.MessageList(
    chatMessages: List<UiMessage>,
    chatListState: LazyListState,
    isChatting: Boolean,
    streamingAssistantIndex: Int,
    lastDelta: Int,
    onInputChange: (String) -> Unit,
) {
    LazyColumn(
        state = chatListState,
        modifier = Modifier
            .weight(1f)
            .fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
    ) {
        if (chatMessages.isEmpty()) {
            item {
                ChatEmptyState(onPromptSelected = onInputChange)
            }
        }
        itemsIndexed(chatMessages) { index, msg ->
            val isStreaming =
                isChatting && index == streamingAssistantIndex && msg.role == "assistant"
            ChatBubble(
                msg = msg,
                isStreaming = isStreaming,
                lastDeltaLength = if (isStreaming) lastDelta else 0,
            )
        }
        if (isChatting) {
            item {
                CircularProgressIndicator(
                    modifier = Modifier
                        .size(24.dp)
                        .padding(8.dp),
                    strokeWidth = 2.dp,
                )
            }
        }
    }
}
