package io.github.ddagunts.screencast

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

// Keeps a cancelled automatic attempt from starting a service after the user
// has already pressed Stop while Android's projection dialog was open.
object MirrorAttemptRegistry {
    data class Consent(val id: String, val granted: Boolean)

    private var activeId: String? = null
    private val _consent = MutableStateFlow<Consent?>(null)
    val consent: StateFlow<Consent?> = _consent

    @Synchronized fun begin(id: String) {
        activeId = id
        _consent.value = null
    }

    @Synchronized fun isActive(id: String): Boolean = activeId == id

    @Synchronized fun report(id: String, granted: Boolean) {
        if (activeId == id) _consent.value = Consent(id, granted)
    }

    @Synchronized fun cancel(id: String) {
        if (activeId == id) activeId = null
    }
}
