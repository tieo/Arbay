package io.github.tieo.arbay.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.tieo.arbay.DisplayCurrency
import io.github.tieo.arbay.api.ArbayClient
import io.github.tieo.arbay.defaultServerUrl
import io.github.tieo.arbay.ui.AdaptiveFormSheet
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    client: ArbayClient,
    onDismiss: () -> Unit,
) {
    var serverUrl by remember { mutableStateOf(client.baseUrl) }
    var status by remember { mutableStateOf<Status>(Status.Idle) }
    val scope = rememberCoroutineScope()

    AdaptiveFormSheet(onDismiss = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
        ) {
            Text(
                "Settings",
                style = MaterialTheme.typography.titleLarge,
            )

            Spacer(Modifier.height(20.dp))

            OutlinedTextField(
                value = serverUrl,
                onValueChange = { serverUrl = it },
                label = { Text("Server URL") },
                placeholder = { Text(defaultServerUrl()) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                leadingIcon = { Icon(Icons.Outlined.Link, null) },
            )

            Spacer(Modifier.height(12.dp))

            // Status indicator
            when (val s = status) {
                is Status.Idle -> {}
                is Status.Testing -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text("Connecting...", style = MaterialTheme.typography.bodySmall)
                    }
                }
                is Status.Ok -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            Icons.Default.CheckCircle, null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp),
                        )
                        Text(
                            "Connected",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                is Status.Err -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            Icons.Default.Error, null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(16.dp),
                        )
                        Text(
                            s.msg,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = {
                        client.updateBaseUrl(serverUrl)
                        status = Status.Testing
                        scope.launch {
                            status = try {
                                client.getProducts()
                                Status.Ok
                            } catch (e: Exception) {
                                Status.Err(e.message ?: "Connection failed")
                            }
                        }
                    },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text("Save & Test")
                }

                OutlinedButton(
                    onClick = {
                        serverUrl = defaultServerUrl()
                        client.updateBaseUrl(serverUrl)
                        status = Status.Idle
                    },
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Icon(Icons.Default.Restore, null, modifier = Modifier.size(18.dp))
                }
            }

            Spacer(Modifier.height(20.dp))

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            Spacer(Modifier.height(16.dp))
            Text("Display", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(12.dp))

            val currencies = listOf("EUR", "USD", "GBP", "CHF")
            var displayCurrency by remember { mutableStateOf(DisplayCurrency.current) }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                currencies.forEach { cur ->
                    FilterChip(
                        selected = displayCurrency == cur,
                        onClick = {
                            displayCurrency = cur
                            DisplayCurrency.current = cur
                            scope.launch {
                                try { client.getExchangeRates() } catch (_: Exception) {}
                            }
                        },
                        label = { Text(cur) },
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            Spacer(Modifier.height(16.dp))
            Text("Crawler", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(12.dp))

            var maxPages by remember { mutableStateOf("5") }
            var sortByPrice by remember { mutableStateOf(true) }

            LaunchedEffect(Unit) {
                try {
                    val config = client.getCrawlerConfig()
                    maxPages = config.maxPages.toString()
                    sortByPrice = config.sortByPrice
                } catch (_: Exception) {}
            }

            OutlinedTextField(
                value = maxPages,
                onValueChange = { maxPages = it.filter { c -> c.isDigit() }.take(2) },
                label = { Text("Max pages per platform") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                supportingText = { Text("More pages = more results but slower") },
            )

            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Sort by price", style = MaterialTheme.typography.bodyMedium)
                Switch(checked = sortByPrice, onCheckedChange = { sortByPrice = it })
            }

            Spacer(Modifier.height(8.dp))

            Button(
                onClick = {
                    scope.launch {
                        try {
                            client.updateCrawlerConfig(maxPages.toIntOrNull() ?: 5, sortByPrice)
                        } catch (_: Exception) {}
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text("Save crawler settings")
            }

            Spacer(Modifier.height(20.dp))

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    "Version",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "1.0.0",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

private sealed class Status {
    data object Idle : Status()
    data object Testing : Status()
    data object Ok : Status()
    data class Err(val msg: String) : Status()
}
