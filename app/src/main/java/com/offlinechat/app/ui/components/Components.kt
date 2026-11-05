package com.offlinechat.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.offlinechat.app.domain.MessageStatus
import com.offlinechat.app.ui.util.initials

@Composable
fun Avatar(
    name: String,
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    isGroup: Boolean = false,
    online: Boolean? = null,
) {
    Box(modifier.size(size)) {
        Box(
            Modifier
                .fillMaxSize()
                .clip(CircleShape)
                .background(color),
            contentAlignment = Alignment.Center,
        ) {
            if (isGroup) {
                Icon(Icons.Filled.Group, contentDescription = null, tint = Color.White, modifier = Modifier.size(size * 0.5f))
            } else {
                Text(initials(name), color = Color.White, fontWeight = FontWeight.Bold, fontSize = (size.value * 0.36f).sp)
            }
        }
        if (online != null) {
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .size(size * 0.3f)
                    .background(MaterialTheme.colorScheme.surface, CircleShape)
                    .padding(2.dp)
                    .background(if (online) OnlineGreen else MaterialTheme.colorScheme.outline, CircleShape),
            )
        }
    }
}

val OnlineGreen = Color(0xFF22C55E)

@Composable
fun StatusTicks(status: MessageStatus, tint: Color, modifier: Modifier = Modifier) {
    Crossfade(targetState = status, modifier = modifier, label = "status") { s ->
        val primary = MaterialTheme.colorScheme.primary
        val error = MaterialTheme.colorScheme.error
        val (icon, description, color) = when (s) {
            MessageStatus.SENDING -> Triple(Icons.Filled.Schedule, "Sending", tint)
            MessageStatus.SENT -> Triple(Icons.Filled.Done, "Sent", tint)
            MessageStatus.DELIVERED -> Triple(Icons.Filled.DoneAll, "Delivered", primary)
            MessageStatus.FAILED -> Triple(Icons.Filled.ErrorOutline, "Failed. Tap to retry", error)
        }
        Icon(icon, contentDescription = description, tint = color, modifier = Modifier.size(15.dp))
    }
}

@Composable
fun TypingDots(color: Color, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "typing")
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { index ->
            val alpha by transition.animateFloat(
                initialValue = 0.25f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 500, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse,
                    initialStartOffset = StartOffset(index * 160),
                ),
                label = "dot$index",
            )
            Box(
                Modifier
                    .size(6.dp)
                    .graphicsLayer { this.alpha = alpha }
                    .background(color, CircleShape),
            )
        }
    }
}

enum class LinkState { CONNECTED, CONNECTING, OFFLINE, ENDED }

/** Slides in under the top bar whenever the link to the other side is not healthy. */
@Composable
fun ConnectionBanner(state: LinkState, modifier: Modifier = Modifier) {
    val last = remember { mutableStateOf(LinkState.OFFLINE) }
    if (state != LinkState.CONNECTED) last.value = state
    AnimatedVisibility(
        visible = state != LinkState.CONNECTED,
        modifier = modifier,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut(),
    ) {
        val shown = last.value
        val container = when (shown) {
            LinkState.CONNECTING -> MaterialTheme.colorScheme.secondaryContainer
            LinkState.OFFLINE -> MaterialTheme.colorScheme.errorContainer
            else -> MaterialTheme.colorScheme.surfaceVariant
        }
        val content = when (shown) {
            LinkState.CONNECTING -> MaterialTheme.colorScheme.onSecondaryContainer
            LinkState.OFFLINE -> MaterialTheme.colorScheme.onErrorContainer
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        }
        Surface(color = container, contentColor = content, modifier = Modifier.fillMaxWidth()) {
            Row(
                Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (shown == LinkState.CONNECTING) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = content)
                }
                Text(
                    text = when (shown) {
                        LinkState.CONNECTING -> "Reconnecting…"
                        LinkState.OFFLINE -> "Not connected. Move closer to the other phone."
                        else -> "This group has ended."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier
                .size(88.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(44.dp))
        }
        Spacer(Modifier.height(20.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (action != null) {
            Spacer(Modifier.height(20.dp))
            action()
        }
    }
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
    )
}

/** Four growing bars, filled up to [bars]. */
@Composable
fun SignalBars(bars: Int, modifier: Modifier = Modifier) {
    val on = MaterialTheme.colorScheme.primary
    val off = MaterialTheme.colorScheme.outlineVariant
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.Bottom) {
        for (i in 1..4) {
            Box(
                Modifier
                    .width(4.dp)
                    .height((4 + i * 3).dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(if (i <= bars) on else off),
            )
        }
    }
}
