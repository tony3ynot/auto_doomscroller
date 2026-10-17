package com.autodoomscroller.session

import com.autodoomscroller.AppGraph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class SessionMode { OFF, LEARNING, AUTO }

/**
 * Single source of truth for the current mode, shared by the UI and the accessibility service.
 * A session row is opened when leaving OFF and closed when returning to OFF.
 */
object SessionController {
    private val _mode = MutableStateFlow(SessionMode.OFF)
    val mode: StateFlow<SessionMode> = _mode.asStateFlow()

    private val _researchMode = MutableStateFlow(false)

    /** When on, a few downscaled frames per reel are kept on disk for offline experiments. */
    val researchMode: StateFlow<Boolean> = _researchMode.asStateFlow()

    // One worker so session start/end writes run in the order the mode changed.
    private val sessionDispatcher = Dispatchers.IO.limitedParallelism(1)

    @Volatile
    var sessionId: Long? = null
        private set

    fun setMode(newMode: SessionMode) {
        val oldMode = _mode.value
        if (oldMode == newMode) return
        _mode.value = newMode
        AppGraph.appScope.launch(sessionDispatcher) {
            when {
                oldMode == SessionMode.OFF -> sessionId = AppGraph.reelRepository.startSession()
                newMode == SessionMode.OFF -> {
                    sessionId?.let { AppGraph.reelRepository.endSession(it) }
                    sessionId = null
                }
            }
        }
    }

    fun setResearchMode(enabled: Boolean) {
        _researchMode.value = enabled
    }
}
