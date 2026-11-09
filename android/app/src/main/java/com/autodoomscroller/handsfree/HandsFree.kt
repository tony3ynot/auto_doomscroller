package com.autodoomscroller.handsfree

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Links the camera service, which sees gestures, to the accessibility service, which acts on
 * them. Both run in the app's process.
 */
object HandsFree {
    private val _running = MutableStateFlow(false)

    /** The camera service is up (the user turned hands-free mode on). */
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _feedVisible = MutableStateFlow(false)

    /** A supported feed is in front; the camera only runs while one is. */
    val feedVisible: StateFlow<Boolean> = _feedVisible.asStateFlow()

    private val _commands = MutableSharedFlow<HandsFreeCommand>(extraBufferCapacity = 4)
    val commands: SharedFlow<HandsFreeCommand> = _commands.asSharedFlow()

    fun setRunning(running: Boolean) {
        _running.value = running
    }

    fun setFeedVisible(visible: Boolean) {
        _feedVisible.value = visible
    }

    fun send(command: HandsFreeCommand) {
        _commands.tryEmit(command)
    }
}
