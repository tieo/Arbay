package io.github.tieo.arbay.ui.screen
import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import coil3.compose.AsyncImage
import io.github.tieo.arbay.api.ArbayClient
import io.github.tieo.arbay.model.*
import io.github.tieo.arbay.openBrowser
import io.github.tieo.arbay.rememberCityDetector
import io.github.tieo.arbay.ui.AdaptiveSheet
import io.github.tieo.arbay.ui.viewmodel.FreeItemViewModel
import io.github.tieo.arbay.ui.viewmodel.PlatformStatus
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlin.math.absoluteValue
import kotlin.math.atan2
import kotlin.math.roundToInt
import kotlin.math.sqrt

@Composable
internal fun SavedSheet(
    profile: FreeItemProfile?,
    history: List<FeedbackHistoryItem>,
    historyLoading: Boolean,
    stats: FreeItemStats?,
    insights: FreeItemInsights?,
    viewModel: FreeItemViewModel,
    onUnsave: (String) -> Unit,
    onEditProfile: () -> Unit,
    onToggleTracking: (Boolean) -> Unit,
    onDismiss: () -> Unit = {},
) {
    val loved = remember(history) { history.filter { it.action == FeedbackAction.LOVE } }
    val disliked = remember(history) { history.filter { it.action == FeedbackAction.DISLIKE } }
    val passed = remember(history) { history.filter { it.action == FeedbackAction.PASS } }
    val models by viewModel.models.collectAsState()
    val arenaLeaderboard by viewModel.arenaLeaderboard.collectAsState()
    val rejectedItems by viewModel.rejectedItems.collectAsState()

    // Tab state: 0 = Saved, 1 = All History, 2 = Activity, 3 = Arena
    var selectedTab by remember { mutableStateOf(0) }
    var historyFilter by remember { mutableStateOf<FeedbackAction?>(null) }
    val filteredHistory = remember(history, historyFilter) {
        if (historyFilter == null) history else history.filter { it.action == historyFilter }
    }

    // Load arena data when tab selected
    LaunchedEffect(selectedTab) {
        if (selectedTab == 3) {
            viewModel.loadModels()
            viewModel.loadRejectedItems()
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        // Close button
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            IconButton(onClick = onDismiss) {
                Icon(Icons.Default.Close, contentDescription = "Close")
            }
        }
        // Tab row
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
            SegmentedButton(selected = selectedTab == 0, onClick = { selectedTab = 0 }, shape = SegmentedButtonDefaults.itemShape(0, 4)) {
                Text("Saved (${loved.size})", style = MaterialTheme.typography.labelMedium)
            }
            SegmentedButton(selected = selectedTab == 1, onClick = { selectedTab = 1 }, shape = SegmentedButtonDefaults.itemShape(1, 4)) {
                Text("History", style = MaterialTheme.typography.labelMedium)
            }
            SegmentedButton(selected = selectedTab == 2, onClick = { selectedTab = 2 }, shape = SegmentedButtonDefaults.itemShape(2, 4)) {
                Text("Activity", style = MaterialTheme.typography.labelMedium)
            }
            SegmentedButton(selected = selectedTab == 3, onClick = { selectedTab = 3 }, shape = SegmentedButtonDefaults.itemShape(3, 4)) {
                Text("Arena", style = MaterialTheme.typography.labelMedium)
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(bottom = 32.dp),
        ) {
            when (selectedTab) {
                // ── Saved tab ──────────────────────────────────────────
                0 -> {
                    if (historyLoading && loved.isEmpty()) {
                        item(key = "saved-loading") {
                            Box(Modifier.fillMaxWidth().height(80.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(modifier = Modifier.size(24.dp))
                            }
                        }
                    } else if (loved.isEmpty()) {
                        item(key = "saved-empty") {
                            Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 24.dp), contentAlignment = Alignment.Center) {
                                Text("Items you save will appear here", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    } else {
                        items(loved, key = { "saved-${it.listingId}" }) { item ->
                            SavedItemRow(item = item, onUnsave = { onUnsave(item.listingId) }, onOpen = { item.url?.let { openBrowser(it) } })
                        }
                    }
                }

                // ── History tab (all actions) ──────────────────────────
                1 -> {
                    item(key = "history-filters") {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            FilterChip(selected = historyFilter == null, onClick = { historyFilter = null }, label = { Text("All (${history.size})") })
                            FilterChip(selected = historyFilter == FeedbackAction.LOVE, onClick = { historyFilter = if (historyFilter == FeedbackAction.LOVE) null else FeedbackAction.LOVE }, label = { Text("Saved (${loved.size})") })
                            FilterChip(selected = historyFilter == FeedbackAction.DISLIKE, onClick = { historyFilter = if (historyFilter == FeedbackAction.DISLIKE) null else FeedbackAction.DISLIKE }, label = { Text("Disliked (${disliked.size})") })
                        }
                    }

                    if (historyLoading && history.isEmpty()) {
                        item(key = "history-loading") {
                            Box(Modifier.fillMaxWidth().height(80.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(modifier = Modifier.size(24.dp))
                            }
                        }
                    } else if (filteredHistory.isEmpty()) {
                        item(key = "history-empty") {
                            Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 24.dp), contentAlignment = Alignment.Center) {
                                Text("Your swipe history will appear here", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    } else {
                        items(filteredHistory, key = { "history-${it.listingId}" }) { item ->
                            HistoryItemRow(item = item, onOpen = { item.url?.let { openBrowser(it) } })
                        }
                    }
                }

                // ── Activity tab ───────────────────────────────────────
                2 -> {
                    // Profile summary
                    if (profile != null) {
                        item(key = "activity-profile") {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp), verticalAlignment = Alignment.Top) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("\"${profile.description}\"", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 3, overflow = TextOverflow.Ellipsis)
                                    if (profile.location != null) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(Icons.Outlined.LocationOn, null, modifier = Modifier.size(12.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                            Spacer(Modifier.width(2.dp))
                                            Text("${profile.location} · ${profile.radiusKm} km", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                }
                                IconButton(onClick = onEditProfile, modifier = Modifier.size(36.dp)) {
                                    Icon(Icons.Outlined.Edit, "Edit profile", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    }

                    // Stats
                    if (stats != null) {
                        item(key = "activity-stats") {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                            ) {
                                StatPill(Icons.Default.Favorite, Color(0xFFE91E63), stats.totalLoved, "saved")
                                StatPill(Icons.Outlined.Visibility, MaterialTheme.colorScheme.primary, stats.totalSeen, "reviewed")
                                StatPill(Icons.Default.Close, MaterialTheme.colorScheme.error, stats.totalDisliked, "disliked")
                            }
                        }
                    }

                    // Background tracking toggle
                    item(key = "activity-tracking") {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Background tracking", style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    "Automatically check for new matching items every 30 min",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(
                                checked = profile?.trackingEnabled ?: false,
                                onCheckedChange = { onToggleTracking(it) },
                            )
                        }
                    }
                }

                // ── Arena tab ─────────────────────────────────────────
                3 -> {
                    // Leaderboard header
                    item(key = "arena-header") {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Outlined.EmojiEvents, null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.tertiary)
                            Spacer(Modifier.width(8.dp))
                            Text("Model Arena", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.weight(1f))
                            TextButton(onClick = { viewModel.retrainModels() }) {
                                Icon(Icons.Outlined.Refresh, null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Retrain", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }

                    // Model cards
                    if (models.isEmpty()) {
                        item(key = "arena-empty") {
                            Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
                                Text("No models loaded yet", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    } else {
                        items(models, key = { "model-${it.id}" }) { model ->
                            val isActive = model.active == "true"
                            Card(
                                onClick = { viewModel.setActiveModel(model.id) },
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = if (isActive) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
                                        else MaterialTheme.colorScheme.surfaceContainerHigh,
                                ),
                                shape = RoundedCornerShape(12.dp),
                            ) {
                                Column(modifier = Modifier.padding(14.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        if (isActive) {
                                            Icon(Icons.Default.RadioButtonChecked, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                                        } else {
                                            Icon(Icons.Default.RadioButtonUnchecked, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.outlineVariant)
                                        }
                                        Spacer(Modifier.width(8.dp))
                                        Text(model.name, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal))
                                        Spacer(Modifier.weight(1f))
                                        if (model.trainable == "true") {
                                            AssistChip(
                                                onClick = {},
                                                label = { Text("trainable", style = MaterialTheme.typography.labelSmall) },
                                                shape = RoundedCornerShape(20.dp),
                                                modifier = Modifier.height(22.dp),
                                                border = null,
                                                colors = AssistChipDefaults.assistChipColors(
                                                    containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f),
                                                ),
                                            )
                                        }
                                    }
                                    Spacer(Modifier.height(8.dp))
                                    val preds = model.totalPredictions.toIntOrNull() ?: 0
                                    if (preds > 0) {
                                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                            Column {
                                                Text("Accuracy", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                Text(model.accuracy, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
                                            }
                                            Column {
                                                Text("Precision", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                Text(model.precision, style = MaterialTheme.typography.titleSmall)
                                            }
                                            Column {
                                                Text("Recall", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                Text(model.recall, style = MaterialTheme.typography.titleSmall)
                                            }
                                            Column {
                                                Text("Separation", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                Text(model.separation, style = MaterialTheme.typography.titleSmall)
                                            }
                                        }
                                        Spacer(Modifier.height(4.dp))
                                        val fn = model.falseNegatives.toIntOrNull() ?: 0
                                        Text("$preds predictions · $fn false negatives", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    } else {
                                        Text("No predictions yet, swipe some items first", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    }

                    // Rejected items section
                    item(key = "rejected-header") {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Outlined.Visibility, null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text("Rejected Items", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
                                Text("Items the model scored low, check for false negatives", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }

                    if (rejectedItems.isEmpty()) {
                        item(key = "rejected-empty") {
                            Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
                                Text("No rejected items yet", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    } else {
                        items(rejectedItems, key = { "rejected-${it.listingId}" }) { item ->
                            Surface(
                                onClick = { openBrowser(item.url) },
                                color = Color.Transparent,
                            ) {
                                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier.width(4.dp).height(48.dp).clip(RoundedCornerShape(2.dp))
                                            .background(MaterialTheme.colorScheme.outlineVariant),
                                    )
                                    Spacer(Modifier.width(12.dp))
                                    Box(
                                        modifier = Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        if (item.imageUrl != null) {
                                            AsyncImage(model = item.imageUrl, contentDescription = item.title, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                                        } else {
                                            Icon(Icons.Default.CardGiftcard, null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.outlineVariant)
                                        }
                                    }
                                    Spacer(Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(item.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                            Text(
                                                "score: ${"%.2f".format(item.relevanceScore ?: 0.0)}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.error,
                                            )
                                            item.locationText?.let {
                                                Text(" · $it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ═════════════════════════════════════════════════════════════════════════════
// ── SAVED ITEM ROW ──────────────────────────────────────────────────────────
// ═════════════════════════════════════════════════════════════════════════════

@Composable
private fun SavedItemRow(item: FeedbackHistoryItem, onUnsave: () -> Unit, onOpen: () -> Unit) {
    Surface(onClick = onOpen, color = Color.Transparent) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(56.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center,
            ) {
                if (item.imageUrl != null) {
                    AsyncImage(model = item.imageUrl, contentDescription = item.title, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                } else {
                    Icon(Icons.Default.CardGiftcard, null, modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.outlineVariant)
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(item.title, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium), maxLines = 2, overflow = TextOverflow.Ellipsis)
                val subtitle = buildList {
                    item.locationText?.let { add(it) }
                    item.timestamp?.let { add("saved ${formatRelativeTime(it)}") }
                }.joinToString(" · ")
                if (subtitle.isNotBlank()) {
                    Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            IconButton(onClick = onUnsave, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.Close, "Remove", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// ═════════════════════════════════════════════════════════════════════════════
// ── HISTORY ITEM ROW ────────────────────────────────────────────────────────
// ═════════════════════════════════════════════════════════════════════════════

@Composable
private fun HistoryItemRow(item: FeedbackHistoryItem, onOpen: () -> Unit) {
    val actionColor = when (item.action) {
        FeedbackAction.LOVE -> Color(0xFFE91E63)
        FeedbackAction.LIKE -> Color(0xFF4CAF50)
        FeedbackAction.DISLIKE -> MaterialTheme.colorScheme.error
        FeedbackAction.PASS -> MaterialTheme.colorScheme.outlineVariant
    }
    val actionIcon = when (item.action) {
        FeedbackAction.LOVE -> Icons.Default.Favorite
        FeedbackAction.LIKE -> Icons.Default.ThumbUp
        FeedbackAction.DISLIKE -> Icons.Default.ThumbDown
        FeedbackAction.PASS -> Icons.Default.SwipeLeft
    }
    val actionLabel = when (item.action) {
        FeedbackAction.LOVE -> "saved"
        FeedbackAction.LIKE -> "liked"
        FeedbackAction.DISLIKE -> "disliked"
        FeedbackAction.PASS -> "passed"
    }

    Surface(onClick = onOpen, color = Color.Transparent) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            // Action indicator strip
            Box(
                modifier = Modifier.width(4.dp).height(48.dp).clip(RoundedCornerShape(2.dp)).background(actionColor),
            )
            Spacer(Modifier.width(12.dp))
            // Thumbnail
            Box(
                modifier = Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center,
            ) {
                if (item.imageUrl != null) {
                    AsyncImage(model = item.imageUrl, contentDescription = item.title, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                } else {
                    Icon(Icons.Default.CardGiftcard, null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.outlineVariant)
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(item.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(actionIcon, null, modifier = Modifier.size(12.dp), tint = actionColor)
                    val subtitle = buildList {
                        add(actionLabel)
                        item.locationText?.let { add(it) }
                        item.timestamp?.let { add(formatRelativeTime(it)) }
                    }.joinToString(" · ")
                    Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

// ═════════════════════════════════════════════════════════════════════════════
// ── STAT PILL ───────────────────────────────────────────────────────────────
// ═════════════════════════════════════════════════════════════════════════════

@Composable
private fun StatPill(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconTint: Color,
    value: Int,
    label: String,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(icon, null, modifier = Modifier.size(14.dp), tint = iconTint)
        Text("$value $label", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ═════════════════════════════════════════════════════════════════════════════
// ── PROFILE EDITOR ──────────────────────────────────────────────────────────
// ═════════════════════════════════════════════════════════════════════════════
