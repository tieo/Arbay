package io.github.tieo.arbay.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.tieo.arbay.model.AutoFetchSettings
import io.github.tieo.arbay.model.NotificationSubfilter
import io.github.tieo.arbay.ui.AdaptiveSheet

/**
 * What one saved search does on its own, and what is worth a push notification about it.
 *
 * Two decisions live here and nowhere else: whether this search re-runs itself in the background
 * at all (off by default — a bookmark is a search someone chose to keep, not one they asked to be
 * crawled), and which of its own finds are worth interrupting for. A count on the bookmark answers
 * "did anything new turn up"; a subfilter answers "is any of it actually what I'm waiting for."
 */
@Composable
fun SearchAlertsSheet(
    productName: String,
    autoFetch: AutoFetchSettings,
    onAutoFetchChange: (AutoFetchSettings) -> Unit,
    subfilters: List<NotificationSubfilter>,
    onSubfiltersChange: (List<NotificationSubfilter>) -> Unit,
    onDismiss: () -> Unit,
) {
    var editing by remember { mutableStateOf<NotificationSubfilter?>(null) }
    var showNewSubfilter by remember { mutableStateOf(false) }

    AdaptiveSheet(onDismiss = onDismiss) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Alerts",
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                )
                Text(
                    productName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Close") }
        }

        Column(
            modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            AutoFetchSection(autoFetch = autoFetch, onChange = onAutoFetchChange)

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Notification subfilters", style = MaterialTheme.typography.labelLarge)
                Text(
                    if (autoFetch.enabled) {
                        "A push notification when a fresh find also matches one of these, named so you know why it arrived."
                    } else {
                        "Turn on auto-fetch above first — a subfilter only sees what auto-fetch finds."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (subfilters.isEmpty()) {
                    Text(
                        "No notification subfilters yet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 320.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(subfilters, key = { it.id }) { sf ->
                            SubfilterRow(
                                subfilter = sf,
                                onToggle = { on ->
                                    onSubfiltersChange(subfilters.map { if (it.id == sf.id) it.copy(enabled = on) else it })
                                },
                                onEdit = { editing = sf },
                                onDelete = { onSubfiltersChange(subfilters.filterNot { it.id == sf.id }) },
                            )
                        }
                    }
                }

                OutlinedButton(
                    onClick = { showNewSubfilter = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.Add, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Add notification subfilter")
                }
            }

            Spacer(Modifier.height(4.dp))
        }
    }

    if (showNewSubfilter) {
        SubfilterEditorDialog(
            initial = null,
            onDismiss = { showNewSubfilter = false },
            onSave = { sf -> onSubfiltersChange(subfilters + sf); showNewSubfilter = false },
        )
    }
    editing?.let { sf ->
        SubfilterEditorDialog(
            initial = sf,
            onDismiss = { editing = null },
            onSave = { updated ->
                onSubfiltersChange(subfilters.map { if (it.id == updated.id) updated else it })
                editing = null
            },
        )
    }
}

@Composable
private fun AutoFetchSection(autoFetch: AutoFetchSettings, onChange: (AutoFetchSettings) -> Unit) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Icon(
                    Icons.Outlined.NotificationsActive, null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "Auto-fetch this search",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    modifier = Modifier.weight(1f),
                )
                Switch(checked = autoFetch.enabled, onCheckedChange = { onChange(autoFetch.copy(enabled = it)) })
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "Off by default. On re-runs this search in the background so it keeps finding new " +
                    "stock without you reopening it — and, once on, a notification subfilter below " +
                    "actually has something to check.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (autoFetch.enabled) {
                Spacer(Modifier.height(12.dp))
                Text("Check every", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(6.dp))
                val options = listOf(
                    30 to "30 min", 60 to "1 hour", 180 to "3 hours",
                    360 to "6 hours", 720 to "12 hours", 1440 to "24 hours",
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    options.forEach { (mins, label) ->
                        FilterChip(
                            selected = autoFetch.intervalMinutes == mins,
                            onClick = { onChange(autoFetch.copy(intervalMinutes = mins)) },
                            label = { Text(label) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SubfilterRow(
    subfilter: NotificationSubfilter,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    subfilter.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    subfilterSummary(subfilter),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Switch(checked = subfilter.enabled, onCheckedChange = onToggle)
            IconButton(onClick = onEdit) {
                Icon(Icons.Outlined.Edit, "Edit", modifier = Modifier.size(18.dp))
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Outlined.Delete, "Remove", modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}

/** The criteria in one line, in the order they'd narrow a listing down: price, condition, words. */
private fun subfilterSummary(sf: NotificationSubfilter): String {
    val parts = buildList {
        when {
            sf.minPriceEur != null && sf.maxPriceEur != null -> add("€${sf.minPriceEur}–${sf.maxPriceEur}")
            sf.maxPriceEur != null -> add("up to €${sf.maxPriceEur}")
            sf.minPriceEur != null -> add("€${sf.minPriceEur}+")
        }
        when (sf.condition) {
            "NEW" -> add("new only")
            "USED" -> add("used only")
        }
        if (sf.mustContainAnyOf.isNotEmpty()) add("has " + sf.mustContainAnyOf.joinToString(" or "))
        if (sf.excludeKeywords.isNotEmpty()) add("not " + sf.excludeKeywords.joinToString(", "))
    }
    return if (parts.isEmpty()) "Any find in this search" else parts.joinToString(" · ")
}

@Composable
private fun SubfilterEditorDialog(
    initial: NotificationSubfilter?,
    onDismiss: () -> Unit,
    onSave: (NotificationSubfilter) -> Unit,
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var minPrice by remember { mutableStateOf(initial?.minPriceEur?.toString() ?: "") }
    var maxPrice by remember { mutableStateOf(initial?.maxPriceEur?.toString() ?: "") }
    var condition by remember { mutableStateOf(initial?.condition) }
    val mustContain = remember { (initial?.mustContainAnyOf ?: emptyList()).toMutableStateList() }
    val exclude = remember { (initial?.excludeKeywords ?: emptyList()).toMutableStateList() }
    var newTerm by remember { mutableStateOf("") }
    var newExclude by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Add notification subfilter" else "Edit notification subfilter") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    placeholder = { Text("e.g. \"under 8000\"") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = minPrice,
                        onValueChange = { minPrice = it.filter { c -> c.isDigit() } },
                        label = { Text("Min €") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = maxPrice,
                        onValueChange = { maxPrice = it.filter { c -> c.isDigit() } },
                        label = { Text("Max €") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(null to "Any", "NEW" to "New", "USED" to "Used").forEach { (value, label) ->
                        FilterChip(
                            selected = condition == value,
                            onClick = { condition = value },
                            label = { Text(label) },
                        )
                    }
                }
                Text("Must contain one of", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                mustContain.forEachIndexed { index, term ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = term, onValueChange = { mustContain[index] = it },
                            singleLine = true, modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { mustContain.removeAt(index) }) { Icon(Icons.Default.Close, "Remove") }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newTerm, onValueChange = { newTerm = it },
                        placeholder = { Text("e.g. \"automatik\"") },
                        singleLine = true, modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = {
                        val t = newTerm.trim()
                        if (t.isNotBlank() && t !in mustContain) mustContain.add(t)
                        newTerm = ""
                    }) { Icon(Icons.Default.Add, "Add") }
                }
                Text("Must not contain", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                exclude.forEachIndexed { index, term ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = term, onValueChange = { exclude[index] = it },
                            singleLine = true, modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { exclude.removeAt(index) }) { Icon(Icons.Default.Close, "Remove") }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newExclude, onValueChange = { newExclude = it },
                        placeholder = { Text("e.g. \"defekt\"") },
                        singleLine = true, modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = {
                        val t = newExclude.trim()
                        if (t.isNotBlank() && t !in exclude) exclude.add(t)
                        newExclude = ""
                    }) { Icon(Icons.Default.Add, "Add") }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = {
                    onSave(
                        NotificationSubfilter(
                            id = initial?.id ?: randomSubfilterId(),
                            name = name.trim(),
                            enabled = initial?.enabled ?: true,
                            minPriceEur = minPrice.toIntOrNull(),
                            maxPriceEur = maxPrice.toIntOrNull(),
                            condition = condition,
                            mustContainAnyOf = mustContain.toList(),
                            excludeKeywords = exclude.toList(),
                        ),
                    )
                },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun randomSubfilterId(): String {
    val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
    return (1..12).map { chars.random() }.joinToString("")
}
