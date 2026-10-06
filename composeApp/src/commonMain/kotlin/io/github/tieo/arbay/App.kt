package io.github.tieo.arbay

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.*
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.tieo.arbay.api.ArbayClient
import io.github.tieo.arbay.debug.DebugRegistry
import io.github.tieo.arbay.debug.debugJson
import io.github.tieo.arbay.debug.debugSnapshotJson
import io.github.tieo.arbay.history.SearchHistoryStore
import io.github.tieo.arbay.ui.LocalDesktopMode
import kotlinx.serialization.encodeToString
import io.github.tieo.arbay.ui.screen.MainScreen
import io.github.tieo.arbay.ui.theme.ArbayTheme
import io.github.tieo.arbay.viewmodel.FreeItemViewModel
import io.github.tieo.arbay.viewmodel.ListingViewModel
import io.github.tieo.arbay.viewmodel.ProductViewModel

@Composable
fun App() {
    ArbayTheme {
        val client = remember { ArbayClient() }
        val productViewModel = viewModel { ProductViewModel(client) }
        val listingViewModel = viewModel { ListingViewModel(client) }
        val freeItemViewModel = viewModel { FreeItemViewModel(client) }

        // The debug dump (see debug/DebugRegistry.kt): these three view models live for the whole
        // app session, so registering once here covers "what does the app currently hold" for as
        // long as the process runs. Each provider reads live StateFlow values, not a snapshot taken
        // now, so a dump requested later still reflects whatever is true at that moment.
        LaunchedEffect(Unit) {
            DebugRegistry.register("products") { productViewModel.debugSnapshotJson() }
            DebugRegistry.register("results") { listingViewModel.debugSnapshotJson() }
            DebugRegistry.register("freeItems") { freeItemViewModel.debugSnapshotJson() }
            // A global, not a screen: affects every price on every screen regardless of whether
            // Settings is open, so it belongs beside the view models, not behind a DebugSlice.
            DebugRegistry.register("displayCurrency") { "\"${DisplayCurrency.current}\"" }
            DebugRegistry.register("searchHistory") { debugJson.encodeToString(SearchHistoryStore.entries.value) }
        }

        // Where the reader is, so every listing can say how far away it is. The device's own
        // position where the app already has permission, the home town otherwise; the permission
        // prompt belongs to the nearest-first control, which is someone asking for it.
        ReadPositionIfAllowed { lat, lon -> DevicePosition.set(lat, lon) }
        LaunchedEffect(Unit) { positionFromHomeTown(client) }
        LaunchedEffect(Unit) { loadServerSettings(client) }

        BoxWithConstraints {
            val isDesktop = maxWidth > 700.dp
            CompositionLocalProvider(LocalDesktopMode provides isDesktop) {
                MainScreen(
                    productViewModel = productViewModel,
                    listingViewModel = listingViewModel,
                    freeItemViewModel = freeItemViewModel,
                    client = client,
                )
            }
        }
    }
}
