package cc.opencar.assistant.api

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Head-unit screen state from the shell's wake/sleep detection; pollers slow down while off. */
object ScreenState {
    private val _on = MutableStateFlow(true)
    val on: StateFlow<Boolean> = _on.asStateFlow()

    fun set(on: Boolean) {
        _on.value = on
    }
}

/**
 * Extra broadcast actions a platform contributes for HU wake / sleep detection.
 * The shell always listens for AOSP [android.content.Intent.ACTION_SCREEN_ON] /
 * [android.content.Intent.ACTION_SCREEN_OFF] (and related); platforms add vendor
 * intents when those are missing (e.g. Flyme with lockscreen disabled).
 */
data class WakeSignals(
    val wakeActions: Set<String> = emptySet(),
    val sleepActions: Set<String> = emptySet(),
) {
    fun isEmpty(): Boolean = wakeActions.isEmpty() && sleepActions.isEmpty()
}
