package io.github.tieo.arbay.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CardGiftcard
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Badge
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import io.github.tieo.arbay.api.ArbayClient
import io.github.tieo.arbay.navigation.Route
import io.github.tieo.arbay.navigation.Source
import io.github.tieo.arbay.viewmodel.ChatViewModel
import io.github.tieo.arbay.viewmodel.FreeItemViewModel
import io.github.tieo.arbay.viewmodel.ListingViewModel
import io.github.tieo.arbay.viewmodel.ProductViewModel

/** One client and one view model of each kind, for the whole session, as the browser holds them. */
class Session(
    val client: ArbayClient,
    val products: ProductViewModel,
    val listings: ListingViewModel,
    val freeItems: FreeItemViewModel,
    val chat: ChatViewModel,
)

private data class Place(val route: Route, val label: String, val icon: ImageVector)

private val PLACES = listOf(
    Place(Route.Home, "Searches", Icons.Outlined.Home),
    Place(Route.Inbox, "Messages", Icons.Outlined.ChatBubbleOutline),
    Place(Route.FreeItems, "Free items", Icons.Outlined.CardGiftcard),
    Place(Route.Settings, "Settings", Icons.Outlined.Settings),
)

/**
 * The phone app: four places at the bottom (searches, messages, free items, settings), and everything
 * opened from them on top, one step at a time, with back walking the steps.
 *
 * The phone is where new finds are checked and notifications land; comparing side by side is the
 * browser's job, so a phone screen shows one thing at a time and the step behind it stays alive.
 */
@Composable
fun ArbayApp(session: Session, navigator: Navigator = remember { Navigator() }) {
    val freeMatches by session.freeItems.newMatches.collectAsState()
    val conversations by session.chat.conversations.collectAsState()
    val route = navigator.current
    val holder = rememberSaveableStateHolder()
    // Composed only while there is a step to go back to: a handler that is merely switched off
    // and on again is not handed back to the system.
    if (navigator.canGoBack) OnBack { navigator.back() }

    CompositionLocalProvider(LocalNavigator provides navigator) {
        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f)) {
                    val key = when (route) {
                        is Route.Results -> "results:" + when (val s = route.source) {
                            is Source.Saved -> "saved:${s.id}"
                            is Source.Typed -> "typed:${s.text}"
                            is Source.Vehicle -> "vehicle:${s.text}"
                        }
                        else -> route.toString()
                    }
                    holder.SaveableStateProvider(key) {
                        when (route) {
                            Route.Home -> HomeScreen(session)
                            is Route.Results -> ResultsScreen(session, route)
                            Route.VehicleForm -> VehicleScreen(session)
                            Route.FreeItems -> FreeItemsScreen(session)
                            Route.Settings -> SettingsScreen(session)
                            Route.Inbox -> InboxScreen(session)
                            is Route.Conversation -> ConversationScreen(session, route.id)
                        }
                    }
                }
                val atTop = PLACES.any { it.route == route }
                if (atTop) {
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
                        PLACES.forEach { place ->
                            NavigationBarItem(
                                selected = route == place.route,
                                onClick = { navigator.top(place.route) },
                                icon = {
                                    val count = when (place.route) {
                                        Route.FreeItems -> freeMatches.size
                                        Route.Inbox -> conversations.sumOf { it.unread }
                                        else -> 0
                                    }
                                    if (count > 0) BadgedBox(badge = { Badge { Text("$count") } }) { Icon(place.icon, null) }
                                    else Icon(place.icon, null)
                                },
                                label = { Text(place.label) },
                                colors = NavigationBarItemDefaults.colors(indicatorColor = MaterialTheme.colorScheme.primaryContainer),
                            )
                        }
                    }
                }
            }
        }
    }
}
