package io.github.ddagunts.screencast.ui

import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.ddagunts.screencast.CastMode
import io.github.ddagunts.screencast.media.PhoneMediaVolume

class MainActivity : ComponentActivity() {

    private val vm: CastViewModel by viewModels()
    private val webRtcVm: WebRtcViewModel by viewModels()
    private val atvVm: AndroidTvViewModel by viewModels()
    private val nativeVm: NativeCastViewModel by viewModels()
    private val mediaVm: MediaPushViewModel by viewModels()
    private val mirrorVm: MirrorIntentViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PhoneMediaVolume.restoreStale(this)
        mediaVm.acceptSharedIntent(intent)
        setContent {
            ScreenCastTheme {
                Surface(Modifier.fillMaxSize()) { AppScaffold(vm, webRtcVm, atvVm, nativeVm, mediaVm, mirrorVm) }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        mediaVm.acceptSharedIntent(intent)
    }
}

// Dynamic color pulls the system's Material You palette on API 31+ so the
// app mirrors the user's wallpaper/accent. Pre-31 devices fall back to the
// stock dark scheme; we don't ship a bespoke palette.
@Composable
private fun ScreenCastTheme(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    // LocalConfiguration (not ctx.resources.configuration) so recomposition
    // fires when the user toggles dark mode without restarting the Activity.
    val uiMode = LocalConfiguration.current.uiMode
    val lightSystemBars = uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_NO
    SideEffect {
        (ctx as? Activity)?.window?.let { window ->
            WindowCompat.getInsetsController(window, window.decorView).apply {
                isAppearanceLightStatusBars = lightSystemBars
                isAppearanceLightNavigationBars = lightSystemBars
            }
        }
    }
    val colors = when {
        Build.VERSION.SDK_INT >= 31 &&
            lightSystemBars ->
                dynamicLightColorScheme(ctx)
        Build.VERSION.SDK_INT >= 31 -> dynamicDarkColorScheme(ctx)
        else -> darkColorScheme()
    }
    MaterialTheme(colorScheme = colors, content = content)
}

private enum class Screen { Home, Mirror, Audio, MultiMirror, Media, Remote, Advanced, Settings, Logs }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppScaffold(
    vm: CastViewModel,
    webRtcVm: WebRtcViewModel,
    atvVm: AndroidTvViewModel,
    nativeVm: NativeCastViewModel,
    mediaVm: MediaPushViewModel,
    mirrorVm: MirrorIntentViewModel,
) {
    var screen by rememberSaveable { mutableStateOf(Screen.Home) }
    var openedShareId by rememberSaveable { mutableStateOf(0L) }
    val shared by mediaVm.shared.collectAsStateWithLifecycle()
    LaunchedEffect(shared?.id) {
        shared?.let {
            if (it.id != openedShareId) {
                screen = Screen.Media
                openedShareId = it.id
            }
        }
    }
    BackHandler(enabled = screen != Screen.Home) { screen = Screen.Home }
    val castMode by vm.castMode.collectAsStateWithLifecycle()
    Scaffold(topBar = {
        TopAppBar(
            title = {
                Text(when (screen) {
                    Screen.Home -> "ScreenCast"
                    Screen.Mirror -> "Mirror screen"
                    Screen.Audio -> "Cast phone audio"
                    Screen.MultiMirror -> "Mirror to several TVs"
                    Screen.Media -> "Play media"
                    Screen.Remote -> "TV remote"
                    Screen.Advanced -> "Advanced modes"
                    Screen.Settings -> "Settings"
                    Screen.Logs -> "Logs"
                })
            },
            navigationIcon = {
                if (screen != Screen.Home) {
                    IconButton(onClick = { screen = Screen.Home }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            },
            actions = {
                if (screen == Screen.Home) CastScreenActions(
                    onOpenSettings = { screen = Screen.Settings },
                    onOpenLogs = { screen = Screen.Logs },
                    onOpenAdvanced = { screen = Screen.Advanced },
                )
            },
        )
    }) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            when (screen) {
                Screen.Home -> IntentHomeScreen(
                    onMirror = { screen = Screen.Mirror },
                    onAudio = { screen = Screen.Audio },
                    onMultiMirror = { screen = Screen.MultiMirror },
                    onMedia = { screen = Screen.Media },
                    onRemote = { screen = Screen.Remote },
                )
                Screen.Mirror -> MirrorIntentScreen(mirrorVm, webRtcVm)
                Screen.Audio -> NativeCastBody(nativeVm, webRtcVm, audioOnly = true)
                Screen.MultiMirror -> CastControlScreen(vm)
                Screen.Media -> MediaPushScreen(mediaVm, vm)
                Screen.Remote -> AndroidTvRemoteScreen(atvVm)
                Screen.Advanced -> CastScreenRoot(
                    castMode = castMode,
                    onModeChange = { vm.setCastMode(it) },
                    vm = vm,
                    webRtcVm = webRtcVm,
                    atvVm = atvVm,
                    nativeVm = nativeVm,
                    mediaVm = mediaVm,
                )
                Screen.Settings -> SettingsScreen(vm, webRtcVm)
                Screen.Logs -> LogPanelScreen()
            }
        }
    }
}

@Composable
private fun IntentHomeScreen(
    onMirror: () -> Unit,
    onAudio: () -> Unit,
    onMultiMirror: () -> Unit,
    onMedia: () -> Unit,
    onRemote: () -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        IntentCard("Mirror my screen", "Show this phone on one TV.", onMirror)
        IntentCard("Cast phone audio", "Play capturable phone audio on a Cast speaker or TV.", onAudio)
        IntentCard("Play a file or link", "Send a photo, video, or audio file to a TV.", onMedia)
        IntentCard("Control my TV", "Use your phone as an Android TV remote.", onRemote)
        IntentCard("Mirror to several TVs", "Show the same screen on multiple TVs.", onMultiMirror)
    }
}

@Composable
private fun IntentCard(title: String, description: String, onClick: () -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(description, style = MaterialTheme.typography.bodySmall)
        }
    }
}

// Direct transport selection for users who need to choose a specific method.
// The ViewModels remain alive when this screen is hidden, preserving discovery.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CastScreenRoot(
    castMode: CastMode,
    onModeChange: (CastMode) -> Unit,
    vm: CastViewModel,
    webRtcVm: WebRtcViewModel,
    atvVm: AndroidTvViewModel,
    nativeVm: NativeCastViewModel,
    mediaVm: MediaPushViewModel,
) {
    Column(Modifier.fillMaxSize()) {
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            val modes = CastMode.values()
            modes.forEachIndexed { index, mode ->
                SegmentedButton(
                    selected = castMode == mode,
                    onClick = { onModeChange(mode) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = modes.size),
                ) {
                    Text(when (mode) {
                        CastMode.HLS -> "HLS"
                        CastMode.WEBRTC -> "WebRTC"
                        CastMode.REMOTE -> "Remote"
                        CastMode.NATIVE -> "Native"
                        CastMode.MEDIA -> "Media"
                    })
                }
            }
        }
        when (castMode) {
            CastMode.HLS -> CastControlScreen(vm)
            CastMode.WEBRTC -> WebRtcCastBody(webRtcVm)
            CastMode.REMOTE -> AndroidTvRemoteScreen(atvVm)
            CastMode.NATIVE -> NativeCastBody(nativeVm, webRtcVm)
            CastMode.MEDIA -> MediaPushScreen(mediaVm, vm, advanced = true)
        }
    }
}

@Composable
private fun CastScreenActions(
    onOpenSettings: () -> Unit,
    onOpenLogs: () -> Unit,
    onOpenAdvanced: () -> Unit,
) {
    IconButton(onClick = onOpenSettings) {
        Icon(Icons.Filled.Settings, contentDescription = "Settings")
    }
    var menuOpen by remember { mutableStateOf(false) }
    IconButton(onClick = { menuOpen = true }) {
        Icon(Icons.Filled.MoreVert, contentDescription = "More")
    }
    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
        DropdownMenuItem(
            text = { Text("Advanced modes") },
            onClick = { menuOpen = false; onOpenAdvanced() },
        )
        DropdownMenuItem(
            text = { Text("Logs") },
            onClick = { menuOpen = false; onOpenLogs() },
        )
    }
}
