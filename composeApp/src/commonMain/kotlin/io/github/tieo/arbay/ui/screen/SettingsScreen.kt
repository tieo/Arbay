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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.tieo.arbay.DisplayCurrency
import io.github.tieo.arbay.loadDeviceSettings
import io.github.tieo.arbay.saveDeviceSettings
import io.github.tieo.arbay.api.ArbayClient
import io.github.tieo.arbay.appSecrets
import io.github.tieo.arbay.debug.DebugSlice
import io.github.tieo.arbay.debug.debugJson
import io.github.tieo.arbay.defaultServerUrl
import io.github.tieo.arbay.model.NotificationSettings
import io.github.tieo.arbay.schedulePolling
import io.github.tieo.arbay.ui.AdaptiveFormSheet
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

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

    DebugSlice("settingsScreen") {
        val statusLabel = when (val s = status) {
            is Status.Idle -> "idle"
            is Status.Testing -> "testing"
            is Status.Ok -> "ok"
            is Status.Err -> "error: ${s.msg}"
        }
        debugJson.encodeToString(
            SettingsScreenSnapshot(
                serverUrl = serverUrl,
                connectionStatus = statusLabel,
                notifSettings = notifSettings,
                notifLoaded = notifLoaded,
            ),
        )
    }

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
            SettingSectionHeading("This device")

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
                                saveDeviceSettings(loadDeviceSettings() + ("currency" to cur))
                                scope.launch { try { client.getExchangeRates() } catch (_: Exception) {} }
                            },
                            label = { Text(cur) },
                        )
                    }
                }
            }

            SettingSectionHeading(
                "This server",
                "Shared with every device that uses it. Changing these changes them for all of them.",
            )

            // ── Crawler ─────────────────────────────────────────
            SettingSection(
                icon = Icons.Outlined.Tune,
                title = "Search depth",
                subtitle = "How hard each search digs. More depth means more results but slower searches.",
            ) {
                var maxResults by remember { mutableStateOf("60") }

                LaunchedEffect(Unit) {
                    try {
                        maxResults = client.getCrawlerConfig().maxResultsPerPlatform.toString()
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

                Button(
                    onClick = {
                        scope.launch {
                            try { client.updateCrawlerConfig((maxResults.toIntOrNull() ?: 60).coerceIn(10, 500)) } catch (_: Exception) {}
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                ) { Text("Save") }
            }

            // Two notifications, each one a thing that stops existing while you wait.
            SettingSection(
                icon = Icons.Outlined.Notifications,
                title = "Notifications",
                subtitle = "Two things can raise a notification on this phone, and both are gone if you " +
                    "wait for the app to be opened. Everything else the app finds is shown when you open it.",
            ) {
                AlertTypeCard(
                    icon = Icons.Default.NotificationsActive,
                    tint = MaterialTheme.colorScheme.error,
                    title = "Free item near you",
                    trigger = "someone gives away an item scoring at least " +
                        "${notifSettings.freeItemScorePct}% against what you have kept and skipped.",
                    why = "free items are taken within the hour, so the first person there gets it.",
                    enabled = notifSettings.freeItemAlerts,
                    onToggle = { notifSettings = notifSettings.copy(freeItemAlerts = it) },
                ) {
                    Text(
                        "Score it has to reach: ${notifSettings.freeItemScorePct}%",
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Slider(
                        value = notifSettings.freeItemScorePct.toFloat(),
                        onValueChange = { notifSettings = notifSettings.copy(freeItemScorePct = it.toInt()) },
                        valueRange = 70f..99f,
                        steps = 28,
                    )
                    Text(
                        "Lower means more notifications and more of them wrong.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Spacer(Modifier.height(10.dp))

                // A saved search interrupts through its own notification subfilters and nothing
                // else, so what it takes to be notified about a search is set on that search.
                Text(
                    "A saved search only notifies through the subfilters you set on it, under its " +
                        "own bell on Home. Everything else it finds waits on the bookmark.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(16.dp))

                Text("The phone asks the server this often", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(2.dp))
                Text(
                    "Nothing can reach you faster than this, and a shorter interval costs battery.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                val intervalOptions = listOf(15 to "15 min", 30 to "30 min", 60 to "1 hour", 120 to "2 hours", 240 to "4 hours")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    intervalOptions.forEach { (mins, label) ->
                        FilterChip(
                            selected = notifSettings.checkEveryMinutes == mins,
                            colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                                selectedLabelColor = MaterialTheme.colorScheme.primary,
                            ),
                            onClick = { notifSettings = notifSettings.copy(checkEveryMinutes = mins) },
                            label = { Text(label) },
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                Button(
                    onClick = {
                        scope.launch {
                            try {
                                client.updateNotificationSettings(notifSettings)
                                schedulePolling(notifSettings.checkEveryMinutes)
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
/** Names who a group of settings belongs to: this device, or the server every device shares. */
@Composable
private fun SettingSectionHeading(title: String, detail: String? = null) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 4.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        detail?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

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
    trigger: String,
    why: String,
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
            Spacer(Modifier.height(4.dp))
            AlertLine("Sent when", trigger)
            AlertLine("Because", why)
            if (enabled && content != null) {
                Spacer(Modifier.height(12.dp))
                content()
            }
        }
    }
}

/** One labelled sentence of an alert's contract, so the card says what fires it and what it costs
 *  to miss it rather than naming itself twice. */
@Composable
private fun AlertLine(label: String, text: String) {
    // Label and sentence are one paragraph, so a wrapped line starts at the left edge instead of
    // hanging under the label.
    val line = buildAnnotatedString {
        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append("$label ") }
        append(text)
    }
    Text(
        line,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Start,
        modifier = Modifier.fillMaxWidth().padding(top = 3.dp),
    )
}

@Serializable
private data class SettingsScreenSnapshot(
    val serverUrl: String,
    val connectionStatus: String,
    val notifSettings: NotificationSettings,
    val notifLoaded: Boolean,
)

private sealed class Status {
    data object Idle : Status()
    data object Testing : Status()
    data object Ok : Status()
    data class Err(val msg: String) : Status()
}
