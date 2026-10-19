package io.github.ddagunts.screencast.ui

import android.content.Intent
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun MediaPushScreen(vm: MediaPushViewModel, castVm: CastViewModel, advanced: Boolean = false) {
    val context = LocalContext.current
    val castDevices by castVm.discovered.collectAsStateWithLifecycle()
    val dlnaDevices by vm.devices.collectAsStateWithLifecycle()
    val session by vm.session.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val serviceError by vm.serviceError.collectAsStateWithLifecycle()
    var fileUri by remember { mutableStateOf<String?>(null) }
    var fileName by remember { mutableStateOf<String?>(null) }
    var url by remember { mutableStateOf("") }
    var mime by remember { mutableStateOf("") }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION)
            fileUri = uri.toString()
            fileName = context.contentResolver.query(uri,
                arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
            url = ""
        }
    }
    val source = fileUri ?: url.trim()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Play media on a TV", style = MaterialTheme.typography.titleMedium)
        Text("The TV fetches the media directly. Choose a file on this phone or enter a direct media URL.",
            style = MaterialTheme.typography.bodySmall)
        Button(onClick = { picker.launch(arrayOf("video/*", "image/*", "audio/*")) }) {
            Text("Choose photo, video, or audio")
        }
        if (fileUri != null) {
            Text("Selected file: ${fileName ?: "Media"}")
            Button(onClick = { fileUri = null; fileName = null }) { Text("Clear file") }
        }
        OutlinedTextField(value = url, onValueChange = {
            url = it
            if (it.isNotBlank()) { fileUri = null; fileName = null }
        },
            label = { Text("Direct media URL") }, singleLine = true,
            modifier = Modifier.fillMaxWidth())
        if (advanced) OutlinedTextField(value = mime, onValueChange = { mime = it },
            label = { Text("URL MIME type (optional)") }, singleLine = true,
            modifier = Modifier.fillMaxWidth())
        Text("Formats depend on the TV receiver. Web page URLs are not media URLs.",
            style = MaterialTheme.typography.bodySmall)

        session?.let { current ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (advanced) "${current.protocol} → ${current.target}"
                        else "Playing on ${current.target}",
                        style = MaterialTheme.typography.titleSmall)
                    Text(current.title)
                    Text(current.status)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = vm::pause) { Text("Pause") }
                        Button(onClick = vm::play) { Text("Play") }
                        Button(onClick = vm::stop) { Text("Stop") }
                    }
                }
            }
        }
        if (error != null) Text(error!!, color = MaterialTheme.colorScheme.error)
        if (serviceError != null) Text(serviceError!!, color = MaterialTheme.colorScheme.error)

        if (!advanced) {
            Text("Available TVs", style = MaterialTheme.typography.titleSmall)
            val targets = (castDevices.map { it.host } + dlnaDevices.map { it.host }).distinct()
            if (targets.isEmpty()) Text("Looking for TVs on this network…")
            for (host in targets) {
                val castDevice = castDevices.firstOrNull { it.host == host }
                val dlnaDevice = dlnaDevices.firstOrNull { it.host == host }
                Card(Modifier.fillMaxWidth().clickable(enabled = session == null && source.isNotBlank()) {
                    vm.startAutomatic(castDevice, dlnaDevice, source, fileUri != null, mime)
                }) {
                    Column(Modifier.padding(16.dp)) {
                        Text(castDevice?.name ?: dlnaDevice?.name ?: host)
                        Text(host, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            ManualHostRow("Connect by IP", castVm::addManualHost)
            Button(onClick = vm::scan) { Text("Scan for TVs") }
        } else {
            Text("Google Cast", style = MaterialTheme.typography.titleSmall)
            for (device in castDevices) {
                Card(Modifier.fillMaxWidth().clickable(enabled = session == null && source.isNotBlank()) {
                    vm.startCast(device, source, fileUri != null, mime)
                }) {
                    Column(Modifier.padding(16.dp)) {
                        Text(device.name)
                        Text(device.host, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            ManualHostRow("Cast IP address", castVm::addManualHost)

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("DLNA / UPnP", style = MaterialTheme.typography.titleSmall)
                Button(onClick = vm::scan) { Text("Scan") }
            }
            if (dlnaDevices.isEmpty()) Text("No DLNA renderer found on this network")
            for (device in dlnaDevices) {
                Card(Modifier.fillMaxWidth().clickable(enabled = session == null && source.isNotBlank()) {
                    vm.startDlna(device, source, fileUri != null, mime)
                }) {
                    Column(Modifier.padding(16.dp)) {
                        Text(device.name)
                        Text(device.host, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            ManualHostRow("DLNA device description URL", vm::addDescription)
        }
    }
}
