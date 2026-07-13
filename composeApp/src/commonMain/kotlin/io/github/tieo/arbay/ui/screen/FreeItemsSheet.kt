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
    // Button action trigger — set by bottom bar, consumed by SwipeCardStack animation
    var buttonAction by remember { mutableStateOf<String?>(null) }

    var editingProfile by remember { mutableStateOf(profile == null) }
    var profileDraft by remember(profile) { mutableStateOf(profile?.description ?: "") }
    var locationDraft by remember(profile) { mutableStateOf(profile?.location ?: "") }
    var radiusDraft by remember(profile) { mutableFloatStateOf((profile?.radiusKm ?: 30).toFloat()) }
    var showSavedSheet by remember { mutableStateOf(false) }

    // Auto-search when profile loads (only if location is set)
    LaunchedEffect(profile) {
        val p = profile
        if (p != null && !p.location.isNullOrBlank() && listings.isEmpty() && !loading) {
            editingProfile = false
            viewModel.search()
        } else if (p != null && p.location.isNullOrBlank()) {
            editingProfile = true // Force profile editor open if no location
        }
    }

    val visibleListings = remember(listings, dismissedIds) {
        listings.filter { it.id !in dismissedIds }
    }

    // The current card is always visibleListings[0] — we don't track an index.
    // When an item is dismissed, it disappears from visibleListings and the next
    // item naturally becomes [0]. This avoids the double-skip bug.

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
                    error = error,
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
                        !editingProfile && visibleListings.isEmpty() && !loading && profile != null -> {
                            EmptyState(
                                hasLocation = profile?.location != null,
                                onRetry = { viewModel.search() },
                            )
                        }

                        !editingProfile && loading && visibleListings.isEmpty() -> {
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
                    properties = androidx.compose.ui.window.DialogProperties(
                        usePlatformDefaultWidth = false,
                        decorFitsSystemWindows = false,
                    ),
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
private fun SwipeHeader(
    profile: FreeItemProfile?,
    editingProfile: Boolean,
    loading: Boolean,
    error: String?,
    displayRadius: Int?,
    currentRadiusKm: Int?,

    currentIndex: Int,
    totalVisible: Int,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.CardGiftcard, null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.tertiary,
            )
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("Free Items", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
                if (profile?.location != null && !editingProfile) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.LocationOn, null, modifier = Modifier.size(11.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(2.dp))
                        Text(
                            "${profile.location} · ${displayRadius ?: profile.radiusKm} km",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            // Item counter
            if (totalVisible > 0 && !editingProfile) {
                Text(
                    "$currentIndex / $totalVisible",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(4.dp))
            }
            if (profile != null && !editingProfile && !loading) {
                IconButton(onClick = onRefresh, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Outlined.Refresh, "Refresh", modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            IconButton(onClick = onDismiss, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Default.Close, "Close", modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        // Expanded radius banner
        currentRadiusKm?.let { radius ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 2.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(Icons.Outlined.Explore, null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.tertiary)
                Text(
                    "Expanded to $radius km",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
        }


        if (loading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        } else {
            HorizontalDivider()
        }

        error?.let { msg ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.errorContainer)
                    .padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                Text(msg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
            }
        }
    }
}

// ═════════════════════════════════════════════════════════════════════════════
// ── SWIPE CARD STACK ────────────────────────────────────────────────────────
// ═════════════════════════════════════════════════════════════════════════════

@Composable
private fun SwipeCardStack(
    listing: Listing?,
    nextListing: Listing?,
    undoDirection: String?,
    onUndoAnimDone: () -> Unit,
    buttonAction: String?,
    onButtonActionConsumed: () -> Unit,
    loadingMore: Boolean,
    onSwipeRight: () -> Unit,
    onSwipeLeft: () -> Unit,
    onSwipeDown: () -> Unit,
    onDislike: () -> Unit,
    onOpen: () -> Unit,
    onMoreLikeThis: () -> Unit,
) {
    if (listing == null) {
        if (loadingMore) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(12.dp))
                    Text("Loading more...", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        return
    }

    // Undo slide-back animation: start off-screen, animate to 0
    val undoStartX = when (undoDirection) {
        "right" -> 800f
        "left" -> -800f
        else -> 0f
    }
    val undoStartY = if (undoDirection == "down") 800f else 0f

    var offsetX by remember(listing.id) { mutableFloatStateOf(undoStartX) }
    var offsetY by remember(listing.id) { mutableFloatStateOf(undoStartY) }
    // null = undecided, "horizontal" or "vertical" once locked
    var lockedAxis by remember(listing.id) { mutableStateOf<String?>(null) }

    // Animate back to center when undone
    LaunchedEffect(listing.id, undoDirection) {
        if (undoDirection != null && (offsetX != 0f || offsetY != 0f)) {
            val animatable = androidx.compose.animation.core.Animatable(1f)
            val startX = offsetX
            val startY = offsetY
            animatable.animateTo(0f, animationSpec = tween(300)) {
                offsetX = startX * value
                offsetY = startY * value
            }
            onUndoAnimDone()
        }
    }

    // Fly-out animation when button is pressed
    LaunchedEffect(buttonAction) {
        val action = buttonAction ?: return@LaunchedEffect
        val targetX = when (action) {
            "right" -> 1200f
            "left", "dislike" -> -1200f
            else -> 0f
        }
        val targetY = if (action == "down") 1200f else 0f
        val animatable = androidx.compose.animation.core.Animatable(0f)
        animatable.animateTo(1f, animationSpec = tween(250)) {
            offsetX = targetX * value
            offsetY = targetY * value
        }
        // Animation done — fire the actual action
        when (action) {
            "right" -> onSwipeRight()
            "left" -> onSwipeLeft()
            "down" -> onSwipeDown()
            "dislike" -> onDislike()
        }
        onButtonActionConsumed()
    }

    val swipeThreshold = 150f
    val lockThreshold = 20f   // px before axis locks
    val deadZoneAngle = 30f   // degrees from axis center that are "dead"

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        // ── Next card (peek behind current) ─────────────────────
        if (nextListing != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.95f)
                    .scale(0.95f)
                    .graphicsLayer { alpha = 0.5f },
            ) {
                SwipeCard(
                    listing = nextListing,

                    onOpen = {},
                    onMoreLikeThis = {},
                )
            }
        }

        // ── Current card with swipe gesture ─────────────────────
        val rotation = (offsetX / 30f).coerceIn(-15f, 15f)
        val normalizedX = (offsetX / swipeThreshold).coerceIn(-1f, 1f)
        val normalizedY = (offsetY / swipeThreshold).coerceIn(-1f, 1f)

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.95f)
                .offset { IntOffset(offsetX.roundToInt(), offsetY.coerceAtLeast(0f).roundToInt()) }
                .rotate(rotation)
                .pointerInput(listing.id) {
                    detectDragGestures(
                        onDragEnd = {
                            when (lockedAxis) {
                                "horizontal" -> when {
                                    offsetX > swipeThreshold -> onSwipeRight()
                                    offsetX < -swipeThreshold -> onSwipeLeft()
                                    else -> { offsetX = 0f; offsetY = 0f }
                                }
                                "vertical" -> when {
                                    offsetY > swipeThreshold -> onSwipeDown()
                                    else -> { offsetX = 0f; offsetY = 0f }
                                }
                                else -> { offsetX = 0f; offsetY = 0f }
                            }
                            lockedAxis = null
                        },
                        onDragCancel = {
                            offsetX = 0f
                            offsetY = 0f
                            lockedAxis = null
                        },
                        onDrag = { _, dragAmount ->
                            if (lockedAxis == null) {
                                // Accumulate until we have enough movement to decide axis
                                val tentativeX = offsetX + dragAmount.x
                                val tentativeY = offsetY + dragAmount.y
                                val dist = sqrt(tentativeX * tentativeX + tentativeY * tentativeY)
                                if (dist >= lockThreshold) {
                                    // Angle from positive X axis: 0°=right, 90°=down, 180°=left
                                    val angle = atan2(tentativeY, tentativeX.absoluteValue) * 180f / Math.PI.toFloat()
                                    // Dead zone: 30°-60° from horizontal (diagonal area)
                                    lockedAxis = when {
                                        angle < deadZoneAngle -> "horizontal"  // 0°-30° → horizontal
                                        angle > (90f - deadZoneAngle) -> "vertical"  // 60°-90° → vertical
                                        else -> null  // 30°-60° → dead zone, keep accumulating
                                    }
                                }
                                if (lockedAxis != null) {
                                    // Snap to axis
                                    when (lockedAxis) {
                                        "horizontal" -> { offsetX = tentativeX; offsetY = 0f }
                                        "vertical" -> { offsetX = 0f; offsetY = tentativeY.coerceAtLeast(0f) }
                                    }
                                } else {
                                    offsetX = tentativeX
                                    offsetY = tentativeY
                                }
                            } else {
                                // Axis locked — only move along that rail
                                when (lockedAxis) {
                                    "horizontal" -> offsetX += dragAmount.x
                                    "vertical" -> offsetY = (offsetY + dragAmount.y).coerceAtLeast(0f)
                                }
                            }
                        },
                    )
                },
        ) {
            SwipeCard(
                listing = listing,
                onOpen = onOpen,
                onMoreLikeThis = onMoreLikeThis,
            )

            // ── Swipe overlay indicators ────────────────────────
            // SAVE indicator (right swipe)
            if (lockedAxis == "horizontal" && normalizedX > 0.15f) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(24.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFFE91E63).copy(alpha = (normalizedX * 0.8f).coerceIn(0f, 0.8f)))
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Text("SAVE", style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold, color = Color.White))
                }
            }

            // PASS indicator (left swipe)
            if (lockedAxis == "horizontal" && normalizedX < -0.15f) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(24.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = (normalizedX.absoluteValue * 0.8f).coerceIn(0f, 0.8f)))
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Text("PASS", style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant))
                }
            }

            // LIKE indicator (down swipe)
            if (lockedAxis == "vertical" && normalizedY > 0.15f) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(24.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF4CAF50).copy(alpha = (normalizedY * 0.8f).coerceIn(0f, 0.8f)))
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Text("LIKE", style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold, color = Color.White))
                }
            }
        }

        // Loading more indicator
        if (loadingMore) {
            CircularProgressIndicator(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 8.dp)
                    .size(24.dp),
                strokeWidth = 2.dp,
            )
        }
    }
}

// ═════════════════════════════════════════════════════════════════════════════
// ── SWIPE CARD ──────────────────────────────────────────────────────────────
// ═════════════════════════════════════════════════════════════════════════════

@Composable
private fun SwipeCard(
    listing: Listing,

    onOpen: () -> Unit,
    onMoreLikeThis: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val images = listing.imageUrls.filter { it.startsWith("http") }


    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        modifier = modifier.fillMaxSize(),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // ── Hero image (fills most of the card) ─────────────
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center,
            ) {
                if (images.isNotEmpty()) {
                    AsyncImage(
                        model = images.first(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                    // Bottom gradient
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(100.dp)
                            .align(Alignment.BottomCenter)
                            .background(
                                Brush.verticalGradient(
                                    listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f))
                                )
                            ),
                    )

                    // Match percentage badge (top-right)
                    listing.relevanceScore?.let { score ->
                        val pct = (score * 100).toInt()
                        val badgeColor = when {
                            pct >= 80 -> Color(0xFF4CAF50)
                            pct >= 50 -> Color(0xFFFFA726)
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                        Surface(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(12.dp),
                            shape = RoundedCornerShape(10.dp),
                            color = Color.Black.copy(alpha = 0.6f),
                        ) {
                            Text(
                                "$pct%",
                                style = MaterialTheme.typography.labelLarge.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = badgeColor,
                                ),
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            )
                        }
                    }

                    // Title overlay on image
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(16.dp),
                    ) {
                        Text(
                            listing.title,
                            style = MaterialTheme.typography.headlineSmall.copy(
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                            ),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.CardGiftcard, null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.outlineVariant,
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            listing.title,
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 24.dp),
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }

            // ── Info section ────────────────────────────────────
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                // Title (only if no image — otherwise it's on the image)
                if (images.isNotEmpty()) {
                    // Metadata row
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        listing.location?.let { loc ->
                            val text = loc.raw ?: listOfNotNull(loc.zip, loc.city).joinToString(" ")
                            if (text.isNotBlank()) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Outlined.LocationOn, null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Spacer(Modifier.width(3.dp))
                                    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                        Text(
                            formatRelativeTime(listing.scrapedAt),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    // No image — show location/time
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        listing.location?.let { loc ->
                            val text = loc.raw ?: listOfNotNull(loc.zip, loc.city).joinToString(" ")
                            if (text.isNotBlank()) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Outlined.LocationOn, null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Spacer(Modifier.width(3.dp))
                                    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                        Text(
                            formatRelativeTime(listing.scrapedAt),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                // Description
                listing.description?.let { desc ->
                    if (desc.isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            desc,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }


                // Action row
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = onOpen,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    ) {
                        Icon(Icons.Outlined.OpenInBrowser, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Open in Kleinanzeigen", style = MaterialTheme.typography.labelMedium)
                    }
                    TextButton(
                        onClick = onMoreLikeThis,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    ) {
                        Icon(Icons.Outlined.ContentCopy, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("More like this", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}

// ═════════════════════════════════════════════════════════════════════════════
// ── SWIPE BOTTOM BAR (Tinder-style action buttons) ──────────────────────────
// ═════════════════════════════════════════════════════════════════════════════

@Composable
private fun SwipeBottomBar(
    lovedCount: Int,
    totalReviewed: Int,
    hasCurrentCard: Boolean,
    canUndo: Boolean,
    onUndo: () -> Unit,
    onSavedClick: () -> Unit,
    onDislike: () -> Unit,
    onLike: () -> Unit,
    onLove: () -> Unit,
) {
    Surface(
        tonalElevation = 2.dp,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            // Saved button (left)
            FilledTonalButton(
                onClick = onSavedClick,
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = if (lovedCount > 0)
                        Color(0xFFE91E63).copy(alpha = 0.12f)
                    else
                        MaterialTheme.colorScheme.surfaceContainerHigh,
                ),
            ) {
                Icon(
                    if (lovedCount > 0) Icons.Default.Favorite else Icons.Outlined.FavoriteBorder,
                    null,
                    modifier = Modifier.size(16.dp),
                    tint = if (lovedCount > 0) Color(0xFFE91E63) else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (lovedCount > 0) {
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "$lovedCount",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color(0xFFE91E63),
                    )
                }
            }

            // Undo button (always visible, greyed out when nothing to undo)
            FilledIconButton(
                onClick = onUndo,
                enabled = canUndo,
                modifier = Modifier.size(48.dp),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = if (canUndo) MaterialTheme.colorScheme.tertiaryContainer
                        else MaterialTheme.colorScheme.surfaceContainerHigh,
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                ),
            ) {
                Icon(
                    Icons.Default.Replay, "Undo",
                    modifier = Modifier.size(22.dp),
                    tint = if (canUndo) MaterialTheme.colorScheme.onTertiaryContainer
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
                )
            }

            if (hasCurrentCard) {
                // Dislike (teach AI)
                FilledIconButton(
                    onClick = onDislike,
                    modifier = Modifier.size(48.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f),
                    ),
                ) {
                    Icon(Icons.Outlined.ThumbDown, "Dislike", modifier = Modifier.size(22.dp), tint = MaterialTheme.colorScheme.error)
                }

                // Like (positive signal, don't save)
                FilledIconButton(
                    onClick = onLike,
                    modifier = Modifier.size(48.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = Color(0xFF4CAF50).copy(alpha = 0.15f),
                    ),
                ) {
                    Icon(Icons.Outlined.ThumbUp, "Like", modifier = Modifier.size(22.dp), tint = Color(0xFF4CAF50))
                }

                // Love / Save
                FilledIconButton(
                    onClick = onLove,
                    modifier = Modifier.size(56.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = Color(0xFFE91E63).copy(alpha = 0.15f),
                    ),
                ) {
                    Icon(Icons.Default.Favorite, "Save", modifier = Modifier.size(28.dp), tint = Color(0xFFE91E63))
                }
            }
        }
    }
}

// ═════════════════════════════════════════════════════════════════════════════
// ── LOADING STATE ───────────────────────────────────────────────────────────
// ═════════════════════════════════════════════════════════════════════════════

@Composable
private fun LoadingState(platformStatus: PlatformStatus?) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(16.dp))
            Text(
                "Finding free items nearby...",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            platformStatus?.fetchStage?.let { stage ->
                Spacer(Modifier.height(4.dp))
                Text(stage, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// ═════════════════════════════════════════════════════════════════════════════
// ── EMPTY STATE ─────────────────────────────────────────────────────────────
// ═════════════════════════════════════════════════════════════════════════════

@Composable
private fun EmptyState(hasLocation: Boolean, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Outlined.Inbox, null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.height(12.dp))
        Text("No free items found", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (!hasLocation) {
            Spacer(Modifier.height(8.dp))
            Text("Tip: Add your city to find nearby items", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(20.dp))
        OutlinedButton(onClick = onRetry) { Text("Try again") }
    }
}

// ═════════════════════════════════════════════════════════════════════════════
// ── SAVED & ACTIVITY SHEET ──────────────────────────────────────────────────
// ═════════════════════════════════════════════════════════════════════════════

@Composable
private fun SavedSheet(
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
                                        Text("No predictions yet — swipe some items first", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                                Text("Items the model scored low — check for false negatives", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                                            AsyncImage(model = item.imageUrl, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
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
                    AsyncImage(model = item.imageUrl, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
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
                    AsyncImage(model = item.imageUrl, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
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
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f)),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                if (isFirstTime) "Set your area and start swiping" else "Update your interests",
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Just set a location to start — swipe through free items and the model learns what you want. A description is optional and only nudges the early ranking.",
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

private fun formatRelativeTime(instant: Instant): String {
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
