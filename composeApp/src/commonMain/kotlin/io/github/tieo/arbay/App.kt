package io.github.tieo.arbay

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.tieo.arbay.api.ArbayClient
import io.github.tieo.arbay.debug.DebugRegistry
import io.github.tieo.arbay.debug.debugJson
import io.github.tieo.arbay.debug.debugSnapshotJson
import io.github.tieo.arbay.history.SearchHistoryStore
import io.github.tieo.arbay.ui.ArbayApp
import io.github.tieo.arbay.ui.ArbayTheme
import io.github.tieo.arbay.ui.Session
import io.github.tieo.arbay.viewmodel.ChatViewModel
import io.github.tieo.arbay.viewmodel.FreeItemViewModel
import io.github.tieo.arbay.viewmodel.ListingViewModel
import io.github.tieo.arbay.viewmodel.ProductViewModel
import kotlinx.serialization.encodeToString

@Composable
fun App() {
    ArbayTheme {
        val client = remember { ArbayClient() }
        val session = Session(
            client = client,
            products = viewModel { ProductViewModel(client) },
            listings = viewModel { ListingViewModel(client) },
            freeItems = viewModel { FreeItemViewModel(client) },
            chat = viewModel { ChatViewModel(client) },
        )

        // The debug dump (see debug/DebugRegistry.kt): the view models live for the whole session,
        // and each provider reads live values, so a dump taken later reflects that moment.
        LaunchedEffect(Unit) {
            DebugRegistry.register("products") { session.products.debugSnapshotJson() }
            DebugRegistry.register("results") { session.listings.debugSnapshotJson() }
            DebugRegistry.register("freeItems") { session.freeItems.debugSnapshotJson() }
            DebugRegistry.register("displayCurrency") { "\"${DisplayCurrency.current}\"" }
            DebugRegistry.register("searchHistory") { debugJson.encodeToString(SearchHistoryStore.entries.value) }
        }

        // Where the reader is, so every offer can say how far away it is: the device's own position
        // where the app already may read it, the home town otherwise.
        ReadPositionIfAllowed { lat, lon -> DevicePosition.set(lat, lon) }
        LaunchedEffect(Unit) { positionFromHomeTown(client) }
        LaunchedEffect(Unit) { loadServerSettings(client) }
        LaunchedEffect(Unit) { session.products.loadProducts() }
        LaunchedEffect(Unit) { session.chat.watch() }

        ArbayApp(session)
    }
}
