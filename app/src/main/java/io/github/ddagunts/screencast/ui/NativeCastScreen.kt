package io.github.ddagunts.screencast.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun NativeCastBody(nativeVm: NativeCastViewModel, devicesVm: WebRtcViewModel) {
    val devices by devicesVm.discovered.collectAsStateWithLifecycle()
    val manualHosts by devicesVm.manualHosts.collectAsStateWithLifecycle()
    val session by nativeVm.session.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
        .padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Native Cast mirroring", style = MaterialTheme.typography.titleMedium)
                Text("Streams the screen directly to a stock Cast receiver over UDP. Video only for now.",
                    style = MaterialTheme.typography.bodySmall)
                session?.let { current ->
                    Text("${current.device.name}: ${current.status}")
                    Button(onClick = nativeVm::stop) { Text("Stop") }
                }
            }
        }
        Text("Available Chromecasts", style = MaterialTheme.typography.titleSmall)
        if (devices.isEmpty()) Text("Discovering on LAN…")
        for (device in devices) {
            Card(Modifier.fillMaxWidth().clickable(enabled = session == null) { nativeVm.start(device) }) {
                Row(Modifier.padding(16.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(device.name)
                        Text(device.host, style = MaterialTheme.typography.bodySmall)
                    }
                    if (device.host in manualHosts) {
                        IconButton(onClick = { devicesVm.removeManualHost(device.host) }) {
                            Icon(Icons.Filled.Close, contentDescription = "Remove ${device.host}")
                        }
                    }
                }
            }
        }
        ManualHostRow(label = "Connect by IP", onAdd = devicesVm::addManualHost)
    }
}
