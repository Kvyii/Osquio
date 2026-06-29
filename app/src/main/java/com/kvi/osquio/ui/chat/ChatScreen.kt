package com.kvi.osquio.ui.chat

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.kvi.osquio.data.model.Message
import com.kvi.osquio.data.model.User
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val timeFmt = DateTimeFormatter.ofPattern("h:mm a").withZone(ZoneId.systemDefault())
private val dateFmt = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy").withZone(ZoneId.systemDefault())

private sealed interface ChatItem {
    data class DateHeader(val date: LocalDate) : ChatItem
    data class Group(val messages: List<Message>, val userId: String) : ChatItem
}

private fun parseLocalDate(createdAt: String): LocalDate? = runCatching {
    java.time.OffsetDateTime.parse(createdAt).atZoneSameInstant(ZoneId.systemDefault()).toLocalDate()
}.getOrNull()

private fun isSameGroup(a: Message, b: Message): Boolean {
    if (a.userId != b.userId) return false
    return runCatching {
        val t1 = java.time.OffsetDateTime.parse(a.createdAt)
        val t2 = java.time.OffsetDateTime.parse(b.createdAt)
        java.time.Duration.between(t1, t2).abs().toMinutes() < 5
    }.getOrDefault(false)
}

private fun buildChatItems(messages: List<Message>): List<ChatItem> {
    val items = mutableListOf<ChatItem>()
    var lastDate: LocalDate? = null
    var i = 0
    while (i < messages.size) {
        val message = messages[i]
        val date = parseLocalDate(message.createdAt)
        if (date == null) { i++; continue }
        if (date != lastDate) {
            items.add(ChatItem.DateHeader(date))
            lastDate = date
        }
        val group = mutableListOf(message)
        while (i + group.size < messages.size && isSameGroup(message, messages[i + group.size])) {
            group.add(messages[i + group.size])
        }
        items.add(ChatItem.Group(group, message.userId))
        i += group.size
    }
    return items
}

@Composable
fun ChatScreen(
    currentUser: User,
    onNavigateToSettings: () -> Unit = {},
    vm: ChatViewModel,
) {
    val state by vm.state.collectAsState()

    LaunchedEffect(Unit) { vm.load() }

    Column(modifier = Modifier.fillMaxSize().padding(top = 48.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 16.dp)) {
            Text("Chat", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            IconButton(onClick = onNavigateToSettings) { Icon(Icons.Default.Settings, contentDescription = "Settings") }
        }

        when (val s = state) {
            is ChatUiState.Loading -> Box(Modifier.weight(1f).fillMaxWidth(), Alignment.Center) {
                CircularProgressIndicator()
            }
            is ChatUiState.Error -> Box(Modifier.weight(1f).fillMaxWidth(), Alignment.Center) {
                Text(s.message, color = MaterialTheme.colorScheme.error)
            }
            is ChatUiState.Loaded -> ChatContent(
                messages = s.messages,
                users = s.users,
                currentUser = currentUser,
                onSend = { vm.sendMessage(currentUser.id, it) },
                onScrolledToBottom = { vm.markRead() },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun ChatContent(
    messages: List<Message>,
    users: Map<String, User>,
    currentUser: User,
    onSend: (String) -> Unit,
    onScrolledToBottom: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    var inputText by remember { mutableStateOf("") }

    val chatItems = remember(messages) { buildChatItems(messages) }

    LaunchedEffect(chatItems.size) {
        if (chatItems.isNotEmpty()) listState.animateScrollToItem(chatItems.size - 1)
    }

    val lastVisibleIndex = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index
    LaunchedEffect(lastVisibleIndex) {
        if (chatItems.isNotEmpty() && lastVisibleIndex == chatItems.size - 1) onScrolledToBottom()
    }

    Column(modifier = modifier.fillMaxWidth()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(chatItems, key = {
                when (it) {
                    is ChatItem.DateHeader -> "date-${it.date}"
                    is ChatItem.Group -> it.messages.first().id
                }
            }) { item ->
                when (item) {
                    is ChatItem.DateHeader -> DateDivider(item.date)
                    is ChatItem.Group -> MessageGroup(
                        group = item,
                        isOwn = item.userId == currentUser.id,
                        ownAvatarUrl = currentUser.avatarUrl,
                        senderAvatarUrl = users[item.userId]?.avatarUrl,
                        senderName = users[item.userId]?.displayName ?: "Unknown",
                    )
                }
            }
        }

        HorizontalDivider()
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = inputText,
                onValueChange = { if (it.length <= 200) inputText = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Message...") },
                singleLine = true,
                shape = RoundedCornerShape(24.dp),
                suffix = if (inputText.length >= 160) {
                    { Text("${200 - inputText.length}", style = MaterialTheme.typography.labelSmall, color = if (inputText.length >= 190) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) }
                } else null,
            )
            Spacer(Modifier.width(8.dp))
            IconButton(
                onClick = { onSend(inputText); inputText = "" },
                enabled = inputText.isNotBlank(),
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
            }
        }
    }
}

@Composable
private fun DateDivider(date: LocalDate) {
    val label = remember(date) { dateFmt.format(date.atStartOfDay(ZoneId.systemDefault())) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        HorizontalDivider(modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        HorizontalDivider(modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
    }
}

@Composable
private fun MessageGroup(
    group: ChatItem.Group,
    isOwn: Boolean,
    ownAvatarUrl: String?,
    senderAvatarUrl: String?,
    senderName: String,
) {
    val lastMessage = group.messages.last()
    val timestamp = remember(lastMessage.createdAt) {
        runCatching { timeFmt.format(java.time.OffsetDateTime.parse(lastMessage.createdAt)) }.getOrDefault("")
    }
    val avatarUrl = if (isOwn) ownAvatarUrl else senderAvatarUrl

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isOwn) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Top,
    ) {
        // Avatar on left for others
        if (!isOwn) {
            AsyncImage(
                model = avatarUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)),
            )
            Spacer(Modifier.width(8.dp))
        }

        // All bubbles + name + timestamp in one column
        Column(
            horizontalAlignment = if (isOwn) Alignment.End else Alignment.Start,
            modifier = Modifier.widthIn(max = 280.dp),
        ) {
            if (!isOwn) {
                Text(
                    senderName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 4.dp, bottom = 2.dp),
                )
            }
            group.messages.forEach { message ->
                Surface(
                    shape = RoundedCornerShape(
                        topStart = 16.dp,
                        topEnd = 16.dp,
                        bottomStart = if (isOwn) 16.dp else 4.dp,
                        bottomEnd = if (isOwn) 4.dp else 16.dp,
                    ),
                    color = if (isOwn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Text(
                        message.content,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isOwn) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (message != lastMessage) Spacer(Modifier.height(2.dp))
            }
            Text(
                timestamp,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.padding(
                    start = if (isOwn) 0.dp else 4.dp,
                    end = if (isOwn) 4.dp else 0.dp,
                    top = 2.dp,
                ),
            )
        }

        // Avatar on right for own
        if (isOwn) {
            Spacer(Modifier.width(8.dp))
            AsyncImage(
                model = avatarUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)),
            )
        }
    }
}
