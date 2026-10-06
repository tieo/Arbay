package io.github.tieo.arbay.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import io.github.tieo.arbay.navigation.Route

/**
 * The steps the back gesture walks. The three places at the bottom of the screen each start a walk
 * of their own; everything opened from them is a step on top.
 */
class Navigator(start: Route = Route.Home) {
    private val stack = mutableStateListOf(start)

    val current: Route get() = stack.last()
    val canGoBack: Boolean get() = stack.size > 1

    /** Everything under the top step, for keeping a screen alive while something covers it. */
    val steps: List<Route> get() = stack

    fun go(to: Route) { if (to != current) stack.add(to) }

    /** Changes the top step in place. */
    fun replace(to: Route) { stack[stack.lastIndex] = to }

    fun back(): Boolean = if (stack.size > 1) { stack.removeAt(stack.lastIndex); true } else false

    /** One of the places at the bottom of the screen, as a fresh walk. */
    fun top(to: Route) { stack.clear(); stack.add(to) }
}

val LocalNavigator = staticCompositionLocalOf { Navigator() }

/** Runs [onBack] when the system back gesture completes, for as long as it is composed. */
@Composable
fun OnBack(onBack: () -> Unit) {
    NavigationBackHandler(
        state = rememberNavigationEventState(NavigationEventInfo.None),
        isBackEnabled = true,
        onBackCompleted = onBack,
    )
}
