package com.kvi.osquio.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
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

private fun buildStyledInput(text: String, confirmedMentions: Set<String>, mentionColor: Color, mentionBg: Color): AnnotatedString {
    val regex = Regex("@(\\S+)")
    return buildAnnotatedString {
        var last = 0
        regex.findAll(text).forEach { match ->
            val name = match.groupValues[1]
            val isConfirmed = confirmedMentions.contains(name)
            append(text.substring(last, match.range.first))
            if (isConfirmed) {
                withStyle(SpanStyle(color = mentionColor, fontWeight = FontWeight.Bold, background = mentionBg)) {
                    append(match.value)
                }
            } else {
                append(match.value)
            }
            last = match.range.last + 1
        }
        append(text.substring(last))
    }
}

private val urlRegex = Regex("(https?://\\S+|www\\.\\S+)")
private val mentionRegex = Regex("@(\\S+)")

private fun buildMessageText(
    content: String,
    displayNames: Set<String>,
    mentionColor: Color,
    mentionBg: Color,
    linkColor: Color,
): AnnotatedString {
    val matches = (mentionRegex.findAll(content).map { it to false } + urlRegex.findAll(content).map { it to true })
        .sortedBy { it.first.range.first }

    return buildAnnotatedString {
        var last = 0
        for ((match, isUrl) in matches) {
            if (match.range.first < last) continue // skip overlapping match
            append(content.substring(last, match.range.first))
            if (isUrl) {
                val url = if (match.value.startsWith("http")) match.value else "https://${match.value}"
                pushStringAnnotation(tag = "URL", annotation = url)
                withStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)) {
                    append(match.value)
                }
                pop()
            } else {
                val name = match.groupValues[1]
                val isValid = name.equals("all", ignoreCase = true) ||
                    displayNames.any { it.equals(name, ignoreCase = true) }
                if (isValid) {
                    withStyle(SpanStyle(color = mentionColor, fontWeight = FontWeight.Bold, background = mentionBg)) {
                        append(match.value)
                    }
                } else {
                    append(match.value)
                }
            }
            last = match.range.last + 1
        }
        append(content.substring(last))
    }
}

@Composable
fun ChatScreen(
    currentUser: User,
    onNavigateToSettings: () -> Unit = {},
    vm: ChatViewModel,
) {
    val state by vm.state.collectAsState()
    val mentionSuggestions by vm.mentionSuggestions.collectAsState()
    val confirmedMentions by vm.confirmedMentions.collectAsState()

    LaunchedEffect(Unit) { vm.load() }

    Column(modifier = Modifier.fillMaxSize().padding(top = 48.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 16.dp, end = 16.dp)) {
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
                mentionSuggestions = mentionSuggestions,
                confirmedMentions = confirmedMentions,
                onInputChanged = { vm.onInputChanged(it) },
                onConfirmMention = { vm.confirmMention(it) },
                onMentionDismiss = { vm.dismissSuggestions() },
                onSend = { vm.sendMessage(currentUser.id, currentUser.displayName, it) },
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
    mentionSuggestions: List<User>,
    confirmedMentions: Set<String>,
    onInputChanged: (String) -> Unit,
    onConfirmMention: (String) -> Unit,
    onMentionDismiss: () -> Unit,
    onSend: (String) -> Unit,
    onScrolledToBottom: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val displayNames = remember(users) { users.values.map { it.displayName }.toSet() }

    val mentionColor = MaterialTheme.colorScheme.primary
    val mentionBg = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)

    var fieldValue by remember {
        mutableStateOf(TextFieldValue(""))
    }

    fun rebuildField(text: String, cursorPos: Int, mentions: Set<String>): TextFieldValue {
        val annotated = buildStyledInput(text, mentions, mentionColor, mentionBg)
        return TextFieldValue(annotated, selection = TextRange(cursorPos))
    }

    val chatItems = remember(messages) { buildChatItems(messages) }

    val isPinnedToBottom by remember {
        derivedStateOf {
            val layout = listState.layoutInfo
            val last = layout.visibleItemsInfo.lastOrNull() ?: return@derivedStateOf true
            last.index >= layout.totalItemsCount - 1
        }
    }

    var previousSize by remember { mutableIntStateOf(0) }
    val lastOwnMessageId = remember(messages) {
        messages.lastOrNull { it.userId == currentUser.id }?.id
    }

    LaunchedEffect(chatItems.size) {
        if (chatItems.isEmpty()) {
            previousSize = 0
            return@LaunchedEffect
        }
        val grew = chatItems.size > previousSize
        val isInitialLoad = previousSize == 0
        previousSize = chatItems.size
        if (isInitialLoad) {
            listState.scrollToItem(chatItems.size - 1)
        } else if (grew && isPinnedToBottom) {
            listState.animateScrollToItem(chatItems.size - 1)
        }
    }

    LaunchedEffect(lastOwnMessageId) {
        if (lastOwnMessageId != null && chatItems.isNotEmpty()) {
            listState.animateScrollToItem(chatItems.size - 1)
        }
    }

    LaunchedEffect(isPinnedToBottom, chatItems.size) {
        if (isPinnedToBottom && chatItems.isNotEmpty()) onScrolledToBottom()
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
                        displayNames = displayNames,
                    )
                }
            }
        }

        if (mentionSuggestions.isNotEmpty()) {
            MentionSuggestions(
                suggestions = mentionSuggestions,
                onSelect = { user ->
                    val text = fieldValue.text
                    val atIndex = text.lastIndexOf('@')
                    if (atIndex != -1) {
                        val name = if (user == null) "all" else user.displayName
                        val newText = text.substring(0, atIndex) + "@$name "
                        onConfirmMention(name)
                        fieldValue = rebuildField(newText, newText.length, confirmedMentions + name)
                        onInputChanged(newText)
                    }
                    onMentionDismiss()
                },
            )
        }

        HorizontalDivider()
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val textStyle = MaterialTheme.typography.bodyLarge.copy(
                color = MaterialTheme.colorScheme.onSurface,
            )
            val charCount = fieldValue.text.length

            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(24.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                if (fieldValue.text.isEmpty()) {
                    Text(
                        "Message...",
                        style = textStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    )
                }
                Column {
                    BasicTextField(
                        value = fieldValue,
                        onValueChange = { newVal ->
                            if (newVal.text.length <= 200) {
                                fieldValue = rebuildField(newVal.text, newVal.selection.end, confirmedMentions)
                                onInputChanged(newVal.text)
                            }
                        },
                        textStyle = textStyle,
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (charCount >= 160) {
                        Text(
                            "${200 - charCount}",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (charCount >= 190) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            IconButton(
                onClick = {
                    onSend(fieldValue.text)
                    fieldValue = TextFieldValue(AnnotatedString(""))
                    onMentionDismiss()
                },
                enabled = fieldValue.text.isNotBlank(),
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
            }
        }
    }
}

@Composable
private fun MentionSuggestions(
    suggestions: List<User>,
    onSelect: (User?) -> Unit,
) {
    Surface(
        shadowElevation = 4.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(null) }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(Modifier.width(38.dp))
                Text("@all", style = MaterialTheme.typography.bodyMedium)
            }
            suggestions.forEach { user ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(user) }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AsyncImage(
                        model = user.avatarUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(28.dp).clip(RoundedCornerShape(6.dp)),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(user.displayName, style = MaterialTheme.typography.bodyMedium)
                }
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
    displayNames: Set<String>,
) {
    val lastMessage = group.messages.last()
    val timestamp = remember(lastMessage.createdAt) {
        runCatching { timeFmt.format(java.time.OffsetDateTime.parse(lastMessage.createdAt)) }.getOrDefault("")
    }
    val avatarUrl = if (isOwn) ownAvatarUrl else senderAvatarUrl
    val mentionColor = if (isOwn) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary
    val mentionBg = if (isOwn)
        MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.2f)
    else
        MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
    val linkColor = if (isOwn) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary
    val uriHandler = LocalUriHandler.current

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isOwn) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Top,
    ) {
        if (!isOwn) {
            AsyncImage(
                model = avatarUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)),
            )
            Spacer(Modifier.width(8.dp))
        }

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
                val annotated = remember(message.content, displayNames, mentionColor, mentionBg, linkColor) {
                    buildMessageText(message.content, displayNames, mentionColor, mentionBg, linkColor)
                }
                Surface(
                    shape = RoundedCornerShape(
                        topStart = 16.dp,
                        topEnd = 16.dp,
                        bottomStart = if (isOwn) 16.dp else 4.dp,
                        bottomEnd = if (isOwn) 4.dp else 16.dp,
                    ),
                    color = if (isOwn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    SelectionContainer {
                        ClickableText(
                            annotated,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.bodyMedium.copy(
                                color = if (isOwn) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                            onClick = { offset ->
                                annotated.getStringAnnotations("URL", offset, offset).firstOrNull()?.let {
                                    uriHandler.openUri(it.item)
                                }
                            },
                        )
                    }
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
