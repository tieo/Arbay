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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.tieo.arbay.DisplayCurrency
import io.github.tieo.arbay.api.ArbayClient
import io.github.tieo.arbay.appSecrets
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
    // The default is the .env-configured server (secret.properties), falling back to the
    // built-in LAN host only when no secret is baked in.
    val configuredDefault = remember { appSecrets().serverUrl ?: defaultServerUrl() }
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
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
        ) {
            // ── Header ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Settings", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Close") }
            }

            Spacer(Modifier.height(12.dp))

            // ── Server ──────────────────────────────────────────
            SettingSection(
                icon = Icons.Outlined.Dns,
                title = "Server",
                subtitle = "Where the app fetches listings from. Defaults to the configured server; change only for local testing.",
            ) {
                OutlinedTextField(
                    value = serverUrl,
                    onValueChange = { serverUrl = it },
                    label = { Text("Server URL") },
                    placeholder = { Text(configuredDefault) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    leadingIcon = { Icon(Icons.Outlined.Link, null) },
                )

                when (val s = status) {
                    is Status.Idle -> {}
                    is Status.Testing -> {
                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Text("Connecting...", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    is Status.Ok -> {
                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                            Text("Connected", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    is Status.Err -> {
                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.Error, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                            Text(s.msg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

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
                            serverUrl = configuredDefault
                            client.updateBaseUrl(serverUrl)
                            status = Status.Idle
                        },
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Icon(Icons.Default.Restore, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Default")
                    }
                }
            }

            // ── Display ─────────────────────────────────────────
            SettingSection(
                icon = Icons.Outlined.Palette,
                title = "Display",
                subtitle = "Currency prices are shown in. Listings from other markets are converted for you.",
            ) {
                val currencies = listOf("EUR", "USD", "GBP", "CHF")
                var displayCurrency by remember { mutableStateOf(DisplayCurrency.current) }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    currencies.forEach { cur ->
                        FilterChip(
                            selected = displayCurrency == cur,
                            colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                                selectedLabelColor = MaterialTheme.colorScheme.primary,
                            ),
                            onClick = {
                                displayCurrency = cur
                                DisplayCurrency.current = cur
                                scope.launch { try { client.getExchangeRates() } catch (_: Exception) {} }
                            },
                            label = { Text(cur) },
                        )
                    }
                }
            }

            // ── Crawler ─────────────────────────────────────────
            SettingSection(
                icon = Icons.Outlined.Tune,
                title = "Search depth",
                subtitle = "How hard each search digs. More depth means more results but slower searches.",
            ) {
                var maxResults by remember { mutableStateOf("60") }
                var sortByPrice by remember { mutableStateOf(true) }

                LaunchedEffect(Unit) {
                    try {
                        val config = client.getCrawlerConfig()
                        maxResults = config.maxResultsPerPlatform.toString()
                        sortByPrice = config.sortByPrice
                    } catch (_: Exception) {}
                }

                OutlinedTextField(
                    value = maxResults,
                    onValueChange = { maxResults = it.filter { c -> c.isDigit() }.take(4) },
                    label = { Text("Max results per marketplace") },
                    supportingText = { Text("Each marketplace is scanned until it reaches this many listings") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                )

                Spacer(Modifier.height(12.dp))

                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        Text("Sort by price", style = MaterialTheme.typography.bodyMedium)
                        Text("Cheapest first across all markets", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = sortByPrice, onCheckedChange = { sortByPrice = it })
                }

                Spacer(Modifier.height(12.dp))

                Button(
                    onClick = {
                        scope.launch {
                            try { client.updateCrawlerConfig((maxResults.toIntOrNull() ?: 60).coerceIn(10, 500), sortByPrice) } catch (_: Exception) {}
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                ) { Text("Save") }
            }

            // ── Free-item notifications ─────────────────────────
            SettingSection(
                icon = Icons.Outlined.Notifications,
                title = "Free-item alerts",
                subtitle = "The app checks for new free items in the background and can notify you. Each alert type is a separate Android channel you can also mute in system settings.",
            ) {
                Text("Check for new items every", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(6.dp))
                val intervalOptions = listOf(15 to "15 min", 30 to "30 min", 60 to "1 hour", 120 to "2 hours", 240 to "4 hours")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    intervalOptions.forEach { (mins, label) ->
                        FilterChip(
                            selected = notifSettings.pollIntervalMinutes == mins,
                            colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                                selectedLabelColor = MaterialTheme.colorScheme.primary,
                            ),
                            onClick = { notifSettings = notifSettings.copy(pollIntervalMinutes = mins) },
                            label = { Text(label) },
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                // Urgent
                AlertTypeCard(
                    icon = Icons.Default.NotificationsActive,
                    tint = MaterialTheme.colorScheme.error,
                    title = "Instant alerts",
                    explanation = "Ping me the moment a free item looks like a very strong match, so I can grab it first.",
                    enabled = notifSettings.urgentEnabled,
                    onToggle = { notifSettings = notifSettings.copy(urgentEnabled = it) },
                ) {
                    Text("Only alert above: ${notifSettings.urgentThresholdPct}% match", style = MaterialTheme.typography.labelMedium)
                    Slider(
                        value = notifSettings.urgentThresholdPct.toFloat(),
                        onValueChange = { notifSettings = notifSettings.copy(urgentThresholdPct = it.toInt()) },
                        valueRange = 70f..99f,
                        steps = 28,
                    )
                }

                Spacer(Modifier.height(10.dp))

                // Digest
                AlertTypeCard(
                    icon = Icons.Default.Summarize,
                    tint = MaterialTheme.colorScheme.primary,
                    title = "Summary digest",
                    explanation = "Instead of pinging per item, collect new matches and send one recap on a schedule.",
                    enabled = notifSettings.digestEnabled,
                    onToggle = { notifSettings = notifSettings.copy(digestEnabled = it) },
                ) {
                    Text("Send a recap every", style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.height(6.dp))
                    val digestOptions = listOf(12 to "12h", 24 to "Daily", 48 to "2 days", 72 to "3 days", 168 to "Weekly")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        digestOptions.forEach { (hours, label) ->
                            FilterChip(
                                selected = notifSettings.digestIntervalHours == hours,
                                colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                                    selectedLabelColor = MaterialTheme.colorScheme.primary,
                                ),
                                onClick = { notifSettings = notifSettings.copy(digestIntervalHours = hours) },
                                label = { Text(label) },
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Text("Include items above: ${notifSettings.digestThresholdPct}% match", style = MaterialTheme.typography.labelMedium)
                    Slider(
                        value = notifSettings.digestThresholdPct.toFloat(),
                        onValueChange = { notifSettings = notifSettings.copy(digestThresholdPct = it.toInt()) },
                        valueRange = 20f..80f,
                        steps = 11,
                    )
                }

                Spacer(Modifier.height(10.dp))

                // Novel
                AlertTypeCard(
                    icon = Icons.Default.AutoAwesome,
                    tint = MaterialTheme.colorScheme.tertiary,
                    title = "Surprising finds",
                    explanation = "Rare or unusual items the model can't confidently score — the odd stuff worth a look.",
                    enabled = notifSettings.novelEnabled,
                    onToggle = { notifSettings = notifSettings.copy(novelEnabled = it) },
                    content = null,
                )

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
                ) { Text("Save alerts") }
            }

            Spacer(Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Version", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("1.0.0", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

/** A titled, bordered group with a one-line plain-language explanation of what it controls. */
@Composable
private fun SettingSection(
    icon: ImageVector,
    title: String,
    subtitle: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text(title, style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(4.dp))
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(14.dp))
            content()
        }
    }
}

/** One free-item alert type: a switch with a plain explanation, revealing its options when on. */
@Composable
private fun AlertTypeCard(
    icon: ImageVector,
    tint: androidx.compose.ui.graphics.Color,
    title: String,
    explanation: String,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    content: (@Composable ColumnScope.() -> Unit)? = null,
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Icon(icon, null, modifier = Modifier.size(20.dp), tint = tint)
                Spacer(Modifier.width(8.dp))
                Text(title, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.weight(1f))
                Switch(checked = enabled, onCheckedChange = onToggle)
            }
            Text(explanation, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (enabled && content != null) {
                Spacer(Modifier.height(12.dp))
                content()
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
