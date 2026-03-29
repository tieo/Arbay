package io.github.tieo.arbay

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.*
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.tieo.arbay.api.ArbayClient
import io.github.tieo.arbay.ui.LocalDesktopMode
import io.github.tieo.arbay.ui.screen.MainScreen
import io.github.tieo.arbay.ui.theme.ArbayTheme
import io.github.tieo.arbay.ui.viewmodel.AlertViewModel
import io.github.tieo.arbay.ui.viewmodel.ListingViewModel
import io.github.tieo.arbay.ui.viewmodel.ProductViewModel

@Composable
fun App() {
    ArbayTheme {
        val client = remember { ArbayClient() }
        val productViewModel = viewModel { ProductViewModel(client) }
        val alertViewModel = viewModel { AlertViewModel(client) }
        val listingViewModel = viewModel { ListingViewModel(client) }

        // Load exchange rates on startup
        LaunchedEffect(Unit) {
            try {
                DisplayCurrency.rates = client.getExchangeRates()
            } catch (_: Exception) {}
        }

        BoxWithConstraints {
            val isDesktop = maxWidth > 700.dp
            CompositionLocalProvider(LocalDesktopMode provides isDesktop) {
                MainScreen(
                    productViewModel = productViewModel,
                    alertViewModel = alertViewModel,
                    listingViewModel = listingViewModel,
                    client = client,
                )
            }
        }
    }
}
