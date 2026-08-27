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
import io.github.tieo.arbay.debug.DebugSlice
import io.github.tieo.arbay.debug.debugJson
import io.github.tieo.arbay.model.*
import io.github.tieo.arbay.openBrowser
import io.github.tieo.arbay.rememberCityDetector
import io.github.tieo.arbay.ui.AdaptiveSheet
import io.github.tieo.arbay.ui.viewmodel.FreeItemViewModel
import io.github.tieo.arbay.ui.viewmodel.PlatformStatus
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlin.math.absoluteValue
import kotlin.math.atan2
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** The profile editor and saved-matches sheet are local to this screen, not the view model, so
 *  the debug dump would otherwise show none of what is currently being typed or which is open. */
@Serializable
private data class FreeItemsScreenSnapshot(
    val editingProfile: Boolean,
    val profileDraft: String,
    val locationDraft: String,
    val radiusDraft: Float,
    val showSavedSheet: Boolean,
    val buttonAction: String?,
)

// ── Main Sheet ──────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FreeItemsSheet(
    viewModel: FreeItemViewModel,
    onDismiss: () -> Unit,
) {
    val profile by viewModel.profile.collectAsState()
    val listings by viewModel.listings.collectAsState()
    val loading by viewModel.loading.collectAsState()
    val loadingMore by viewModel.loadingMore.collectAsState()
    val error by viewModel.error.collectAsState()
    val platformStatus by viewModel.platformStatus.collectAsState()
    val insights by viewModel.insights.collectAsState()
    val dismissedIds by viewModel.dismissedIds.collectAsState()
    val history by viewModel.history.collectAsState()
    val historyLoading by viewModel.historyLoading.collectAsState()
    val stats by viewModel.stats.collectAsState()
    val currentRadiusKm by viewModel.currentRadiusKm.collectAsState()
    val newMatches by viewModel.newMatches.collectAsState()
    val lastAction by viewModel.lastAction.collectAsState()
    val undoAnimDirection by viewModel.undoAnimDirection.collectAsState()
    // Button action trigger, set by the bottom bar and consumed by the SwipeCardStack fly-out animation
    var buttonAction by remember { mutableStateOf<String?>(null) }

    var editingProfile by remember { mutableStateOf(profile == null) }
    var profileDraft by remember(profile) { mutableStateOf(profile?.description ?: "") }
    var locationDraft by remember(profile) { mutableStateOf(profile?.location ?: "") }
    var radiusDraft by remember(profile) { mutableFloatStateOf((profile?.radiusKm ?: 30).toFloat()) }
    var showSavedSheet by remember { mutableStateOf(false) }

    DebugSlice("freeItemsScreen") {
        debugJson.encodeToString(
            FreeItemsScreenSnapshot(
                editingProfile = editingProfile,
                profileDraft = profileDraft,
                locationDraft = locationDraft,
                radiusDraft = radiusDraft,
                showSavedSheet = showSavedSheet,
                buttonAction = buttonAction,
            ),
        )
    }

    // A fresh open starts without a stale error banner from an earlier search this app session.
    LaunchedEffect(Unit) { viewModel.clearError() }

    // Auto-search when profile loads (only if location is set)
    LaunchedEffect(profile) {
        val p = profile
        if (p != null && !p.location.isNullOrBlank() && listings.isEmpty() && !loading) {
            editingProfile = false
            viewModel.search()
        } else if (p != null && p.location.isNullOrBlank()) {
            editingProfile = true // Force profile editor open if no location
            viewModel.clearError()
        }
    }

    val visibleListings = remember(listings, dismissedIds) {
        listings.filter { it.id !in dismissedIds }
    }

    // The current card is always visibleListings[0]; dismissing an item removes it
    // from the list and the next one becomes [0], so no index needs tracking.

    // Prefetch more items when running low
    LaunchedEffect(visibleListings.size) {
        viewModel.onCardViewed(visibleListings.size, 0)
    }

    val lovedCount = stats?.totalLoved ?: history.count { it.action == FeedbackAction.LOVE }
    val displayRadius = currentRadiusKm ?: profile?.radiusKm

    AdaptiveSheet(onDismiss = onDismiss) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {

                // ── Compact Header ──────────────────────────────────────
                SwipeHeader(
                    profile = profile,
                    editingProfile = editingProfile,
                    loading = loading || loadingMore,
                    // While the profile editor is open, the form itself guides the user; a search
                    // error banner (e.g. "set a location") would just be noise on top of it.
                    error = if (editingProfile) null else error,
                    displayRadius = displayRadius,
                    currentRadiusKm = currentRadiusKm,
                    currentIndex = if (visibleListings.isNotEmpty()) 1 else 0,
                    totalVisible = visibleListings.size,
                    onRefresh = { viewModel.search() },
                    onDismiss = onDismiss,
                )

                // ── New Matches Banner ──────────────────────────────────
                AnimatedVisibility(
                    visible = newMatches.isNotEmpty(),
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut(),
                ) {
                    Surface(
                        onClick = {
                            showSavedSheet = true
                            viewModel.dismissNewMatches()
                        },
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Outlined.NotificationsActive, null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "${newMatches.size} new match${if (newMatches.size > 1) "es" else ""} found!",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.weight(1f),
                            )
                            Icon(Icons.Default.ChevronRight, null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                    }
                }

                // ── Profile Editor ──────────────────────────────────────
                AnimatedVisibility(
                    visible = editingProfile,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut(),
                ) {
                    ProfileEditor(
                        isFirstTime = profile == null,
                        descriptionDraft = profileDraft,
                        locationDraft = locationDraft,
                        radiusDraft = radiusDraft,
                        onDescriptionChange = { profileDraft = it },
                        onLocationChange = { locationDraft = it },
                        onRadiusChange = { radiusDraft = it },
                        onCancel = if (profile?.location.isNullOrBlank() == false) ({ editingProfile = false }) else null,
                        onSave = {
                            if (locationDraft.isNotBlank()) {
                                viewModel.saveProfile(
                                    description = profileDraft.trim(),
                                    location = locationDraft.trim(),
                                    radiusKm = radiusDraft.roundToInt(),
                                )
                                editingProfile = false
                                viewModel.search()
                            }
                        },
                    )
                }

                // ── Tinder Card Stack ───────────────────────────────────
                Box(modifier = Modifier.weight(1f)) {
                    when {
                        !editingProfile && visibleListings.isEmpty() && !loading && !loadingMore && profile != null -> {
                            EmptyState(
                                hasLocation = profile?.location != null,
                                searchFailed = error != null,
                                onRetry = { viewModel.search() },
                            )
                        }

                        // Covers loadingMore too: when the deck empties while a prefetch is in
                        // flight, a spinner is truthful where "No free items found" is not.
                        !editingProfile && (loading || loadingMore) && visibleListings.isEmpty() -> {
                            LoadingState(platformStatus = platformStatus)
                        }

                        !editingProfile && visibleListings.isNotEmpty() -> {
                            val currentListing = visibleListings.first()
                            SwipeCardStack(
                                listing = currentListing,
                                nextListing = visibleListings.getOrNull(1),
                                undoDirection = undoAnimDirection,
                                onUndoAnimDone = { viewModel.clearUndoAnimation() },
                                buttonAction = buttonAction,
                                onButtonActionConsumed = { buttonAction = null },
                                loadingMore = loadingMore,
                                onSwipeRight = { viewModel.love(currentListing) },
                                onSwipeLeft = { viewModel.pass(currentListing) },
                                onSwipeDown = { viewModel.like(currentListing) },
                                onDislike = { viewModel.dislike(currentListing) },
                                onOpen = { openBrowser(currentListing.url) },
                                onMoreLikeThis = { viewModel.moreLikeThis(currentListing) },
                            )
                        }
                    }
                }

                // ── Bottom Bar ──────────────────────────────────────────
                if (profile != null && !editingProfile) {
                    SwipeBottomBar(
                        lovedCount = lovedCount,
                        totalReviewed = stats?.totalSeen ?: 0,
                        hasCurrentCard = visibleListings.isNotEmpty(),
                        canUndo = lastAction != null,
                        onUndo = { viewModel.undoLast() },
                        onSavedClick = { showSavedSheet = true },
                        onDislike = { buttonAction = "dislike" },
                        onLike = { buttonAction = "down" },
                        onLove = { buttonAction = "right" },
                    )
                }
            }

            // ── Saved & Activity Sheet ──────────────────────────────────
            if (showSavedSheet) {
                Dialog(
                    onDismissRequest = { showSavedSheet = false },
                    properties = io.github.tieo.arbay.ui.fullBleedDialogProperties(),
                ) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                    ) {
                        Column(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars)) {
                        SavedSheet(
                            profile = profile,
                            history = history,
                            historyLoading = historyLoading,
                            stats = stats,
                            insights = insights,
                            viewModel = viewModel,
                            onUnsave = { viewModel.undoFeedback(it) },
                            onEditProfile = {
                                showSavedSheet = false
                                editingProfile = true
                            },
                            onToggleTracking = { viewModel.toggleTracking(it) },
                            onDismiss = { showSavedSheet = false },
                        )
                        }
                    }
                }
            }
        }
    }
}

// ═════════════════════════════════════════════════════════════════════════════
// ── SWIPE HEADER ────────────────────────────────────────────────────────────
// ═════════════════════════════════════════════════════════════════════════════

@Composable
private fun ProfileEditor(
    isFirstTime: Boolean,
    descriptionDraft: String,
    locationDraft: String,
    radiusDraft: Float,
    onDescriptionChange: (String) -> Unit,
    onLocationChange: (String) -> Unit,
    onRadiusChange: (Float) -> Unit,
    onCancel: (() -> Unit)?,
    onSave: () -> Unit,
) {
    var detectingLocation by remember { mutableStateOf(false) }
    val detectCity = rememberCityDetector { city ->
        detectingLocation = false
        if (city != null) onLocationChange(city)
    }

    Card(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                if (isFirstTime) "Set your area and start swiping" else "Update your interests",
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Just set a location to start, then swipe through free items and the model learns what you want. A description is optional and only nudges the early ranking.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = descriptionDraft,
                onValueChange = onDescriptionChange,
                label = { Text("What excites you (optional)", style = MaterialTheme.typography.labelSmall) },
                placeholder = { Text("e.g. electronics, furniture, cycling gear, tools", style = MaterialTheme.typography.bodySmall) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp),
                shape = RoundedCornerShape(12.dp),
                maxLines = 5,
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = locationDraft,
                onValueChange = onLocationChange,
                placeholder = { Text("Your city or zip (e.g. Bremen, 28195)", style = MaterialTheme.typography.bodySmall) },
                label = { Text("Location", style = MaterialTheme.typography.labelSmall) },
                leadingIcon = { Icon(Icons.Outlined.LocationOn, null, modifier = Modifier.size(18.dp)) },
                trailingIcon = {
                    if (detectingLocation) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        IconButton(onClick = { detectingLocation = true; detectCity() }) {
                            Icon(Icons.Default.MyLocation, "Detect", modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                singleLine = true,
            )

            // Radius slider
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Explore, null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(8.dp))
                Text(
                    "Radius: ${radiusDraft.roundToInt()} km",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.width(100.dp),
                )
                Slider(
                    value = radiusDraft,
                    onValueChange = onRadiusChange,
                    valueRange = 5f..500f,
                    steps = 0,
                    modifier = Modifier.weight(1f),
                    colors = SliderDefaults.colors(
                        thumbColor = MaterialTheme.colorScheme.primary,
                        activeTrackColor = MaterialTheme.colorScheme.primary,
                        inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    ),
                )
            }

            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (onCancel != null) {
                    TextButton(onClick = onCancel) { Text("Cancel") }
                    Spacer(Modifier.width(8.dp))
                }
                Button(
                    onClick = onSave,
                    enabled = locationDraft.isNotBlank(),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Icon(Icons.Default.Search, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (isFirstTime) "Start browsing" else "Update & search")
                }
            }
        }
    }
}

// ═════════════════════════════════════════════════════════════════════════════
// ── HELPERS ─────────────────────────────────────────────────────────────────
// ═════════════════════════════════════════════════════════════════════════════

internal fun formatRelativeTime(instant: Instant): String {
    val now = Clock.System.now()
    val diff = now - instant
    val minutes = diff.inWholeMinutes
    val hours = diff.inWholeHours
    val days = diff.inWholeDays
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "${minutes}m ago"
        hours < 24 -> "${hours}h ago"
        days == 1L -> "yesterday"
        days < 7 -> "${days}d ago"
        days < 30 -> "${(days / 7)}w ago"
        else -> "${(days / 30)}mo ago"
    }
}
