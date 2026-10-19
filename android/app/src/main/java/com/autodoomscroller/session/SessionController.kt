package com.autodoomscroller.session

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
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
 *
 * Settings are persisted because the system can kill the process at any time; when it comes
 * back in a non-OFF mode a fresh session is opened (the old one keeps a null end time).
 */
object SessionController {
    private const val KEY_MODE = "mode"
    private const val KEY_RESEARCH_MODE = "research_mode"
    private const val KEY_AD_SKIP = "ad_skip"

    private lateinit var prefs: SharedPreferences

    private val _mode = MutableStateFlow(SessionMode.OFF)
    val mode: StateFlow<SessionMode> = _mode.asStateFlow()

    private val _researchMode = MutableStateFlow(false)

    /** When on, a few downscaled frames per reel are kept on disk for offline experiments. */
    val researchMode: StateFlow<Boolean> = _researchMode.asStateFlow()

    private val _adSkip = MutableStateFlow(false)

    /** Swipe past ads as soon as they are detected. Off by default. */
    val adSkip: StateFlow<Boolean> = _adSkip.asStateFlow()

    // One worker so session start/end writes run in the order the mode changed.
    private val sessionDispatcher = Dispatchers.IO.limitedParallelism(1)

    @Volatile
    var sessionId: Long? = null
        private set

    /** Call once from AppGraph.init, after the repository exists. */
    fun init(context: Context) {
        prefs = context.getSharedPreferences("session", Context.MODE_PRIVATE)
        _researchMode.value = prefs.getBoolean(KEY_RESEARCH_MODE, false)
        _adSkip.value = prefs.getBoolean(KEY_AD_SKIP, false)
        val savedMode = prefs.getString(KEY_MODE, null)
            ?.let { name -> SessionMode.entries.firstOrNull { it.name == name } }
            ?: SessionMode.OFF
        setMode(savedMode)
    }

    fun setMode(newMode: SessionMode) {
        val oldMode = _mode.value
        if (oldMode == newMode) return
        _mode.value = newMode
        prefs.edit { putString(KEY_MODE, newMode.name) }
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
        prefs.edit { putBoolean(KEY_RESEARCH_MODE, enabled) }
    }

    fun setAdSkip(enabled: Boolean) {
        _adSkip.value = enabled
        prefs.edit { putBoolean(KEY_AD_SKIP, enabled) }
    }
}
