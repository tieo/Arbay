package io.github.tieo.arbay.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.tieo.arbay.decimals
import io.github.tieo.arbay.model.FeedbackAction
import io.github.tieo.arbay.model.Listing
import io.github.tieo.arbay.openBrowser
import io.github.tieo.arbay.rememberCityDetector
import io.github.tieo.arbay.results.ageLabel
import io.github.tieo.arbay.viewmodel.FreeItemViewModel
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * What is given away near home, one item at a time. A swipe right keeps it, left passes, and each
 * verdict teaches the scorer, so the next item is the one it now thinks best. The buttons below do
 * the same for whoever would rather tap.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FreeItemsScreen(session: Session) {
    val vm = session.freeItems
    val profile by vm.profile.collectAsState()
    val listings by vm.listings.collectAsState()
    val dismissed by vm.dismissedIds.collectAsState()
    val loading by vm.loading.collectAsState()
    val loadingMore by vm.loadingMore.collectAsState()
    val error by vm.error.collectAsState()
    val lastAction by vm.lastAction.collectAsState()
    val radius by vm.currentRadiusKm.collectAsState()
    val deck = remember(listings, dismissed) { listings.filter { it.id !in dismissed } }
    val hasPlace = !profile?.location.isNullOrBlank()
    var sheet by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(profile?.location) { if (hasPlace && listings.isEmpty() && !loading) vm.search() }
    LaunchedEffect(deck.size) { vm.onCardViewed(deck.size, 0) }
    LaunchedEffect(Unit) { vm.dismissNewMatches() }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.padding(start = arbay.pad, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Free items", style = MaterialTheme.typography.headlineSmall)
                Muted(
                    if (!hasPlace) "Around home, once you say where that is"
                    else listOfNotNull("Around ${profile?.location}", (radius ?: profile?.radiusKm)?.let { "within $it km" }, deck.size.takeIf { it > 0 }?.let { "$it waiting" }).joinToString(" · "),
                    maxLines = 1,
                )
            }
            IconButton(onClick = { sheet = "kept" }) { Icon(Icons.Outlined.Inventory2, "Kept") }
            IconButton(onClick = { sheet = "profile" }) { Icon(Icons.Outlined.Tune, "What is worth a detour") }
            IconButton(onClick = { vm.search() }, enabled = hasPlace) { Icon(Icons.Outlined.Refresh, "Look again") }
        }
        if (loading || loadingMore) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp)) else Gap(2.dp)
        error?.let { Muted(it, Modifier.padding(horizontal = arbay.pad, vertical = 4.dp)) }

        Box(Modifier.weight(1f).fillMaxWidth().padding(arbay.pad)) {
            val current = deck.firstOrNull()
            when {
                !hasPlace -> Column(Modifier.verticalScroll(rememberScrollState())) { ProfileForm(vm) }
                current != null -> {
                    deck.getOrNull(1)?.let { next -> ItemCard(next, Modifier.fillMaxSize().graphicsLayer { scaleX = 0.96f; scaleY = 0.96f; alpha = 0.6f }) }
                    SwipeCard(current, onKeep = { vm.love(current) }, onPass = { vm.pass(current) })
                }
                loading || loadingMore -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                else -> Nothing("Nothing new is given away within reach right now.") { OutlinedButton(onClick = { vm.search() }) { Text("Look again") } }
            }
        }

        deck.firstOrNull()?.takeIf { hasPlace }?.let { current ->
            Row(Modifier.fillMaxWidth().padding(horizontal = arbay.pad).padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                if (lastAction != null) IconButton(onClick = { vm.undoLast() }) { Icon(Icons.AutoMirrored.Outlined.Undo, "Undo") }
                OutlinedButton(onClick = { vm.pass(current) }, Modifier.weight(1f).height(52.dp)) { Icon(Icons.Outlined.Close, "Not for me") }
                OutlinedButton(onClick = { vm.like(current) }, Modifier.weight(1f).height(52.dp)) { Icon(Icons.Outlined.Check, "Good, not now") }
                Button(onClick = { vm.love(current) }, Modifier.weight(1f).height(52.dp)) { Icon(Icons.Outlined.Favorite, "Keep it") }
            }
        }
    }

    sheet?.let { which ->
        ModalBottomSheet(onDismissRequest = { sheet = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = arbay.pad).padding(bottom = 24.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(arbay.gap)) {
                if (which == "kept") Kept(vm) else ProfileForm(vm) { sheet = null }
            }
        }
    }
}

/** The card on top, dragged sideways to decide: far enough right keeps it, far enough left passes. */
@Composable
private fun SwipeCard(listing: Listing, onKeep: () -> Unit, onPass: () -> Unit) {
    val offset = remember(listing.id) { Animatable(0f) }
    val scope = rememberCoroutineScope()
    var width by remember { mutableStateOf(1000f) }
    val threshold = width * 0.3f
    ItemCard(
        listing,
        Modifier.fillMaxSize()
            .onSizeChanged { width = it.width.toFloat() }
            .graphicsLayer { translationX = offset.value; rotationZ = offset.value / 40f }
            .draggable(
                orientation = Orientation.Horizontal,
                state = rememberDraggableState { delta -> scope.launch { offset.snapTo(offset.value + delta) } },
                onDragStopped = { velocity ->
                    val flung = abs(velocity) > 2000f
                    when {
                        offset.value > threshold || (flung && velocity > 0) -> { offset.animateTo(width * 1.5f); onKeep() }
                        offset.value < -threshold || (flung && velocity < 0) -> { offset.animateTo(-width * 1.5f); onPass() }
                        else -> offset.animateTo(0f)
                    }
                },
            ),
        hint = when {
            offset.value > threshold / 2 -> "Keep"
            offset.value < -threshold / 2 -> "Not for me"
            else -> null
        },
    )
}

@Composable
private fun ItemCard(listing: Listing, modifier: Modifier, hint: String? = null) {
    Column(
        modifier.clip(MaterialTheme.shapes.large).background(MaterialTheme.colorScheme.surface).border(1.dp, arbay.line, MaterialTheme.shapes.large),
    ) {
        Box(Modifier.fillMaxWidth().weight(1f)) {
            Photo(listing.imageUrls.firstOrNull { it.isNotBlank() }, Modifier.fillMaxSize(), listing.title)
            hint?.let {
                Text(
                    it, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.align(Alignment.TopCenter).padding(16.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary).padding(horizontal = 16.dp, vertical = 6.dp),
                )
            }
        }
        Column(Modifier.padding(arbay.pad), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(listing.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Muted(
                listOfNotNull(
                    listing.distanceKm?.let { "${decimals(it, if (it < 10) 1 else 0)} km away" },
                    listing.location?.let { listOfNotNull(it.zip, it.city).joinToString(" ").ifBlank { null } },
                    listing.listingDate?.let { ageLabel(it) },
                ).joinToString(" · "),
                maxLines = 1,
            )
            listing.relevanceScore?.let { score ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LinearProgressIndicator(progress = { score.toFloat() }, modifier = Modifier.weight(1f).height(4.dp).clip(CircleShape), drawStopIndicator = {})
                    Muted("${(score * 100).roundToInt()}% like what you keep")
                }
            }
            listing.description?.takeIf { it.isNotBlank() }?.let { Muted(it, maxLines = 3) }
            TextButton(onClick = { openBrowser(listing.url) }, contentPadding = ButtonDefaults.TextButtonContentPadding) {
                Icon(Icons.AutoMirrored.Outlined.OpenInNew, null, Modifier.size(16.dp)); HGap(6.dp); Text("Open on ${listing.platformId.displayName}")
            }
        }
    }
}

/** What is wanted and where: the words the scorer starts from, home and how far to go, and whether
 *  the server keeps looking while nobody is here. */
@Composable
private fun ProfileForm(vm: FreeItemViewModel, onSaved: () -> Unit = {}) {
    val profile by vm.profile.collectAsState()
    var description by remember(profile) { mutableStateOf(profile?.description.orEmpty()) }
    var place by remember(profile) { mutableStateOf(profile?.location.orEmpty()) }
    var radius by remember(profile) { mutableStateOf((profile?.radiusKm ?: 30).toString()) }
    val here = rememberCityDetector { city -> if (!city.isNullOrBlank()) place = city }
    Column(verticalArrangement = Arrangement.spacedBy(arbay.gap)) {
        Text("What is worth a detour", style = MaterialTheme.typography.titleLarge)
        OutlinedTextField(description, { description = it }, Modifier.fillMaxWidth().heightIn(min = 96.dp), label = { Text("Things you would pick up") }, placeholder = { Text("Furniture in solid wood, tools, plants, a bike") })
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
                place, { place = it }, Modifier.weight(2f), label = { Text("Home") }, placeholder = { Text("Town or postcode") }, singleLine = true,
                trailingIcon = { IconButton(onClick = here) { Icon(Icons.Outlined.MyLocation, "Where I am now") } },
            )
            OutlinedTextField(radius, { radius = it.filter(Char::isDigit) }, Modifier.weight(1f), label = { Text("Within km") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        }
        Button(onClick = {
            vm.saveProfile(description.trim(), place.trim(), radius.toIntOrNull() ?: 30)
            vm.search()
            onSaved()
        }, enabled = place.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Save and look") }
        profile?.let { p ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Keep looking while I am away, and tell me about a good one", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Switch(p.trackingEnabled, { vm.toggleTracking(it) })
            }
        }
    }
}

/** What was kept, newest first, each a way back to its listing and a way to take it back. */
@Composable
private fun Kept(vm: FreeItemViewModel) {
    val history by vm.history.collectAsState()
    val kept = history.filter { it.action == FeedbackAction.LOVE }
    Text(if (kept.isEmpty()) "Kept" else "Kept · ${kept.size}", style = MaterialTheme.typography.titleLarge)
    if (kept.isEmpty()) Muted("Items you keep stay here after they are gone from the market.")
    kept.forEach { item ->
        Row(
            Modifier.fillMaxWidth().clickable(enabled = item.url != null) { item.url?.let(::openBrowser) },
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Photo(item.imageUrl, Modifier.size(52.dp).clip(MaterialTheme.shapes.small))
            Column(Modifier.weight(1f)) {
                Text(item.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                item.locationText?.let { Muted(it, maxLines = 1) }
            }
            IconButton(onClick = { vm.undoFeedback(item.listingId) }) { Icon(Icons.AutoMirrored.Outlined.Undo, "Take back") }
        }
    }
}
