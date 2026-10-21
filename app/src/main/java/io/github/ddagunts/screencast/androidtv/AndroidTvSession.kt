package io.github.ddagunts.screencast.androidtv

import io.github.ddagunts.screencast.util.logD
import io.github.ddagunts.screencast.util.logW
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed class AndroidTvState {
    data object Idle : AndroidTvState()
    data object Connecting : AndroidTvState()
    data object Active : AndroidTvState()
    data class Reconnecting(val attempt: Int) : AndroidTvState()
    data class Error(val message: String) : AndroidTvState()
}

data class AndroidTvVolume(
    val level: Int = 0,
    val max: Int = 100,
    val muted: Boolean = false,
) {
    val fraction: Float get() = if (max <= 0) 0f else level.toFloat() / max.toFloat()
}

// Snapshot of an active text field on the TV. Surfaced as a StateFlow so
// the UI can auto-open its IME bottom sheet when this turns non-null and
// dismiss when it goes null. `value`/`selectionStart`/`selectionEnd`
// reflect the TV's last push — the phone sheet should pre-populate from
// these so the user is editing what's actually on the TV. `label` is the
// field's hint text (e.g. "Search YouTube") when the TV provides one;
// `appPackage` is the foreground app that owns the field.
data class AndroidTvImePrompt(
    val appPackage: String,
    val appLabel: String,
    val label: String,
    val value: String,
    val selectionStart: Int,
    val selectionEnd: Int,
)

// Lifecycle FSM around AndroidTvRemoteChannel. Owns:
//  * The connect/handshake sequence (Configure + SetActive — without those
//    many TVs silently drop subsequent input).
//  * Reconnect-with-backoff on transport drop.
//  * Volume StateFlow, kept in sync with both sides — TV pushes whenever
//    the system volume changes (including from its own remote), and we
//    push when the user moves our slider.
//  * App-launch + key-inject routing through the channel's write mutex.
class AndroidTvSession(
    val device: AndroidTvDevice,
    private val clientMaterial: AndroidTvCertFactory.Material,
    private val serverCertPin: String,
    private val deviceModel: String = "ScreenCast",
) {
    @Volatile private var channel: AndroidTvRemoteChannel? = null
    private val lifecycleLock = Any()
    private var connectionJob: Job? = null
    @Volatile private var lastMessageNs = 0L
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow<AndroidTvState>(AndroidTvState.Idle)
    val state: StateFlow<AndroidTvState> = _state

    private val _volume = MutableStateFlow(AndroidTvVolume())
    val volume: StateFlow<AndroidTvVolume> = _volume

    // What the phone's IME bottom sheet is currently editing. Non-null
    // iff the sheet should be visible. Only ever set by openImePrompt() /
    // closeImePrompt(). TV pushes intentionally DO NOT mutate this —
    // auto-opening the sheet every time the TV reports a focused text
    // field was overwhelming, so the sheet is manual-only.
    private val _imePrompt = MutableStateFlow<AndroidTvImePrompt?>(null)
    val imePrompt: StateFlow<AndroidTvImePrompt?> = _imePrompt

    // The TV's last-known focused text field. Updated continuously from
    // ImeKeyInject / ImeShowRequest pushes. Used solely to seed _imePrompt
    // when the user manually opens the sheet, so they start editing what's
    // actually on the TV instead of a blank field. @Volatile because
    // writes happen from observeIncoming and reads happen from the UI
    // thread via openImePrompt().
    @Volatile private var lastTvField: AndroidTvImePrompt? = null

    // Counters carried on every outbound ImeBatchEdit. Per the canonical
    // androidtvremote2 (Python) library, BOTH counters are echo-driven:
    // they start at 0, are NEVER incremented by the sender, and are
    // updated only when the TV pushes a remote_ime_batch_edit message
    // back to us. We tried the opposite (bump locally) and the TV
    // silently dropped every edit.
    @Volatile private var imeCounter: Int = 0
    @Volatile private var fieldCounter: Int = 0

    // Mirror of the TV's focused-field content as last pushed by us. Used
    // as the `end` of the span the next ImeBatchEdit overwrites — we
    // send `start=0, end=prevTvText.length, value=newText` so the entire
    // previous content is replaced. Tracking this locally is required
    // because the canonical Python `start=end=len-1` shape is interpreted
    // by the wild firmware as a *literal* span insert at position len-1,
    // not as a full-field replacement, so each progressive keystroke
    // compounds ("h" → "hhe" → "hhhele"). Initialized from the TV's
    // last-known field value when the user opens the IME sheet and
    // updated after every successful send.
    @Volatile private var prevTvText: String = ""

    // One coroutine owns the entire connection/retry lifetime. A close during
    // handshake cannot fall between separate reader and reconnect jobs.
    suspend fun connect() = startConnection(restart = false)

    private fun startConnection(restart: Boolean) {
        synchronized(lifecycleLock) {
            if (!restart && connectionJob?.isActive == true) return
            connectionJob?.cancel()
            channel?.close()
            channel = null
            resetIme()
            _state.value = AndroidTvState.Connecting
            connectionJob = backgroundScope.launch { runConnection() }
        }
    }

    private suspend fun runConnection() {
        var failures = 0
        while (currentCoroutineContext().isActive) {
            var activeSince = 0L
            val ch = AndroidTvRemoteChannel(device.host, device.port, clientMaterial, serverCertPin)
            val context = currentCoroutineContext()
            synchronized(lifecycleLock) {
                context.ensureActive()
                channel = ch
                resetIme()
            }
            try {
                var features = SUPPORTED_FEATURES
                ch.run { msg ->
                    currentCoroutineContext().ensureActive()
                    when (msg) {
                        is RemoteMessage.Configure -> {
                            features = SUPPORTED_FEATURES and msg.code1
                            require(features and FEATURE_KEY != 0) { "TV does not support remote keys" }
                            ch.send(RemoteMessage.Configure(features, RemoteDeviceInfo(
                                model = deviceModel, vendor = "ScreenCast",
                                packageName = "io.github.ddagunts.screencast", appVersion = "1.0.0",
                            )))
                        }
                        is RemoteMessage.SetActive -> ch.send(RemoteMessage.SetActive(features))
                        else -> synchronized(lifecycleLock) {
                            context.ensureActive()
                            if (channel !== ch) return@synchronized
                            lastMessageNs = System.nanoTime()
                            when (msg) {
                                is RemoteMessage.StartedNotification -> {
                                    if (activeSince == 0L) activeSince = System.nanoTime()
                                    _state.value = AndroidTvState.Active
                                    ch.markReady()
                                }
                                is RemoteMessage.SetVolumeLevel ->
                                    _volume.value = AndroidTvVolume(msg.volumeLevel, msg.volumeMax, msg.volumeMuted)
                                is RemoteMessage.Error -> logW("TV rejected a remote message: ${msg.message}")
                                is RemoteMessage.ImeKeyInject -> handleImeKeyInject(msg)
                                is RemoteMessage.ImeShowRequest -> handleImeShowRequest(msg)
                                is RemoteMessage.ImeBatchEdit -> handleImeBatchEditEcho(msg)
                                else -> Unit
                            }
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                if (activeSince != 0L && System.nanoTime() - activeSince >= STABILITY_GRACE_MS * 1_000_000) {
                    failures = 0
                }
                failures++
                if (e is SecurityException || failures > MAX_RECONNECT_ATTEMPTS) {
                    synchronized(lifecycleLock) {
                        context.ensureActive()
                        _state.value = AndroidTvState.Error(e.message ?: "TV connection lost")
                    }
                    return
                }
                logW("ATV connection lost; retry $failures: ${e.message}")
                synchronized(lifecycleLock) {
                    context.ensureActive()
                    _state.value = AndroidTvState.Reconnecting(failures)
                }
            } finally {
                ch.close()
                synchronized(lifecycleLock) {
                    if (channel === ch) {
                        channel = null
                        resetIme()
                    }
                }
            }
            delay((1_000L shl (failures - 1).coerceIn(0, 3)))
        }
    }

    // TV announced a text-field state. Cache it so a subsequent
    // openImePrompt() can pre-populate the sheet with what the TV
    // actually has focused.
    private fun handleImeKeyInject(msg: RemoteMessage.ImeKeyInject) {
        val status = msg.textFieldStatus
        if (lastTvField?.appPackage != msg.appInfo.appPackage) {
            _imePrompt.value = null
            prevTvText = ""
        }
        lastTvField = AndroidTvImePrompt(
            appPackage = msg.appInfo.appPackage,
            appLabel = msg.appInfo.label,
            label = status.label,
            value = status.value,
            selectionStart = status.start,
            selectionEnd = status.end,
        )
    }

    private fun handleImeShowRequest(msg: RemoteMessage.ImeShowRequest) {
        val status = msg.textFieldStatus
        // ImeShowRequest arrives without app_info — preserve whatever
        // ImeKeyInject most recently cached.
        val existing = lastTvField
        lastTvField = AndroidTvImePrompt(
            appPackage = existing?.appPackage ?: "",
            appLabel = existing?.appLabel ?: "",
            label = status.label.ifEmpty { existing?.label ?: "" },
            value = status.value,
            selectionStart = status.start,
            selectionEnd = status.end,
        )
    }

    // Counters belong to the current TV input connection and can reset when
    // switching apps or fields. Always use the latest TV values.
    private fun handleImeBatchEditEcho(msg: RemoteMessage.ImeBatchEdit) {
        imeCounter = msg.imeCounter
        fieldCounter = msg.fieldCounter
        logD("ime echo: imeCtr=${msg.imeCounter} fieldCtr=${msg.fieldCounter} (local now ime=$imeCounter field=$fieldCounter)")
    }

    suspend fun resume() {
        val state = _state.value
        if (state is AndroidTvState.Idle) return
        if (state !is AndroidTvState.Active || System.nanoTime() - lastMessageNs > 15_000_000_000L) {
            startConnection(restart = true)
        }
    }

    fun disconnect() {
        synchronized(lifecycleLock) {
            connectionJob?.cancel()
            connectionJob = null
            channel?.close()
            channel = null
            _state.value = AndroidTvState.Idle
            resetIme()
        }
    }

    fun close() {
        disconnect()
        backgroundScope.cancel()
    }

    private fun resetIme() {
        _imePrompt.value = null
        lastTvField = null
        imeCounter = 0
        fieldCounter = 0
        prevTvText = ""
    }

    private fun activeChannel(): AndroidTvRemoteChannel? =
        channel.takeIf { _state.value is AndroidTvState.Active }

    suspend fun sendKey(key: AndroidTvKey, direction: RemoteDirection = RemoteDirection.SHORT) {
        val ch = activeChannel() ?: return logW("sendKey: not connected")
        ch.send(RemoteMessage.KeyInject(key.wire, direction))
    }

    suspend fun keyDown(key: AndroidTvKey) = sendKey(key, RemoteDirection.START_LONG)
    suspend fun keyUp(key: AndroidTvKey) = sendKey(key, RemoteDirection.END_LONG)

    // Press-and-hold a key for `holdMs`. Used for "long-press Home"
    // (which on Sony BRAVIA opens the Action Menu where Settings lives)
    // and similar physical-remote gestures. Sends JUST down + up — no
    // SHORT in between — because mixing all three for the same keycode
    // makes the TV close the socket as malformed input.
    suspend fun longPress(key: AndroidTvKey, holdMs: Long = 800L) {
        val ch = activeChannel() ?: return
        ch.send(RemoteMessage.KeyInject(key.wire, RemoteDirection.START_LONG))
        try {
            delay(holdMs)
        } finally {
            // Never send the release into a replacement connection.
            withContext(NonCancellable) {
                if (channel === ch) runCatching {
                    ch.send(RemoteMessage.KeyInject(key.wire, RemoteDirection.END_LONG))
                }
            }
        }
    }

    // Setting the absolute level with RemoteSetVolumeLevel; the TV mirrors
    // back via the SetVolumeLevel inbound push, so the slider settles to
    // the actual achieved level (not the requested one) as the StateFlow
    // updates. `levelFraction` is clamped to [0,1].
    suspend fun setVolume(levelFraction: Float) {
        val ch = activeChannel() ?: return logW("setVolume: not connected")
        val v = _volume.value
        val target = (levelFraction.coerceIn(0f, 1f) * v.max).toInt()
        ch.send(RemoteMessage.SetVolumeLevel(
            playerModel = "",
            volumeLevel = target,
            volumeMax = v.max,
            volumeMuted = v.muted,
        ))
    }

    suspend fun setMuted(muted: Boolean) {
        // No dedicated mute message — VOLUME_MUTE key works on every TV.
        sendKey(AndroidTvKey.Mute)
    }

    suspend fun launchApp(uri: String) {
        val ch = activeChannel() ?: return logW("launchApp: not connected")
        ch.send(RemoteMessage.AppLinkLaunchRequest(uri))
    }

    // Push the phone-side full text buffer to the TV as one
    // RemoteImeBatchEdit, span-replacing the entire previous content
    // (`start=0, end=prevTvText.length, value=newText`).
    //
    // Why not the canonical Python `start=end=len(newText)-1` shape?
    // That shape is documented as "set field to value with cursor at
    // len-1" but the wild firmware actually interprets it as a literal
    // span insert at position len-1, so progressive sends compound:
    //   send "h" into "":  insert at 0 → "h"          ✓
    //   send "he" into "h": insert at 1 → "h"+"he" = "hhe"  ✗
    //   send "hel" into "hhe": insert at 2 → "hh"+"hel"+"e" = "hhhele" ✗
    // A full-range replacement [0..prev.length] unambiguously rewrites
    // the field on this firmware. Canonical Python gets away with the
    // len-1 shape because it's intended as a one-shot send into an
    // empty field; our progressive on-screen-keyboard usage breaks it.
    //
    // Counters: both ime_counter and field_counter ride on every frame.
    // Per canonical Python, neither is ever bumped by the sender — they
    // ratchet up via TV echoes only and stay at 0 until the first echo.
    //
    // Empty `newText` is a legitimate "clear the field" delete (the
    // Backspace button shortens to empty); the wire shape stays valid
    // because `start=0, end=prev.length, value=""` encodes cleanly.
    suspend fun sendImeText(newText: String) {
        val ch = activeChannel() ?: return logW("sendImeText: not connected")
        if (_imePrompt.value == null) return logW("sendImeText: no active IME prompt")
        val prev = prevTvText
        if (newText == prev) return
        logD("sendImeText: length=${newText.length} imeCtr=$imeCounter fieldCtr=$fieldCounter")
        ch.send(RemoteMessage.ImeBatchEdit(
            imeCounter = imeCounter,
            fieldCounter = fieldCounter,
            editInfo = listOf(RemoteEditInfo(
                insert = 1,
                textFieldStatus = RemoteImeObject(start = 0, end = prev.length, value = newText),
            )),
        ))
        prevTvText = newText
    }

    // Submit / "Done" — sends KEYCODE_ENTER, which is what the on-screen
    // soft keyboard fires for IME_ACTION_DONE. Most TV search fields treat
    // this as "commit the query". Distinct from DPadCenter (which is the
    // generic "click" focus dispatcher).
    suspend fun sendImeEnter() = sendKey(AndroidTvKey.Enter)

    // The sheet's Backspace button updates its own TextFieldValue and the
    // change re-triggers the debounced send through sendImeText with the
    // already-shortened full buffer, so there's nothing to do here at the
    // wire level. Kept as a hook for the UI so the button still has a
    // semantic call site.
    suspend fun sendImeBackspace() {}

    // UI hook: user tapped the manual "keyboard" button. Seeds the sheet
    // from the TV's last-known focused field if we have one cached (so the
    // user starts editing what's actually on the TV); otherwise opens an
    // empty prompt and the first character lands wherever the TV currently
    // has focus.
    fun openImePrompt() {
        if (_state.value !is AndroidTvState.Active || _imePrompt.value != null) return
        val seed = lastTvField ?: AndroidTvImePrompt(
            appPackage = "",
            appLabel = "",
            label = "",
            value = "",
            selectionStart = 0,
            selectionEnd = 0,
        )
        _imePrompt.value = seed
        // Mirror the TV's current field content so the next sendImeText
        // span-replaces over the right range. Without this, the first
        // keystroke would [0..0]-replace and leave anything the TV
        // already had (e.g., a previously-typed search query) prefixed
        // to the user's new input.
        prevTvText = seed.value
    }

    fun closeImePrompt() {
        _imePrompt.value = null
    }

    companion object {
        private const val FEATURE_KEY = 2
        // Ping, keys, IME, power, volume and app links; voice is unsupported.
        private const val SUPPORTED_FEATURES = 1 or 2 or 4 or 32 or 64 or 512
        private const val MAX_RECONNECT_ATTEMPTS = 6
        // Brief handshake flaps must keep increasing the retry backoff.
        private const val STABILITY_GRACE_MS = 10_000L
    }
}
