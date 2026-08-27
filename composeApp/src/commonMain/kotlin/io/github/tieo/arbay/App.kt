package io.github.tieo.arbay

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.*
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.tieo.arbay.api.ArbayClient
import io.github.tieo.arbay.debug.DebugRegistry
import io.github.tieo.arbay.debug.debugSnapshotJson
import io.github.tieo.arbay.ui.LocalDesktopMode
import io.github.tieo.arbay.ui.screen.MainScreen
import io.github.tieo.arbay.ui.theme.ArbayTheme
import io.github.tieo.arbay.ui.viewmodel.FreeItemViewModel
import io.github.tieo.arbay.ui.viewmodel.ListingViewModel
import io.github.tieo.arbay.ui.viewmodel.ProductViewModel

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
        }

        // Load exchange rates + refresh the car taxonomy on startup
        LaunchedEffect(Unit) {
            try {
                DisplayCurrency.rates = client.getExchangeRates()
            } catch (_: Exception) {}
            try {
                CarTaxonomyStore.update(client.getCarTaxonomy())
            } catch (_: Exception) {}
        }

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
