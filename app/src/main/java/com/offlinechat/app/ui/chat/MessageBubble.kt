package com.offlinechat.app.ui.chat

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.offlinechat.app.domain.ChatMessage
import com.offlinechat.app.domain.MessageKind
import com.offlinechat.app.domain.MessageStatus
import com.offlinechat.app.domain.TransferState
import com.offlinechat.app.ui.components.Avatar
import com.offlinechat.app.ui.components.BubbleShape
import com.offlinechat.app.ui.components.StatusTicks
import com.offlinechat.app.ui.components.fileIcon
import com.offlinechat.app.ui.components.isImage
import com.offlinechat.app.ui.components.rememberThumbnail
import com.offlinechat.app.ui.theme.avatarColor
import com.offlinechat.app.ui.theme.senderColor
import com.offlinechat.app.ui.util.formatSize
import com.offlinechat.app.ui.util.formatTime

private val AvatarSlot = 32.dp

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageRow(
    item: ChatItem.Message,
    isGroup: Boolean,
    transfer: TransferState?,
    onClick: (ChatMessage) -> Unit,
    onLongClick: (ChatMessage) -> Unit,
    onCancel: (ChatMessage) -> Unit,
    modifier: Modifier = Modifier,
) {
    val m = item.message
    val incoming = !m.outgoing
    val scheme = MaterialTheme.colorScheme
    val bubbleColor = if (incoming) scheme.surfaceContainerHigh else scheme.primaryContainer
    val contentColor = if (incoming) scheme.onSurface else scheme.onPrimaryContainer
    val subtle = contentColor.copy(alpha = 0.62f)
    val maxWidth = (LocalConfiguration.current.screenWidthDp * 0.8f).dp

    Row(
        modifier
            .fillMaxWidth()
            .padding(top = if (item.topGap) 8.dp else 2.dp, start = 8.dp, end = 8.dp),
        horizontalArrangement = if (incoming) Arrangement.Start else Arrangement.End,
        verticalAlignment = Alignment.Bottom,
    ) {
        if (incoming && isGroup) {
            if (item.tail) {
                Avatar(m.senderName, avatarColor(m.senderId), size = 28.dp)
                Spacer(Modifier.width(4.dp))
            } else {
                Spacer(Modifier.width(AvatarSlot))
            }
        }
        Surface(
            shape = BubbleShape(incoming = incoming, tail = item.tail),
            color = bubbleColor,
            contentColor = contentColor,
            modifier = Modifier
                .widthIn(max = maxWidth)
                .semantics(mergeDescendants = true) {
                    contentDescription = describe(m, incoming)
                },
        ) {
            Column(
                Modifier
                    .combinedClickable(onClick = { onClick(m) }, onLongClick = { onLongClick(m) })
                    .padding(
                        start = if (incoming) BubbleShape.TAIL_WIDTH + 12.dp else 12.dp,
                        end = if (incoming) 12.dp else BubbleShape.TAIL_WIDTH + 12.dp,
                        top = 7.dp,
                        bottom = 5.dp,
                    ),
            ) {
                if (item.showSender) {
                    Text(
                        m.senderName,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = senderColor(m.senderId, scheme.background.luminance() < 0.5f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (m.kind == MessageKind.FILE) {
                    FileContent(m, transfer, subtle, onCancel)
                } else {
                    Text(m.text, style = MaterialTheme.typography.bodyLarge)
                }
                Row(
                    Modifier.align(Alignment.End).padding(top = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(formatTime(m.timestamp), style = MaterialTheme.typography.labelSmall, color = subtle)
                    if (m.outgoing) StatusTicks(m.status, tint = subtle)
                }
            }
        }
    }
}

@Composable
private fun FileContent(m: ChatMessage, transfer: TransferState?, subtle: Color, onCancel: (ChatMessage) -> Unit) {
    val ready = m.filePath != null && m.status == MessageStatus.DELIVERED || (m.outgoing && m.filePath != null)
    val thumbnail by rememberThumbnail(path = if (ready && isImage(m.fileMime)) m.filePath else null, maxPx = 640)
    val image = thumbnail
    if (image != null) {
        Image(
            bitmap = image,
            contentDescription = m.fileName,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 120.dp, max = 240.dp)
                .padding(top = 2.dp, bottom = 2.dp)
                .clip(RoundedCornerShape(12.dp)),
        )
        if (transfer != null) TransferProgress(m, transfer, subtle, onCancel)
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
        Box(
            Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(fileIcon(m.fileMime, m.fileName), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.widthIn(min = 120.dp, max = 220.dp)) {
            Text(
                m.fileName ?: "File",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                when {
                    transfer != null -> "${formatSize(transfer.done)} of ${formatSize(transfer.total)}"
                    m.status == MessageStatus.FAILED -> if (m.outgoing) "Failed. Tap to retry" else "Failed to receive"
                    else -> formatSize(m.fileSize)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (m.status == MessageStatus.FAILED) MaterialTheme.colorScheme.error else subtle,
            )
        }
    }
    if (transfer != null) TransferProgress(m, transfer, subtle, onCancel)
}

@Composable
private fun TransferProgress(m: ChatMessage, transfer: TransferState, subtle: Color, onCancel: (ChatMessage) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        LinearProgressIndicator(
            progress = { transfer.fraction },
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 6.dp),
            trackColor = subtle.copy(alpha = 0.25f),
        )
        IconButton(onClick = { onCancel(m) }, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Filled.Close, contentDescription = "Cancel transfer", modifier = Modifier.size(18.dp))
        }
    }
}

private fun describe(m: ChatMessage, incoming: Boolean): String {
    val who = if (incoming) m.senderName.ifBlank { "Them" } else "You"
    val body = if (m.kind == MessageKind.FILE) "file ${m.fileName.orEmpty()}" else m.text
    val status = if (m.outgoing) ", ${m.status.name.lowercase()}" else ""
    return "$who: $body, ${formatTime(m.timestamp)}$status"
}
