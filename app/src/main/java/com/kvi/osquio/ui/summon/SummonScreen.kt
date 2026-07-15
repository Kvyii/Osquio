package com.kvi.osquio.ui.summon

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.ui.res.painterResource
import com.kvi.osquio.R
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.kvi.osquio.data.model.Config
import com.kvi.osquio.data.model.Rsvp
import com.kvi.osquio.data.model.User
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val timeFmt = DateTimeFormatter.ofPattern("h:mm a").withZone(ZoneId.systemDefault())

private val QUICK_MINUTES = listOf(15, 30, 45, 60)

@Composable
fun SummonScreen(currentUser: User, vm: SummonViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    val notifyError by vm.notifyError.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(currentUser.id) { vm.load(currentUser) }

    LaunchedEffect(notifyError) {
        val error = notifyError ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(error)
        vm.clearNotifyError()
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { padding ->
    Box(Modifier.padding(padding)) {
    when (val s = state) {
        is SummonUiState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        is SummonUiState.Error -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(s.message, color = MaterialTheme.colorScheme.error)
        }
        is SummonUiState.NoActiveSummon -> {
            val isCreating by vm.isCreating.collectAsState()
            SummonCreationContent(
                currentUser = currentUser,
                config = s.config,
                cooldownRemaining = s.cooldownRemaining,
                isCreating = isCreating,
                onSummon = { gameTime -> vm.createSummon(currentUser, gameTime) },
            )
        }
        is SummonUiState.ActiveLobby -> LobbyContent(
            lobby = s.lobby,
            currentUser = currentUser,
            onCancel = { vm.cancelSummon(s.lobby.summon.id, currentUser) },
            onRebeacon = { vm.rebeacon(s.lobby.summon.id, currentUser.isAdmin) },
            onRsvp = { response, responseTime ->
                vm.submitRsvp(s.lobby.summon.id, currentUser.id, response, responseTime)
            },
        )
    }
    } // Box
    } // Scaffold
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SummonCreationContent(
    currentUser: User,
    config: Config,
    cooldownRemaining: Long,
    isCreating: Boolean,
    onSummon: (Instant) -> Unit,
) {
    var minutes by remember { mutableStateOf(30) }
    val selectedQuick = minutes.takeIf { it in QUICK_MINUTES }
    val inCooldown = cooldownRemaining > 0L

    Box(
        modifier = Modifier.fillMaxSize().padding(top = 48.dp, start = 24.dp, end = 24.dp, bottom = 24.dp),
    ) {
        val effectiveMinutes = minutes.coerceAtLeast(1)
        val gameInstant = Instant.now().plusSeconds(effectiveMinutes * 60L)

        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_dota),
                contentDescription = null,
                modifier = Modifier.size(180.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(12.dp))
            Text("Call a Game", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(32.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                QUICK_MINUTES.forEach { m ->
                    val label = if (m == 60) "1h" else "${m}m"
                    if (selectedQuick == m) {
                        Button(
                            onClick = { minutes = m },
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(vertical = 16.dp),
                        ) { Text(label, style = MaterialTheme.typography.titleMedium) }
                    } else {
                        OutlinedButton(
                            onClick = { minutes = m },
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(vertical = 16.dp),
                        ) { Text(label, style = MaterialTheme.typography.titleMedium) }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Slider(
                value = minutes.toFloat(),
                onValueChange = { minutes = kotlin.math.round(it).toInt().coerceIn(0, 60) },
                valueRange = 0f..60f,
                steps = 59,
                track = { TieredTrack(it) },
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Game at — ${timeFmt.format(gameInstant)}",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Column(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (inCooldown) {
                val mins = cooldownRemaining / 60
                val secs = cooldownRemaining % 60
                Text(
                    "Cooldown: %d:%02d".format(mins, secs),
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.height(8.dp))
            }
            Button(
                onClick = { onSummon(gameInstant) },
                modifier = Modifier.fillMaxWidth(),
                enabled = !inCooldown && !isCreating,
            ) {
                if (isCreating) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Text("Beacon!")
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "Please use this responsibly.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        }
    }
}

@Composable
private fun LobbyContent(
    lobby: LobbyState,
    currentUser: User,
    onCancel: () -> Unit,
    onRebeacon: () -> Unit,
    onRsvp: (String, Instant?) -> Unit,
) {
    val summon = lobby.summon
    val gameInstant = remember(summon.gameTime) {
        runCatching { Instant.parse(summon.gameTime) }.getOrNull()
    }
    val canCancel = currentUser.isAdmin || currentUser.id == summon.createdBy
    val myRsvp = lobby.rsvps.firstOrNull { it.userId == currentUser.id }

    val respondedUserIds = lobby.rsvps.map { it.userId }.toSet()
    val nonRespondents = lobby.allUsers.filter { it.id !in respondedUserIds }

    var showTimePickerForYesAt by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().padding(top = 48.dp, start = 16.dp, end = 16.dp, bottom = 16.dp)) {
        Text("Active Beacon", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(12.dp))
        Text(
            "Game at ${gameInstant?.let { timeFmt.format(it) } ?: "?"}",
            style = MaterialTheme.typography.headlineMedium,
        )
        lobby.summoner?.let {
            Text("Beaconed by ${it.displayName}", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        Spacer(Modifier.height(16.dp))
        if (currentUser.id != summon.createdBy) {
            val myResponse = myRsvp?.response
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(
                    Triple("yes", "Yes", { onRsvp("yes", null) }),
                    Triple("no", "No", { onRsvp("no", null) }),
                    Triple("yes_at_time", "Maybe", { showTimePickerForYesAt = true }),
                ).forEach { (response, label, action) ->
                    if (myResponse == response) {
                        Button(
                            onClick = if (response == "yes_at_time") action else ({}),
                            modifier = Modifier.weight(1f),
                        ) { Text(label) }
                    } else {
                        OutlinedButton(
                            onClick = action,
                            modifier = Modifier.weight(1f),
                        ) { Text(label) }
                    }
                }
            }
        }
        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (myRsvp != null) {
                item {
                    val responseLabel = myRsvp.response.replace("_", " ")
                    val timeLabel = myRsvp.responseTime?.let { t -> " (${timeFmt.format(Instant.parse(t))})" } ?: ""
                    Text(
                        "Your response: $responseLabel$timeLabel",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item {
                Spacer(Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                Text("Responses", style = MaterialTheme.typography.titleSmall)
            }
            items(lobby.rsvps, key = { it.userId }) { rsvp ->
                val user = lobby.allUsers.firstOrNull { it.id == rsvp.userId }
                RsvpRow(user, rsvp)
            }
            if (nonRespondents.isNotEmpty()) {
                item { Text("No response yet", style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(vertical = 8.dp)) }
                items(nonRespondents, key = { it.id }) { user ->
                    UserRow(user)
                }
            }
            if (canCancel) {
                item {
                    Spacer(Modifier.height(16.dp))
                    val rebeaconCooldown = lobby.rebeaconCooldownSeconds
                    val rebeaconExhausted = lobby.rebeaconUsedInWindow >= 5
                    val rebeaconLabel = when {
                        !currentUser.isAdmin && rebeaconExhausted -> "Re-beacon (limit reached)"
                        !currentUser.isAdmin && rebeaconCooldown > 0L -> "Re-beacon (${rebeaconCooldown}s)"
                        else -> "Re-beacon"
                    }
                    OutlinedButton(
                        onClick = onRebeacon,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = currentUser.isAdmin || (rebeaconCooldown <= 0L && !rebeaconExhausted),
                    ) { Text(rebeaconLabel) }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = onCancel,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    ) { Text("Cancel Beacon") }
                }
            }
        }
    }

    if (showTimePickerForYesAt) {
        YesAtTimePicker(
            gameInstant = gameInstant,
            onConfirm = { time -> onRsvp("yes_at_time", time); showTimePickerForYesAt = false },
            onDismiss = { showTimePickerForYesAt = false },
        )
    }
}

@Composable
private fun RsvpRow(user: User?, rsvp: Rsvp) {
    ListItem(
        headlineContent = { Text(user?.displayName ?: rsvp.userId) },
        supportingContent = {
            val label = when (rsvp.response) {
                "yes" -> "Yes"
                "no" -> "No"

                "yes_at_time" -> rsvp.responseTime?.let { "Maybe at ${timeFmt.format(Instant.parse(it))}" } ?: "Maybe"

                else -> rsvp.response
            }
            Text(label)
        },
        leadingContent = {
            AsyncImage(
                model = user?.avatarUrl,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
            )
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

@Composable
private fun UserRow(user: User) {
    ListItem(
        headlineContent = { Text(user.displayName) },
        leadingContent = {
            AsyncImage(
                model = user.avatarUrl,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
            )
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

@Composable
private fun YesAtTimePicker(
    gameInstant: Instant?,
    onConfirm: (Instant?) -> Unit,
    onDismiss: () -> Unit,
) {
    val offsetSteps = listOf(0, 5, 10, 15, 20, 25, 30, 35, 40, 45, 50, 55, 60)
    var sliderPos by remember { mutableStateOf(0) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Maybe") },
        text = {
            Column {
                when {
                    sliderPos == 0 -> Text("Maybe")
                    gameInstant != null -> {
                        val offsetMin = offsetSteps[sliderPos - 1]
                        val targetInstant = gameInstant.plusSeconds(offsetMin * 60L)
                        val suffix = if (offsetMin == 0) "" else " (+$offsetMin min)"
                        Text("Ready at ${timeFmt.format(targetInstant)}$suffix")
                    }
                    else -> Text("Maybe")
                }
                Spacer(Modifier.height(8.dp))
                if (gameInstant != null) {
                    val totalPositions = offsetSteps.size + 1
                    Slider(
                        value = sliderPos.toFloat(),
                        onValueChange = { sliderPos = it.toInt() },
                        valueRange = 0f..(totalPositions - 1).toFloat(),
                        steps = totalPositions - 2,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("Maybe", style = MaterialTheme.typography.labelSmall)
                        Text("+60 min", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val time = if (sliderPos == 0 || gameInstant == null) {
                    null
                } else {
                    gameInstant.plusSeconds(offsetSteps[sliderPos - 1] * 60L)
                }
                onConfirm(time)
            }) { Text("Confirm") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TieredTrack(sliderState: SliderState) {
    val activeColor = MaterialTheme.colorScheme.primary
    val inactiveColor = MaterialTheme.colorScheme.surfaceVariant
    val tickColor = MaterialTheme.colorScheme.onSurfaceVariant

    Canvas(
        modifier = Modifier.fillMaxWidth().height(24.dp),
    ) {
        val trackY = size.height / 2f
        val trackStroke = 4.dp.toPx()
        val leftPad = 8.dp.toPx()
        val rightPad = 8.dp.toPx()
        val usable = size.width - leftPad - rightPad

        val range = sliderState.valueRange
        val span = (range.endInclusive - range.start).coerceAtLeast(1f)
        fun xFor(m: Int) = leftPad + usable * ((m - range.start) / span)

        val thumbX = xFor(kotlin.math.round(sliderState.value).toInt())

        drawLine(
            color = activeColor,
            start = Offset(leftPad, trackY),
            end = Offset(thumbX, trackY),
            strokeWidth = trackStroke,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = inactiveColor,
            start = Offset(thumbX, trackY),
            end = Offset(size.width - rightPad, trackY),
            strokeWidth = trackStroke,
            cap = StrokeCap.Round,
        )

        val smallTick = 4.dp.toPx()
        val mediumTick = 7.dp.toPx()
        val largeTick = 10.dp.toPx()
        val tickWidth = 1.5.dp.toPx()

        for (m in 0..60) {
            val (halfHeight, alpha) = when {
                m == 0 || m == 30 || m == 60 -> largeTick / 2f to 1f
                m == 15 || m == 45 -> mediumTick / 2f to 0.85f
                m % 5 == 0 -> smallTick / 2f to 0.55f
                else -> continue
            }
            drawLine(
                color = tickColor.copy(alpha = alpha),
                start = Offset(xFor(m), trackY - halfHeight),
                end = Offset(xFor(m), trackY + halfHeight),
                strokeWidth = tickWidth,
                cap = StrokeCap.Round,
            )
        }
    }
}
