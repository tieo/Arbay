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
import io.github.tieo.arbay.model.BlockReview
import io.github.tieo.arbay.model.ChatAccount
import io.github.tieo.arbay.model.ReviewAnswer
import io.github.tieo.arbay.model.ChatMessage
import io.github.tieo.arbay.model.ChatSettings
import io.github.tieo.arbay.model.Conversation
import io.github.tieo.arbay.model.MessageTemplate
import io.github.tieo.arbay.model.OutgoingMessage
import io.github.tieo.arbay.viewmodel.ChatViewModel
import io.github.tieo.arbay.viewmodel.FreeItemViewModel
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
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

private val NOW = Clock.System.now()

/** The user's own text, as they wrote it, standing in for whatever texts they keep. */
private val SAMPLE_TEXT = MessageTemplate(
    "id", "Ausweis",
    "Falls ohne Käuferschutz, hätte ich gerne ein Foto mit dem Produkt und dem Ausweis, gerne alles andere außer dem Namen mit zwei Blättern Papier abdecken. Wurde leider in der Vergangenheit Betrugsopfer.",
)

private fun chat(signedIn: Boolean = true, picked: Set<String> = emptySet()) = ChatViewModel(
    sampleAccount = ChatAccount(signedIn = signedIn, name = if (signedIn) "Max" else null),
    sampleConversations = listOf(
        Conversation(
            "c1", listingId = "KLEINANZEIGEN:1", adTitle = PreviewData.active[0].title, adImage = PreviewData.active[0].imageUrls.first(),
            partner = "Erika", unread = 1, lastAt = NOW - 25.minutes, lastText = "Ja, ist noch da. Foto kommt gleich.",
            messages = listOf(
                ChatMessage("m1", mine = true, text = "Hallo, ist die Maschine noch zu haben?", at = NOW - 2.hours),
                ChatMessage("m2", mine = false, text = "Ja, ist noch da. Foto kommt gleich.", at = NOW - 25.minutes),
            ),
        ),
        Conversation("c2", adTitle = "Bosch Akkuschrauber", partner = "Jonas", buying = false, lastAt = NOW - 3.days, lastText = "Danke, hat alles geklappt"),
    ),
    sampleOutbox = listOf(OutgoingMessage("o1", "KLEINANZEIGEN:7", PreviewData.active[6].title, "Hallo", NOW + 70.seconds)),
    sampleSettings = ChatSettings(templates = listOf(SAMPLE_TEXT), allInBySearch = mapOf(PreviewData.saved.first().id to 450.0)),
    sampleReview = listOf(
        BlockReview(
            SAMPLE_TEXT.id, SAMPLE_TEXT.name, sent = 6, answered = 4, middleMinutesToAnswer = 95,
            answers = listOf(ReviewAnswer("c1", PreviewData.active[0].title, NOW - 2.hours, 425, "Ja, ist noch da. Foto kommt gleich.", NOW - 25.minutes)),
        ),
    ),
).also { vm -> picked.forEach(vm::toggleSelected) }

private fun session(
    listings: List<Listing> = PreviewData.active,
    statuses: List<PlatformStatus> = PreviewData.marketAnswers,
    loading: Boolean = false,
    newIds: Set<String> = emptySet(),
    chat: ChatViewModel = chat(),
) = Session(
    client = ArbayClient(),
    products = ProductViewModel(saved = PreviewData.saved, savedStatus = PreviewData.savedStatus, rendersASample = true),
    listings = ListingViewModel(sample = listings, sampleStatuses = statuses, sampleLoading = loading),
    freeItems = FreeItemViewModel(sampleProfile = PreviewData.freeItemProfile, sampleItems = PreviewData.active),
    chat = chat,
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
    Scene("picking") { At(Route.Home, Route.Results(saved), s = session(chat = chat(picked = setOf("KLEINANZEIGEN:1", "KLEINANZEIGEN:7")))) },
    Scene("panel-write") { PanelScene(Panel.WRITE, session(chat = chat(picked = setOf("KLEINANZEIGEN:1", "KLEINANZEIGEN:7", "EBAY_DE:2")))) },
    Scene("inbox") { At(Route.Inbox) },
    Scene("inbox-signedout") { At(Route.Inbox, s = session(chat = chat(signedIn = false))) },
    Scene("conversation") { At(Route.Inbox, Route.Conversation("c1")) },
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
