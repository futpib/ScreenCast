package io.github.ddagunts.screencast.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.compose.material3.Switch
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Power
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.ddagunts.screencast.androidtv.AndroidTvDevice
import io.github.ddagunts.screencast.androidtv.AndroidTvKey
import io.github.ddagunts.screencast.androidtv.AndroidTvState
import io.github.ddagunts.screencast.androidtv.AndroidTvVolume

// Top-level body for the remote-control screen. Hosts:
//   * a discovered + paired device picker (when no device is selected),
//   * the pairing dialog (when a pair() is in flight),
//   * the actual remote (D-pad / nav / volume / media) when a device is
//     selected.
@Composable
fun AndroidTvRemoteScreen(vm: AndroidTvViewModel) {
    val notificationEnabled by vm.notificationEnabled.collectAsStateWithLifecycle()
    val notificationError by vm.notificationError.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.setNotificationEnabled(true) else vm.notificationPermissionDenied()
    }
    LifecycleResumeEffect(Unit) {
        vm.resumeCurrent()
        onPauseOrDispose { }
    }
    val discovered by vm.discovered.collectAsStateWithLifecycle()
    val manualHosts by vm.manualHosts.collectAsStateWithLifecycle()
    val paired by vm.paired.collectAsStateWithLifecycle()
    val currentHost by vm.currentHost.collectAsStateWithLifecycle()
    val state by vm.currentState.collectAsStateWithLifecycle()
    val volume by vm.currentVolume.collectAsStateWithLifecycle()
    val prompt by vm.pairingPrompt.collectAsStateWithLifecycle()
    val imePrompt by vm.currentImePrompt.collectAsStateWithLifecycle()

    if (currentHost == null) {
        DevicePicker(
            discovered = discovered,
            paired = paired,
            manualHosts = manualHosts,
            onPair = { vm.startPairing(it) },
            onSelect = { vm.selectDevice(it) },
            onForget = { vm.unpair(it) },
            onAddManual = { vm.addManualHost(it) },
            onRemoveManual = { vm.removeManualHost(it) },
        )
    } else {
        val device = vm.selectedDevice() ?: discovered.firstOrNull { it.host == currentHost } ?: paired
            .firstOrNull { it.host == currentHost }
            ?.let {
                AndroidTvDevice(
                    name = it.name, host = it.host, modelName = it.model, bleMac = it.bleMac,
                )
            } ?: return Text("Device not found")
        RemoteScreenBody(
            device = device,
            state = state,
            volume = volume,
            notificationEnabled = notificationEnabled,
            notificationError = notificationError,
            onNotificationChange = { enabled ->
                if (!enabled) vm.setNotificationEnabled(false)
                else if (NotificationManagerCompat.from(context).areNotificationsEnabled()) vm.setNotificationEnabled(true)
                else if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                else vm.notificationPermissionDenied()
            },
            onNotificationSettings = {
                context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
            },
            onLeave = { vm.leaveCurrent() },
            onKey = { vm.sendKey(it) },
            onKeyDown = { vm.keyDown(it) },
            onKeyUp = { vm.keyUp(it) },
            onSetVolume = { vm.setVolume(it) },
            onConnect = { vm.connectCurrent() },
            onLongPress = { key -> vm.longPress(key) },
            onOpenKeyboard = { vm.openImePrompt() },
        )
    }

    prompt?.let {
        AndroidTvPairingDialog(
            deviceName = it.device.name,
            onSubmit = { code -> vm.submitPairingCode(code) },
            onDismiss = { vm.cancelPairing() },
        )
    }

    imePrompt?.let { p ->
        AndroidTvImeSheet(
            prompt = p,
            onTextChange = { vm.sendImeText(it) },
            onBackspace = { vm.sendImeBackspace() },
            onEnter = { vm.sendImeEnter() },
            onDismiss = { vm.closeImePrompt() },
        )
    }
}

@Composable
private fun DevicePicker(
    discovered: List<AndroidTvDevice>,
    paired: List<io.github.ddagunts.screencast.androidtv.AndroidTvPersistence.Entry>,
    manualHosts: Set<String>,
    onPair: (AndroidTvDevice) -> Unit,
    onSelect: (AndroidTvDevice) -> Unit,
    onForget: (String) -> Unit,
    onAddManual: (String) -> Unit,
    onRemoveManual: (String) -> Unit,
) {
    val pairedHosts = paired.map { it.host }.toSet()
    val pairedDiscovered = discovered.filter { it.host in pairedHosts }
    val newDiscovered = discovered.filterNot { it.host in pairedHosts }
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (pairedDiscovered.isNotEmpty()) {
            Text("Paired", style = MaterialTheme.typography.titleSmall)
            Card(Modifier.fillMaxWidth()) {
                pairedDiscovered.forEach { d ->
                    ListItem(
                        headlineContent = { Text(d.name) },
                        supportingContent = { Text(d.host) },
                        trailingContent = {
                            TextButton(onClick = { onForget(d.host) }) { Text("Forget") }
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier
                            .fillMaxWidth()
                            .pointerInput(d.host) {
                                detectTapGestures(onTap = { onSelect(d) })
                            },
                    )
                }
            }
        }
        if (newDiscovered.isNotEmpty()) {
            Text("Discovered", style = MaterialTheme.typography.titleSmall)
            Card(Modifier.fillMaxWidth()) {
                newDiscovered.forEach { d ->
                    val isManual = d.host in manualHosts
                    ListItem(
                        headlineContent = { Text(d.name) },
                        supportingContent = { Text(d.host) },
                        trailingContent = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TextButton(onClick = { onPair(d) }) { Text("Pair") }
                                if (isManual) {
                                    IconButton(onClick = { onRemoveManual(d.host) }) {
                                        Icon(Icons.Filled.Close, contentDescription = "Remove ${d.host}")
                                    }
                                }
                            }
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                }
            }
        }
        // mDNS can lag behind Wi-Fi resume. Allow connecting by the saved IP.
        val orphanedPaired = paired.filterNot { it.host in discovered.map { d -> d.host } }
        if (orphanedPaired.isNotEmpty()) {
            Text("Saved TVs", style = MaterialTheme.typography.titleSmall)
            Card(Modifier.fillMaxWidth()) {
                orphanedPaired.forEach { e ->
                    ListItem(
                        modifier = Modifier.pointerInput(e.host) {
                            detectTapGestures(onTap = {
                                onSelect(AndroidTvDevice(e.name, e.host, modelName = e.model, bleMac = e.bleMac))
                            })
                        },
                        headlineContent = { Text(e.name) },
                        supportingContent = { Text(e.host) },
                        trailingContent = {
                            TextButton(onClick = { onForget(e.host) }) { Text("Forget") }
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                }
            }
        }
        if (discovered.isEmpty() && paired.isEmpty()) {
            Text(
                "No Android TVs found yet. Make sure the TV is on and on the same Wi-Fi.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        ManualHostRow(
            label = "Connect by IP",
            onAdd = onAddManual,
        )
    }
}

@Composable
private fun RemoteScreenBody(
    device: AndroidTvDevice,
    state: AndroidTvState,
    volume: AndroidTvVolume,
    notificationEnabled: Boolean,
    notificationError: String?,
    onNotificationChange: (Boolean) -> Unit,
    onNotificationSettings: () -> Unit,
    onLeave: () -> Unit,
    onKey: (AndroidTvKey) -> Unit,
    onKeyDown: (AndroidTvKey) -> Unit,
    onKeyUp: (AndroidTvKey) -> Unit,
    onSetVolume: (Float) -> Unit,
    onConnect: () -> Unit,
    onLongPress: (AndroidTvKey) -> Unit,
    onOpenKeyboard: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Header(device = device, state = state, onLeave = onLeave, onConnect = onConnect)

        Card(Modifier.fillMaxWidth()) {
            ListItem(
                headlineContent = { Text("Remote notification") },
                supportingContent = { Text("Keep connected and play/pause without opening ScreenCast") },
                trailingContent = { Switch(checked = notificationEnabled, onCheckedChange = onNotificationChange) },
            )
            notificationError?.let {
                Text(it, modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error)
                TextButton(onClick = onNotificationSettings) { Text("Notification settings") }
            }
        }

        DPad(onKey = onKey)

        NavRow(onKey = onKey, onLongPress = onLongPress, onOpenKeyboard = onOpenKeyboard)

        VolumeRow(volume = volume, onKey = onKey)

        MediaRow(onKey = onKey)
    }
}

@Composable
private fun Header(
    device: AndroidTvDevice,
    state: AndroidTvState,
    onLeave: () -> Unit,
    onConnect: () -> Unit,
) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onLeave) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to picker")
            }
            Column(Modifier.weight(1f)) {
                Text(device.name, style = MaterialTheme.typography.titleMedium)
                Text(device.host, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (state is AndroidTvState.Error) Text(state.message,
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            StatusChip(state = state)
            if (state is AndroidTvState.Idle || state is AndroidTvState.Error) {
                TextButton(onClick = onConnect) { Text("Connect") }
            }
        }
    }
}

@Composable
private fun StatusChip(state: AndroidTvState) {
    val (label, color) = when (state) {
        is AndroidTvState.Idle -> "Idle" to MaterialTheme.colorScheme.outline
        is AndroidTvState.Connecting -> "Connecting" to MaterialTheme.colorScheme.tertiary
        is AndroidTvState.Active -> "Connected" to MaterialTheme.colorScheme.primary
        is AndroidTvState.Reconnecting -> "Reconnecting" to MaterialTheme.colorScheme.tertiary
        is AndroidTvState.Error -> "Error" to MaterialTheme.colorScheme.error
    }
    AssistChip(
        onClick = {}, enabled = false,
        leadingIcon = {
            Box(Modifier.size(10.dp).background(color, CircleShape))
        },
        label = { Text(label) },
        colors = AssistChipDefaults.assistChipColors(
            disabledLabelColor = MaterialTheme.colorScheme.onSurface,
        ),
    )
}

@Composable
private fun DPad(onKey: (AndroidTvKey) -> Unit) {
    // One SHORT key per tap. We started with a press-and-hold detector
    // (START_LONG on press, END_LONG on release, plus SHORT on tap), but
    // Compose's detectTapGestures fires all three in sequence for one
    // discrete tap — and the TV interprets `down → short → up` for the
    // same keycode as a malformed event sequence and closes the socket.
    // Until the press/hold UX gets a real long-press detector with a
    // threshold, plain SHORT-on-click is the safe choice.
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            DPadButton(Icons.Filled.KeyboardArrowUp, "Up", AndroidTvKey.DPadUp, onKey)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                DPadButton(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Left", AndroidTvKey.DPadLeft, onKey)
                FilledTonalIconButton(
                    onClick = { onKey(AndroidTvKey.DPadCenter) },
                    modifier = Modifier.size(72.dp),
                    shape = CircleShape,
                ) {
                    Text("OK", style = MaterialTheme.typography.titleMedium)
                }
                DPadButton(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Right", AndroidTvKey.DPadRight, onKey)
            }
            DPadButton(Icons.Filled.KeyboardArrowDown, "Down", AndroidTvKey.DPadDown, onKey)
        }
    }
}

@Composable
private fun DPadButton(
    icon: ImageVector,
    description: String,
    key: AndroidTvKey,
    onTap: (AndroidTvKey) -> Unit,
) {
    FilledTonalIconButton(
        onClick = { onTap(key) },
        modifier = Modifier.size(56.dp),
        shape = CircleShape,
    ) {
        Icon(icon, contentDescription = description)
    }
}

@Composable
private fun NavRow(
    onKey: (AndroidTvKey) -> Unit,
    onLongPress: (AndroidTvKey) -> Unit,
    onOpenKeyboard: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        SmallKeyButton(Icons.AutoMirrored.Filled.ArrowBack, "Back", AndroidTvKey.Back, onKey)
        SmallKeyButton(Icons.Filled.Home, "Home", AndroidTvKey.Home, onKey)
        SmallKeyButton(Icons.Filled.Menu, "Menu", AndroidTvKey.Menu, onKey)
        // KEYCODE_SETTINGS (176) goes unbound on Sony BRAVIA firmware,
        // and the Cast AppLinkLaunchRequest path expects HTTPS deep
        // links (an `intent:#Intent;...;end` URI got us a RemoteError +
        // socket close). Long-pressing HOME opens the system action
        // menu / quick settings on every Android TV / Google TV — same
        // gesture the physical Sony remote uses.
        FilledTonalIconButton(onClick = { onLongPress(AndroidTvKey.Home) }) {
            Icon(Icons.Filled.Settings, contentDescription = "Settings (long-press Home)")
        }
        // Manual keyboard trigger. The sheet also auto-opens when the TV
        // pushes ImeKeyInject/ImeShowRequest, but firmwares sometimes
        // drop those (especially on first focus into a field) — this
        // button lets the user force-open against whatever field is
        // already focused.
        FilledTonalIconButton(onClick = onOpenKeyboard) {
            Icon(Icons.Filled.Keyboard, contentDescription = "Show keyboard")
        }
        SmallKeyButton(Icons.Filled.Power, "Power", AndroidTvKey.Power, onKey)
    }
}

@Composable
private fun VolumeRow(volume: AndroidTvVolume, onKey: (AndroidTvKey) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (volume.muted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.size(8.dp))
            // On HDMI-ARC the TV delegates output to the AVR and stops
            // pushing SetVolumeLevel entirely (verified on the wire — both
            // level and max stay 0 across volume key presses). The Android
            // TV Remote protocol has no query RPC, so there's no way to
            // read the AVR's level. Drop the numbers in that case rather
            // than show a misleading "0".
            val label = if (volume.max > 0) {
                "Volume ${volume.level}/${volume.max}" + if (volume.muted) " (muted)" else ""
            } else {
                "Volume" + if (volume.muted) " (muted)" else ""
            }
            Text(label, modifier = Modifier.weight(1f))
            FilledTonalIconButton(onClick = { onKey(AndroidTvKey.VolumeDown) }) {
                Icon(Icons.AutoMirrored.Filled.VolumeDown, contentDescription = "Volume down")
            }
            Spacer(Modifier.size(4.dp))
            FilledTonalIconButton(onClick = { onKey(AndroidTvKey.Mute) }) {
                Icon(Icons.AutoMirrored.Filled.VolumeOff, contentDescription = "Mute toggle")
            }
            Spacer(Modifier.size(4.dp))
            FilledTonalIconButton(onClick = { onKey(AndroidTvKey.VolumeUp) }) {
                Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = "Volume up")
            }
        }
    }
}

@Composable
private fun MediaRow(onKey: (AndroidTvKey) -> Unit) {
    // Six transport buttons in physical-remote order: scrub-rewind,
    // skip-prev, play/pause, stop, skip-next, scrub-forward. Earlier
    // version had a duplicate "Stop" entry (Pause icon mislabeled) and
    // used SpaceEvenly which jammed the buttons against the card edges.
    // Modifier.weight on each child plus a centered SpaceEvenly gives an
    // even distribution that fills the card width regardless of screen.
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(horizontal = 8.dp, vertical = 12.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SmallKeyButton(Icons.Filled.FastRewind, "Rewind", AndroidTvKey.MediaRewind, onKey)
            SmallKeyButton(Icons.Filled.SkipPrevious, "Previous", AndroidTvKey.MediaPrevious, onKey)
            SmallKeyButton(Icons.Filled.PlayArrow, "Play / pause", AndroidTvKey.MediaPlayPause, onKey)
            SmallKeyButton(Icons.Filled.Stop, "Stop", AndroidTvKey.MediaStop, onKey)
            SmallKeyButton(Icons.Filled.SkipNext, "Next", AndroidTvKey.MediaNext, onKey)
            SmallKeyButton(Icons.Filled.FastForward, "Fast forward", AndroidTvKey.MediaFastForward, onKey)
        }
    }
}

@Composable
private fun SmallKeyButton(
    icon: ImageVector,
    description: String,
    key: AndroidTvKey,
    onKey: (AndroidTvKey) -> Unit,
) {
    FilledTonalIconButton(onClick = { onKey(key) }) {
        Icon(icon, contentDescription = description)
    }
}
