package com.supportbubble.app.ui.overlay

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.supportbubble.app.services.OverlayChatState
import com.supportbubble.app.ui.MessageBubble
import com.supportbubble.app.ui.theme.SupportBubbleTheme

// ── Entry point ───────────────────────────────────────────────────────────────

/**
 * Full overlay chat panel composable.
 *
 * Renders inside a [android.view.WindowManager] overlay managed by [OverlayService].
 * All state is owned by [OverlayChatState] so there is no dependency on
 * an Android [androidx.lifecycle.ViewModel].
 *
 * @param state     shared state holder providing messages, connection status, etc.
 * @param onMinimize called when the user taps the collapse/minimize button.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OverlayChatPanel(
    state: OverlayChatState,
    onMinimize: () -> Unit,
) {
    val messages by state.messages.collectAsStateWithLifecycle()
    val connected by state.connected.collectAsStateWithLifecycle()
    val adminTyping by state.adminTyping.collectAsStateWithLifecycle()
    val inputText by state.inputText.collectAsStateWithLifecycle()
    val refreshing by state.refreshing.collectAsStateWithLifecycle()
    val hasUnread by state.hasUnread.collectAsStateWithLifecycle()
    val imeHeightPx by state.imeHeightPx.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    // Soft-keyboard height → animated bottom padding. In an overlay window
    // Compose's own imePadding() never receives IME insets, so OverlayService
    // measures the keyboard and feeds the height here. animateDpAsState makes the
    // panel rise/settle smoothly; 0 px (keyboard closed) leaves the panel unchanged.
    val imeBottomPadding by animateDpAsState(
        targetValue = with(LocalDensity.current) { imeHeightPx.toDp() },
        label = "imeBottomPadding",
    )

    // Scroll to bottom whenever a new message arrives
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    // Keep the latest messages visible when the keyboard opens and shrinks the list.
    LaunchedEffect(imeHeightPx) {
        if (imeHeightPx > 0 && messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    // On open: pull the latest messages from the server, THEN mark them read
    // (ordered inside onChatOpened to avoid a late fetch re-showing the dot).
    LaunchedEffect(Unit) {
        state.onChatOpened()
    }

    SupportBubbleTheme {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = imeBottomPadding),
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
            shadowElevation = 24.dp,
            tonalElevation = 2.dp,
        ) {
            Column(modifier = Modifier.fillMaxSize()) {

                // ── Header ────────────────────────────────────────────────────
                PanelHeader(
                    connected = connected,
                    refreshing = refreshing,
                    hasUnread = hasUnread,
                    onRefresh = state::refreshMessages,
                    onMinimize = onMinimize,
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                // ── Message list (with pull-to-refresh) ───────────────────────
                PullToRefreshBox(
                    isRefreshing = refreshing,
                    onRefresh = state::refreshMessages,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.background),
                ) {
                    if (messages.isEmpty()) {
                        Text(
                            text = "No messages yet.\nSay hello! 👋",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.align(Alignment.Center),
                        )
                    }

                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(vertical = 8.dp),
                    ) {
                        items(
                            items = messages,
                            key = { it.id },
                        ) { message ->
                            MessageBubble(message = message)
                        }
                    }
                }

                // ── Admin typing indicator ────────────────────────────────────
                AnimatedVisibility(
                    visible = adminTyping,
                    enter = fadeIn(),
                    exit = fadeOut(),
                ) {
                    Text(
                        text = "Support is typing…",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                // ── Input area ────────────────────────────────────────────────
                InputArea(
                    text = inputText,
                    onTextChange = state::onInputChanged,
                    onSend = state::sendMessage,
                )
            }
        }
    }
}

// ── Sub-composables ───────────────────────────────────────────────────────────

@Composable
private fun PanelHeader(
    connected: Boolean,
    refreshing: Boolean,
    hasUnread: Boolean,
    onRefresh: () -> Unit,
    onMinimize: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.primary)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Online / offline indicator dot
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(
                    if (connected) Color(0xFF66BB6A) else Color(0xFFBDBDBD)
                ),
        )

        Spacer(Modifier.width(10.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Support",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
            )
            Text(
                text = if (connected) "Online" else "Connecting…",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.75f),
            )
        }

        // Refresh button — manual pull of new messages, with a red "unread" dot.
        Box {
            IconButton(
                onClick = onRefresh,
                enabled = !refreshing,
            ) {
                if (refreshing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        strokeWidth = 2.dp,
                        color = Color.White,
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Refresh messages",
                        tint = Color.White,
                        modifier = Modifier.size(26.dp),
                    )
                }
            }
            // Red unread indicator dot, shown only when there are unread admin messages.
            if (hasUnread && !refreshing) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .size(9.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFE53935)),
                )
            }
        }

        // Minimize / collapse button
        IconButton(onClick = onMinimize) {
            Icon(
                imageVector = Icons.Default.KeyboardArrowDown,
                contentDescription = "Minimize chat",
                tint = Color.White,
                modifier = Modifier.size(28.dp),
            )
        }
    }
}

@Composable
private fun InputArea(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
) {
    val canSend = text.isNotBlank()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = onTextChange,
            placeholder = {
                Text(
                    text = "Type a message…",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(24.dp),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Sentences,
                imeAction = ImeAction.Send,
            ),
            keyboardActions = KeyboardActions(onSend = { onSend() }),
            maxLines = 3,
            textStyle = MaterialTheme.typography.bodyMedium,
        )

        Spacer(Modifier.width(8.dp))

        IconButton(
            onClick = onSend,
            enabled = canSend,
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(
                    if (canSend) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surfaceVariant,
                ),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Send,
                contentDescription = "Send message",
                tint = if (canSend) Color.White
                       else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
