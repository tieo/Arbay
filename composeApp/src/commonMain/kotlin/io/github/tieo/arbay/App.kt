package io.github.tieo.arbay

import androidx.compose.runtime.*
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.tieo.arbay.api.ArbayClient
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

        MainScreen(
            productViewModel = productViewModel,
            alertViewModel = alertViewModel,
            listingViewModel = listingViewModel,
            client = client,
        )
    }
}
