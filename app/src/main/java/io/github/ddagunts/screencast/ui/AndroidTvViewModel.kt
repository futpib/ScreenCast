package io.github.ddagunts.screencast.ui

import android.app.Application
import io.github.ddagunts.screencast.AndroidTvRemoteService
import io.github.ddagunts.screencast.androidtv.AndroidTvRemotes
import io.github.ddagunts.screencast.androidtv.AndroidTvRemotePreferences
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.ddagunts.screencast.androidtv.AndroidTvDevice
import io.github.ddagunts.screencast.androidtv.AndroidTvDiscovery
import io.github.ddagunts.screencast.androidtv.AndroidTvImePrompt
import io.github.ddagunts.screencast.androidtv.AndroidTvKey
import io.github.ddagunts.screencast.androidtv.AndroidTvPersistence
import io.github.ddagunts.screencast.androidtv.AndroidTvRemote
import io.github.ddagunts.screencast.androidtv.AndroidTvState
import io.github.ddagunts.screencast.androidtv.AndroidTvVolume
import io.github.ddagunts.screencast.util.ManualHostsStore
import io.github.ddagunts.screencast.util.logE
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class AndroidTvViewModel(app: Application) : AndroidViewModel(app) {

    private val discovery = AndroidTvDiscovery(app).also { it.start() }

    // Manual IPs entered by the user — show up in the picker exactly like
    // mDNS-discovered devices. The pair/connect flow doesn't care where the
    // device entry came from; only the host string matters.
    private val manualStore = ManualHostsStore(app, "atv")
    private val _manualHosts = MutableStateFlow(manualStore.list())
    val manualHosts: StateFlow<Set<String>> = _manualHosts

    val discovered: StateFlow<List<AndroidTvDevice>> = combine(
        discovery.flow, _manualHosts,
    ) { mdns, manual ->
        val mdnsHosts = mdns.map { it.host }.toSet()
        val manualOnly = manual.filterNot { it in mdnsHosts }
            .map { AndroidTvDevice(name = it, host = it) }
        (mdns + manualOnly).sortedBy { it.name }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun addManualHost(host: String) {
        _manualHosts.value = manualStore.add(host)
    }

    fun removeManualHost(host: String) {
        _manualHosts.value = manualStore.remove(host)
    }

    private val preferences = AndroidTvRemotePreferences(app)
    private val _notificationEnabled = MutableStateFlow(preferences.notificationEnabled)
    val notificationEnabled: StateFlow<Boolean> = _notificationEnabled
    private val _notificationError = MutableStateFlow<String?>(null)
    val notificationError: StateFlow<String?> = _notificationError

    init { AndroidTvRemotes.acquireUi() }
    private val persistence = AndroidTvPersistence(app)

    private val _paired = MutableStateFlow(persistence.list())
    val paired: StateFlow<List<AndroidTvPersistence.Entry>> = _paired

    // One AndroidTvRemote per host. Keeping these alive across screen
    // toggles avoids having to re-handshake every time the user pops back
    // into the remote screen — most users will toggle in/out frequently.
    // The optional notification can retain a connection after the VM clears.
    private val remotes = AndroidTvRemotes

    private val _currentHost = MutableStateFlow<String?>(null)
    val currentHost: StateFlow<String?> = _currentHost

    private val _currentState = MutableStateFlow<AndroidTvState>(AndroidTvState.Idle)
    val currentState: StateFlow<AndroidTvState> = _currentState

    private val _currentVolume = MutableStateFlow(AndroidTvVolume())
    val currentVolume: StateFlow<AndroidTvVolume> = _currentVolume

    private val _currentImePrompt = MutableStateFlow<AndroidTvImePrompt?>(null)
    val currentImePrompt: StateFlow<AndroidTvImePrompt?> = _currentImePrompt

    // Cancelled and replaced on every selectDevice() so collectors from a
    // prior device don't keep writing into _currentState/_currentVolume/
    // _currentImePrompt and race the new device's flows.
    private var currentJobs: Job? = null

    // Pairing UI plumbing: when a pair() runs it'll set this prompt, the
    // dialog observes it and resumes the deferred when the user submits.
    // Using a single in-flight prompt is safe — we only ever start one
    // pairing at a time, and starting another while one is open replaces
    // (cancels) the previous one.
    data class PairingPrompt(
        val device: AndroidTvDevice,
        val deferred: CompletableDeferred<String?>,
    )
    private val _prompt = MutableStateFlow<PairingPrompt?>(null)
    val pairingPrompt: StateFlow<PairingPrompt?> = _prompt

    fun selectDevice(device: AndroidTvDevice) {
        currentJobs?.cancel()
        _currentHost.value = device.host
        val remote = remoteFor(device)
        showNotification(remote)
        // Seed the surfaced fields from the remote's current values so the
        // UI sees the right state immediately, before the new collectors
        // have a chance to emit.
        _currentState.value = remote.state.value
        _currentVolume.value = remote.volume.value
        _currentImePrompt.value = remote.imePrompt.value
        currentJobs = viewModelScope.launch {
            launch { remote.state.collect { _currentState.value = it } }
            launch { remote.volume.collect { _currentVolume.value = it } }
            launch { remote.imePrompt.collect { _currentImePrompt.value = it } }
            if (remote.isPaired) {
                launch {
                    runCatching { remote.connect() }
                        .onFailure { logE("connect failed for ${device.host}", it) }
                }
            }
        }
    }

    fun startPairing(device: AndroidTvDevice) {
        // Replace any in-flight prompt — UI invariant is "at most one
        // pairing dialog open at a time".
        _prompt.value?.deferred?.complete(null)
        val remote = remoteFor(device)
        viewModelScope.launch {
            val result = remote.pair { promptForCode(device) }
            if (result.isSuccess) {
                persistence.upsert(device)
                _paired.value = persistence.list()
                selectDevice(device)
            }
        }
    }

    private suspend fun promptForCode(device: AndroidTvDevice): String? {
        val deferred = CompletableDeferred<String?>()
        _prompt.value = PairingPrompt(device, deferred)
        return try {
            deferred.await()
        } finally {
            _prompt.value = null
        }
    }

    fun submitPairingCode(code: String) {
        _prompt.value?.deferred?.complete(code)
    }

    fun cancelPairing() {
        _prompt.value?.deferred?.complete(null)
    }

    fun unpair(host: String) {
        if (AndroidTvRemotes.notificationRemote.value?.device?.host == host) {
            AndroidTvRemoteService.hide(getApplication())
        }
        remotes.forget(getApplication(), host)
        // Find the persistence entry for this host (key may be a BLE MAC
        // rather than the host string) so we can forget by stable key.
        _paired.value.firstOrNull { it.host == host }?.let { persistence.forget(it.key) }
        _paired.value = persistence.list()
        if (_currentHost.value == host) {
            leaveCurrent()
        }
    }

    fun connectCurrent() {
        val host = _currentHost.value ?: return
        val remote = remotes[host] ?: return
        showNotification(remote)
        viewModelScope.launch { runCatching { remote.connect() } }
    }

    fun disconnectCurrent() {
        val host = _currentHost.value ?: return
        remotes[host]?.disconnect()
    }

    // Navigate back to the picker without touching the TV connection. The
    // remote stays alive in `remotes` so re-selecting the same device skips
    // the handshake. Cancels the collectors so stale flows from this remote
    // stop writing into the surfaced fields, then clears them so the next
    // selectDevice() doesn't briefly render the prior device's state.
    fun leaveCurrent() {
        currentJobs?.cancel()
        currentJobs = null
        _currentHost.value = null
        _currentState.value = AndroidTvState.Idle
        _currentVolume.value = AndroidTvVolume()
        _currentImePrompt.value = null
    }

    // Every UI-triggered remote action gets its own try/catch so a
    // transient socket failure (broken pipe, EOF after TV reboot) just
    // logs and lets the session's reconnect logic recover, rather than
    // bubbling out of viewModelScope.launch and crashing the app.
    private fun launchSafe(name: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            runCatching { block() }
                .onFailure { logE("$name failed", it) }
        }
    }

    fun sendKey(key: AndroidTvKey) {
        val host = _currentHost.value ?: return
        launchSafe("sendKey($key)") { remotes[host]?.sendKey(key) }
    }

    fun keyDown(key: AndroidTvKey) {
        val host = _currentHost.value ?: return
        launchSafe("keyDown($key)") { remotes[host]?.keyDown(key) }
    }

    fun keyUp(key: AndroidTvKey) {
        val host = _currentHost.value ?: return
        launchSafe("keyUp($key)") { remotes[host]?.keyUp(key) }
    }

    fun longPress(key: AndroidTvKey, holdMs: Long = 800L) {
        val host = _currentHost.value ?: return
        launchSafe("longPress($key)") { remotes[host]?.longPress(key, holdMs) }
    }

    fun setVolume(level: Float) {
        val host = _currentHost.value ?: return
        launchSafe("setVolume($level)") { remotes[host]?.setVolume(level) }
    }

    fun launchApp(uri: String) {
        val host = _currentHost.value ?: return
        launchSafe("launchApp($uri)") { remotes[host]?.launchApp(uri) }
    }

    fun sendImeText(text: String) {
        val host = _currentHost.value ?: return
        launchSafe("sendImeText") { remotes[host]?.sendImeText(text) }
    }

    fun sendImeBackspace() {
        val host = _currentHost.value ?: return
        launchSafe("sendImeBackspace") { remotes[host]?.sendImeBackspace() }
    }

    fun sendImeEnter() {
        val host = _currentHost.value ?: return
        launchSafe("sendImeEnter") { remotes[host]?.sendImeEnter() }
    }

    // Manual "show keyboard" trigger from the UI when there is no
    // TV-pushed prompt yet. Synthesises an empty prompt so the sheet can
    // open; sendImeText against that will start by inserting at position 0.
    fun openImePrompt() {
        val host = _currentHost.value ?: return
        remotes[host]?.openImePrompt()
    }

    // Dismiss the bottom sheet locally without touching the TV. The TV
    // doesn't need to know we hid the sheet — it'll re-push ImeKeyInject
    // the next time focus changes.
    fun closeImePrompt() {
        val host = _currentHost.value ?: return
        remotes[host]?.closeImePrompt()
    }

    fun selectedDevice(): AndroidTvDevice? = _currentHost.value?.let { remotes[it]?.device }

    private fun remoteFor(device: AndroidTvDevice): AndroidTvRemote =
        remotes.getOrCreate(getApplication(), device)

    fun setNotificationEnabled(enabled: Boolean) {
        preferences.notificationEnabled = enabled
        _notificationEnabled.value = enabled
        _notificationError.value = null
        if (!enabled) AndroidTvRemoteService.hide(getApplication())
        else _currentHost.value?.let { remotes[it]?.let(::showNotification) }
    }

    fun notificationPermissionDenied() {
        _notificationError.value = "Allow ScreenCast notifications in Android settings to use remote controls here."
    }

    private fun showNotification(remote: AndroidTvRemote) {
        if (!_notificationEnabled.value || !remote.isPaired) return
        runCatching { AndroidTvRemoteService.show(getApplication(), remote.device) }
            .onFailure {
                _notificationError.value = "Could not show remote controls: ${it.message}"
                logE("Remote notification failed", it)
            }
    }

    fun resumeCurrent() {
        val remote = _currentHost.value?.let { remotes[it] } ?: return
        viewModelScope.launch { remote.resume() }
        if (_notificationEnabled.value && remote.state.value !is AndroidTvState.Idle) showNotification(remote)
    }

    fun openNotificationRemote() {
        AndroidTvRemotes.notificationRemote.value?.let { selectDevice(it.device) }
    }

    override fun onCleared() {
        discovery.stop()
        remotes.releaseUi()
        super.onCleared()
    }
}
