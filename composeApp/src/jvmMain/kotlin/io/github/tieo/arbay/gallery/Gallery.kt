package io.github.tieo.arbay.gallery

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.tieo.arbay.api.ArbayClient
import io.github.tieo.arbay.design.Look
import io.github.tieo.arbay.design.LookChoice
import io.github.tieo.arbay.design.Looks
import io.github.tieo.arbay.design.OfferLayout
import io.github.tieo.arbay.model.Listing
import io.github.tieo.arbay.navigation.Panel
import io.github.tieo.arbay.navigation.Route
import io.github.tieo.arbay.navigation.Source
import io.github.tieo.arbay.results.rememberOpenSearch
import io.github.tieo.arbay.results.rememberResultsState
import io.github.tieo.arbay.sample.PreviewData
import io.github.tieo.arbay.ui.ArbayApp
import io.github.tieo.arbay.ui.LocalNavigator
import io.github.tieo.arbay.ui.Navigator
import io.github.tieo.arbay.ui.PanelBody
import io.github.tieo.arbay.ui.Session
import io.github.tieo.arbay.viewmodel.FreeItemViewModel
import io.github.tieo.arbay.viewmodel.ListingViewModel
import io.github.tieo.arbay.viewmodel.PlatformStatus
import io.github.tieo.arbay.viewmodel.ProductViewModel
import java.io.File

/**
 * Every phone screen drawn off-screen with sample data, one PNG per screen, state, look and
 * brightness, so the design can be looked at without a device.
 */
private const val PHONE_W = 390
private const val PHONE_H = 844

private data class Scene(val name: String, val content: @Composable () -> Unit)

private fun session(
    listings: List<Listing> = PreviewData.active,
    statuses: List<PlatformStatus> = PreviewData.marketAnswers,
    loading: Boolean = false,
    newIds: Set<String> = emptySet(),
) = Session(
    client = ArbayClient(),
    products = ProductViewModel(saved = PreviewData.saved, savedStatus = PreviewData.savedStatus, rendersASample = true),
    listings = ListingViewModel(sample = listings, sampleStatuses = statuses, sampleLoading = loading),
    freeItems = FreeItemViewModel(sampleProfile = PreviewData.freeItemProfile, sampleItems = PreviewData.active),
)

/** The app as it stands at [route], with whatever steps lie under it. */
@Composable
private fun At(vararg steps: Route, s: Session = session()) {
    val nav = remember { Navigator(steps.first()).also { n -> steps.drop(1).forEach(n::go) } }
    ArbayApp(s, nav)
}

private val saved = Source.Saved(PreviewData.saved.first().id)

/** A panel's content as it reads inside its sheet. */
@Composable
private fun PanelScene(panel: Panel, s: Session = session()) {
    val route = Route.Results(saved, panel = panel)
    CompositionLocalProvider(LocalNavigator provides remember { Navigator(route) }) {
        val open = rememberOpenSearch(saved, s.products) ?: return@CompositionLocalProvider
        val state = rememberResultsState(s.listings, open)
        Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.padding(top = 24.dp)) { PanelBody(s, route, panel, state) }
        }
    }
}

private val SCENES: List<Scene> = listOf(
    Scene("home") { At(Route.Home) },
    Scene("results") { At(Route.Home, Route.Results(saved)) },
    Scene("results-loading") { At(Route.Home, Route.Results(saved), s = session(PreviewData.active.take(3), PreviewData.stillAsking, loading = true)) },
    Scene("results-empty") { At(Route.Home, Route.Results(saved), s = session(emptyList(), PreviewData.nobodyHadAnything)) },
    Scene("results-captcha") { At(Route.Home, Route.Results(saved), s = session(PreviewData.active.take(3), PreviewData.captchaHeld)) },
    Scene("offer") { At(Route.Home, Route.Results(saved), Route.Results(saved, listing = PreviewData.active[1].id)) },
    Scene("vehicle") { At(Route.Home, Route.VehicleForm) },
    Scene("free") { At(Route.FreeItems) },
    Scene("settings") { At(Route.Settings) },
    Scene("panel-prices") { PanelScene(Panel.PRICES) },
    Scene("panel-markets") { PanelScene(Panel.MARKETS) },
    Scene("panel-words") { PanelScene(Panel.WORDS) },
    Scene("panel-hidden") { PanelScene(Panel.HIDDEN) },
    Scene("panel-alerts") { PanelScene(Panel.ALERTS) },
)

fun main() {
    val outDir = File(System.getProperty("gallery.out") ?: "build/gallery")
    val only = System.getProperty("gallery.only")?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
    val themes = (System.getProperty("gallery.themes") ?: "light,dark").split(",").map { it.trim() }.toSet()
    val looks = (System.getProperty("gallery.looks") ?: "receipt").split(",").map { Looks.byId(it.trim()) }
    val layouts = (System.getProperty("gallery.layouts") ?: "rows").split(",").map { OfferLayout.valueOf(it.trim().uppercase()) }
    val started = System.currentTimeMillis()
    var drawn = 0
    for (scene in SCENES.filter { only == null || it.name in only || only.any { o -> it.name.startsWith(o) } }) {
        for (look: Look in looks) for (layout in layouts) for (theme in listOf("light", "dark")) {
            if (theme !in themes) continue
            LookChoice.choose(look)
            LookChoice.choose(layout)
            val name = listOfNotNull(scene.name, look.id, layout.name.lowercase().takeIf { layouts.size > 1 }, theme).joinToString("-")
            runCatching {
                renderToPng(name, PHONE_W, PHONE_H, dark = theme == "dark", outDir = outDir, scale = 2f) { Box(Modifier.fillMaxSize()) { scene.content() } }
                drawn++
            }.onFailure { println("FAILED $name: ${it.message}"); it.printStackTrace() }
        }
    }
    println("$drawn renders in ${(System.currentTimeMillis() - started) / 1000.0}s, written to ${outDir.absolutePath}")
}
