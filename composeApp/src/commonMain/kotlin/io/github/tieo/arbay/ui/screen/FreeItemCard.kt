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
                tint = MaterialTheme.colorScheme.primary,
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
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(Icons.Outlined.Explore, null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                Text(
                    "Expanded to $radius km",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
    listing: Listing,
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

    // Fly-out animation when a bottom bar button is pressed ("dislike", "down" or "right")
    LaunchedEffect(buttonAction) {
        val action = buttonAction ?: return@LaunchedEffect
        val targetX = when (action) {
            "right" -> 1200f
            "dislike" -> -1200f
            else -> 0f
        }
        val targetY = if (action == "down") 1200f else 0f
        val animatable = androidx.compose.animation.core.Animatable(0f)
        animatable.animateTo(1f, animationSpec = tween(250)) {
            offsetX = targetX * value
            offsetY = targetY * value
        }
        // Animation done; fire the actual action
        when (action) {
            "right" -> onSwipeRight()
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
                                // Axis locked; only move along that rail
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
                        contentDescription = listing.title,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                    // Bottom gradient so the white title overlay stays readable on bright photos
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
                // Location and scrape-time metadata; the title sits on the hero image when one exists
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
