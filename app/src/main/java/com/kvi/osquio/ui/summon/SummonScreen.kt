package com.kvi.osquio.ui.summon

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.ui.res.painterResource
import com.kvi.osquio.R
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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

@Composable
fun SummonScreen(currentUser: User, vm: SummonViewModel = viewModel()) {
    val state by vm.state.collectAsState()

    LaunchedEffect(currentUser.id) { vm.load(currentUser) }

    when (val s = state) {
        is SummonUiState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        is SummonUiState.Error -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(s.message, color = MaterialTheme.colorScheme.error)
        }
        is SummonUiState.NoActiveSummon -> SummonCreationContent(
            currentUser = currentUser,
            config = s.config,
            cooldownRemaining = s.cooldownRemaining,
            onSummon = { gameTime -> vm.createSummon(currentUser, gameTime) },
        )
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
}

@Composable
private fun SummonCreationContent(
    currentUser: User,
    config: Config,
    cooldownRemaining: Long,
    onSummon: (Instant) -> Unit,
) {
    var selectedMinutes by remember { mutableStateOf(30) }
    val inCooldown = cooldownRemaining > 0L

    Box(
        modifier = Modifier.fillMaxSize().padding(top = 48.dp, start = 24.dp, end = 24.dp, bottom = 24.dp),
    ) {
        val gameInstant = Instant.now().plusSeconds(selectedMinutes * 60L)

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
                listOf(15 to "in 15 mins", 30 to "in 30 mins", 60 to "in 1 hour").forEach { (mins, label) ->
                    if (selectedMinutes == mins) {
                        Button(
                            onClick = { selectedMinutes = mins },
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(vertical = 16.dp),
                        ) { Text(label, style = MaterialTheme.typography.titleMedium) }
                    } else {
                        OutlinedButton(
                            onClick = { selectedMinutes = mins },
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(vertical = 16.dp),
                        ) { Text(label, style = MaterialTheme.typography.titleMedium) }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
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
                enabled = !inCooldown,
            ) {
                Text("Beacon!")
            }
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
                    Triple("yes_at_time", "Yes at...", { showTimePickerForYesAt = true }),
                ).forEach { (response, label, action) ->
                    if (myResponse == response) {
                        Button(
                            onClick = {},
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
        if (myRsvp != null) {
            val responseLabel = myRsvp.response.replace("_", " ")
            val timeLabel = myRsvp.responseTime?.let { t -> " (${timeFmt.format(Instant.parse(t))})" } ?: ""
            Text(
                "Your response: $responseLabel$timeLabel",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(Modifier.height(8.dp))
        Text("Responses", style = MaterialTheme.typography.titleSmall)

        LazyColumn(modifier = Modifier.weight(1f)) {
            items(lobby.rsvps) { rsvp ->
                val user = lobby.allUsers.firstOrNull { it.id == rsvp.userId }
                RsvpRow(user, rsvp)
            }
            if (nonRespondents.isNotEmpty()) {
                item { Text("No response yet", style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(vertical = 8.dp)) }
                items(nonRespondents) { user ->
                    UserRow(user)
                }
            }
        }

        if (canCancel) {
            Spacer(Modifier.height(8.dp))
            val rebeaconCooldown = lobby.rebeaconCooldownSeconds
            val rebeaconExhausted = lobby.rebeaconUsedInWindow >= 5
            val rebeaconLabel = when {
                rebeaconExhausted -> "Re-beacon (limit reached)"
                rebeaconCooldown > 0L -> "Re-beacon (${rebeaconCooldown}s)"
                else -> "Re-beacon"
            }
            OutlinedButton(
                onClick = onRebeacon,
                modifier = Modifier.fillMaxWidth(),
                enabled = rebeaconCooldown <= 0L && !rebeaconExhausted,
            ) { Text(rebeaconLabel) }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { Text("Cancel Beacon") }
        }
    }

    if (showTimePickerForYesAt && gameInstant != null) {
        YesAtTimePicker(
            gameTime = gameInstant,
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
                "yes_at_time" -> "Yes at ${rsvp.responseTime?.let { timeFmt.format(Instant.parse(it)) } ?: "?"}"
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
        }
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
        }
    )
}

@Composable
private fun YesAtTimePicker(
    gameTime: Instant,
    onConfirm: (Instant) -> Unit,
    onDismiss: () -> Unit,
) {
    val now = Instant.now()
    val maxMinutesFromNow = ((gameTime.epochSecond - now.epochSecond) / 60).toInt().coerceAtLeast(0)
    var selectedOffset by remember { mutableStateOf(0) }

    val quickOptions = listOf(15, 30, 60).filter { it <= maxMinutesFromNow }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Yes at what time?") },
        text = {
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    quickOptions.forEach { mins ->
                        FilterChip(
                            selected = selectedOffset == mins,
                            onClick = { selectedOffset = mins },
                            label = { Text("+$mins min") },
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                val targetInstant = now.plusSeconds(selectedOffset * 60L)
                Text("I'll be ready at ${timeFmt.format(targetInstant)}")
                Slider(
                    value = selectedOffset.toFloat(),
                    onValueChange = { selectedOffset = (it / 5).toInt() * 5 },
                    valueRange = 0f..maxMinutesFromNow.toFloat(),
                    steps = (maxMinutesFromNow / 5).coerceAtLeast(1),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(now.plusSeconds(selectedOffset * 60L)) }) { Text("Confirm") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
