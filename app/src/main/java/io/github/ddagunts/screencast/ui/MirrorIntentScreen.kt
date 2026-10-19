package io.github.ddagunts.screencast.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun MirrorIntentScreen(vm: MirrorIntentViewModel, devicesVm: WebRtcViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val devices by devicesVm.discovered.collectAsStateWithLifecycle()
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Mirror your screen", style = MaterialTheme.typography.titleMedium)
        Text("Choose a TV. The app picks a connection method and tries another if it fails.",
            style = MaterialTheme.typography.bodySmall)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(state.message)
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (state.busy) Button(onClick = vm::stop) {
                    Text(if (state.casting) "Stop mirroring" else "Cancel")
                }
            }
        }
        if (devices.isEmpty()) Text("Looking for TVs on this network…")
        for (device in devices) {
            Card(Modifier.fillMaxWidth().clickable(enabled = !state.busy) { vm.start(device) }) {
                Row(Modifier.padding(16.dp)) {
                    Column {
                        Text(device.name)
                        Text(device.host, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        ManualHostRow("Connect by IP", devicesVm::addManualHost)
        Text("Android asks to share your screen for each connection attempt, including a retry.",
            style = MaterialTheme.typography.bodySmall)
    }
}
