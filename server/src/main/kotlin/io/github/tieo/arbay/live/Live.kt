package io.github.tieo.arbay.live

import io.github.tieo.arbay.model.LiveEvent
import io.github.tieo.arbay.model.LiveKind
import io.github.tieo.arbay.model.OnScreen
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** The server's changes as they happen, for every open app (see [LiveEvent]). */
object Live {
    private val events = MutableSharedFlow<LiveEvent>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val flow: SharedFlow<LiveEvent> = events.asSharedFlow()

    fun changed(kind: LiveKind) { events.tryEmit(LiveEvent(kind)) }

    private val screens = java.util.concurrent.ConcurrentHashMap<String, OnScreen>()

    /** What each device last said it shows. */
    fun onScreen(): List<OnScreen> = screens.values.sortedByDescending { it.at }

    fun here(screen: OnScreen) { screens[screen.device] = screen.copy(at = kotlin.time.Clock.System.now()) }

    /** Open [path] on every screen in use. */
    fun show(path: String) { events.tryEmit(LiveEvent(LiveKind.SHOW, path)) }
}
