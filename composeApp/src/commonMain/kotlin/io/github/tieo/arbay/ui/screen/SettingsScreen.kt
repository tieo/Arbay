package io.github.tieo.arbay.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.tieo.arbay.DisplayCurrency
import io.github.tieo.arbay.api.ArbayClient
import io.github.tieo.arbay.defaultServerUrl
import io.github.tieo.arbay.model.NotificationSettings
import io.github.tieo.arbay.schedulePolling
import io.github.tieo.arbay.ui.AdaptiveFormSheet
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    client: ArbayClient,
    onDismiss: () -> Unit,
    onServerUrlChanged: () -> Unit = {},
) {
    var serverUrl by remember { mutableStateOf(client.baseUrl) }
    var status by remember { mutableStateOf<Status>(Status.Idle) }
    val scope = rememberCoroutineScope()

    // Notification settings
    var notifSettings by remember { mutableStateOf(NotificationSettings()) }
    var notifLoaded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        try {
            notifSettings = client.getNotificationSettings()
            notifLoaded = true
        } catch (_: Exception) {}
    }

    AdaptiveFormSheet(onDismiss = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
        ) {
            // ── Close button ──
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Close") }
            }

            Text("Settings", style = MaterialTheme.typography.titleLarge)

            Spacer(Modifier.height(20.dp))

            // ── Server URL ──────────────────────────────────────
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

            when (val s = status) {
                is Status.Idle -> {}
                is Status.Testing -> {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text("Connecting...", style = MaterialTheme.typography.bodySmall)
                    }
                }
                is Status.Ok -> {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                        Text("Connected", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
                is Status.Err -> {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.Error, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                        Text(s.msg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        client.updateBaseUrl(serverUrl)
                        status = Status.Testing
                        scope.launch {
                            status = try {
                                client.getProducts()
                                onServerUrlChanged()
                                Status.Ok
                            } catch (e: Exception) {
                                Status.Err(e.message ?: "Connection failed")
                            }
                        }
                    },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                ) { Text("Save & Test") }

                OutlinedButton(
                    onClick = {
                        serverUrl = defaultServerUrl()
                        client.updateBaseUrl(serverUrl)
                        status = Status.Idle
                    },
                    shape = RoundedCornerShape(12.dp),
                ) { Icon(Icons.Default.Restore, null, modifier = Modifier.size(18.dp)) }
            }

            Spacer(Modifier.height(20.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            // ── Display ──────────────────────────────────────────
            Spacer(Modifier.height(16.dp))
            Text("Display", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(12.dp))

            val currencies = listOf("EUR", "USD", "GBP", "CHF")
            var displayCurrency by remember { mutableStateOf(DisplayCurrency.current) }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                currencies.forEach { cur ->
                    FilterChip(
                        selected = displayCurrency == cur,
                        onClick = {
                            displayCurrency = cur
                            DisplayCurrency.current = cur
                            scope.launch { try { client.getExchangeRates() } catch (_: Exception) {} }
                        },
                        label = { Text(cur) },
                    )
                }
            }

            Spacer(Modifier.height(20.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            // ── Crawler ──────────────────────────────────────────
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

            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Sort by price", style = MaterialTheme.typography.bodyMedium)
                Switch(checked = sortByPrice, onCheckedChange = { sortByPrice = it })
            }

            Spacer(Modifier.height(8.dp))

            Button(
                onClick = {
                    scope.launch {
                        try { client.updateCrawlerConfig(maxPages.toIntOrNull() ?: 5, sortByPrice) } catch (_: Exception) {}
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            ) { Text("Save crawler settings") }

            Spacer(Modifier.height(20.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            // ══════════════════════════════════════════════════════
            // ── Notifications ────────────────────────────────────
            // ══════════════════════════════════════════════════════
            Spacer(Modifier.height(16.dp))
            Text("Free Item Notifications", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            Text(
                "Background polling checks for new items. Android notification channels let you control each type independently in system settings.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))

            // Poll interval
            Text("Poll interval", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(4.dp))
            val intervalOptions = listOf(15 to "15 min", 30 to "30 min", 60 to "1 hour", 120 to "2 hours", 240 to "4 hours")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                intervalOptions.forEach { (mins, label) ->
                    FilterChip(
                        selected = notifSettings.pollIntervalMinutes == mins,
                        onClick = { notifSettings = notifSettings.copy(pollIntervalMinutes = mins) },
                        label = { Text(label) },
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            // ── Urgent matches ──
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)),
            ) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.NotificationsActive, null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.width(8.dp))
                        Text("Urgent Matches", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
                        Spacer(Modifier.weight(1f))
                        Switch(checked = notifSettings.urgentEnabled, onCheckedChange = { notifSettings = notifSettings.copy(urgentEnabled = it) })
                    }
                    Text("Instant notification for very high matches", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

                    if (notifSettings.urgentEnabled) {
                        Spacer(Modifier.height(12.dp))
                        Text("Threshold: ${notifSettings.urgentThresholdPct}%", style = MaterialTheme.typography.labelMedium)
                        Slider(
                            value = notifSettings.urgentThresholdPct.toFloat(),
                            onValueChange = { notifSettings = notifSettings.copy(urgentThresholdPct = it.toInt()) },
                            valueRange = 70f..99f,
                            steps = 28,
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // ── Digest ──
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)),
            ) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Summarize, null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text("Match Digest", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
                        Spacer(Modifier.weight(1f))
                        Switch(checked = notifSettings.digestEnabled, onCheckedChange = { notifSettings = notifSettings.copy(digestEnabled = it) })
                    }
                    Text("Periodic summary of new matching items", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

                    if (notifSettings.digestEnabled) {
                        Spacer(Modifier.height(12.dp))

                        Text("Digest every:", style = MaterialTheme.typography.labelMedium)
                        Spacer(Modifier.height(4.dp))
                        val digestOptions = listOf(12 to "12h", 24 to "Daily", 48 to "Every 2 days", 72 to "Every 3 days", 168 to "Weekly")
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            digestOptions.forEach { (hours, label) ->
                                FilterChip(
                                    selected = notifSettings.digestIntervalHours == hours,
                                    onClick = { notifSettings = notifSettings.copy(digestIntervalHours = hours) },
                                    label = { Text(label) },
                                )
                            }
                        }

                        Spacer(Modifier.height(12.dp))
                        Text("Include items above: ${notifSettings.digestThresholdPct}%", style = MaterialTheme.typography.labelMedium)
                        Slider(
                            value = notifSettings.digestThresholdPct.toFloat(),
                            onValueChange = { notifSettings = notifSettings.copy(digestThresholdPct = it.toInt()) },
                            valueRange = 20f..80f,
                            steps = 11,
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // ── Novel items ──
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.3f)),
            ) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.AutoAwesome, null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.tertiary)
                        Spacer(Modifier.width(8.dp))
                        Text("Novel Items", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
                        Spacer(Modifier.weight(1f))
                        Switch(checked = notifSettings.novelEnabled, onCheckedChange = { notifSettings = notifSettings.copy(novelEnabled = it) })
                    }
                    Text("Items unlike anything you've seen — rare/new stuff the model can't predict", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Spacer(Modifier.height(16.dp))

            Button(
                onClick = {
                    scope.launch {
                        try {
                            client.updateNotificationSettings(notifSettings)
                            schedulePolling(notifSettings.pollIntervalMinutes)
                        } catch (_: Exception) {}
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            ) { Text("Save notification settings") }

            Spacer(Modifier.height(20.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            Spacer(Modifier.height(12.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Version", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("1.0.0", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
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
