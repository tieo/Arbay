package io.github.tieo.arbay.web

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import io.github.tieo.arbay.DisplayCurrency
import io.github.tieo.arbay.ImportRules
import io.github.tieo.arbay.SearchCountries
import io.github.tieo.arbay.design.Brightness
import io.github.tieo.arbay.design.Look
import io.github.tieo.arbay.design.LookChoice
import io.github.tieo.arbay.design.Looks
import io.github.tieo.arbay.design.OfferLayout
import io.github.tieo.arbay.loadDeviceSettings
import io.github.tieo.arbay.model.MarketGroup
import io.github.tieo.arbay.model.MarketSets
import io.github.tieo.arbay.model.MarketSettings
import io.github.tieo.arbay.model.NotificationSettings
import io.github.tieo.arbay.model.PlatformId
import io.github.tieo.arbay.model.platformsIn
import io.github.tieo.arbay.results.flagEmoji
import io.github.tieo.arbay.saveDeviceSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.jetbrains.compose.web.attributes.InputType
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H1
import org.jetbrains.compose.web.dom.H2
import org.jetbrains.compose.web.dom.Header
import org.jetbrains.compose.web.dom.Input
import org.jetbrains.compose.web.dom.Label
import org.jetbrains.compose.web.dom.Main
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Section
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.w3c.notifications.DEFAULT
import org.w3c.notifications.GRANTED
import org.w3c.notifications.Notification
import org.w3c.notifications.NotificationPermission

/**
 * Settings in two halves: how this browser shows things, which only this browser keeps, and what
 * the server does for every device that uses it. Each server setting saves as it is changed.
 */
@Composable
fun SettingsScreen(app: WebApp) {
    Main({ classes("results", "settings") }) {
        Header({ classes("results-head") }) { H1 { Text("Settings") } }
        Div({ classes("form-scroll") }) {
            H2({ classes("group-title") }) { Text("This browser") }
            LookSection()
            CurrencySection()
            H2({ classes("group-title") }) { Text("The server, for every device") }
            ServerSections(app)
        }
    }
}

@Composable
private fun Setting(title: String, detail: String? = null, content: @Composable () -> Unit) {
    Section({ classes("setting") }) {
        Div({ classes("setting-head") }) {
            Span({ classes("setting-title") }) { Text(title) }
            detail?.let { Span({ classes("muted", "small") }) { Text(it) } }
        }
        Div({ classes("setting-body") }) { content() }
    }
}

/** The looks side by side, each drawn in its own colours, so the choice is made by seeing them. */
@Composable
private fun LookSection() {
    Setting("Look") {
        Div({ classes("looks") }) {
            Looks.all.forEach { look -> LookSample(look, LookChoice.look.id == look.id) }
        }
        Div({ classes("chips") }) {
            Brightness.entries.forEach { b -> Chip(b.label, LookChoice.brightness == b) { LookChoice.choose(b) } }
        }
        Div({ classes("chips") }) {
            Span({ classes("chips-label") }) { Text("Offers as") }
            OfferLayout.entries.forEach { l -> Chip(l.label, LookChoice.layout == l) { LookChoice.choose(l) } }
        }
    }
}

@Composable
private fun LookSample(look: Look, chosen: Boolean) {
    val p = if (prefersDark()) look.dark else look.light
    fun hex(c: Long) = "#" + c.toString(16).padStart(6, '0')
    Button(attrs = {
        attr("type", "button")
        attr("aria-pressed", chosen.toString())
        classes(*listOfNotNull("look-sample", "on".takeIf { chosen }).toTypedArray())
        style {
            property("background", hex(p.background))
            property("color", hex(p.text))
            property("border-radius", "${look.radius}px")
        }
        onClick { LookChoice.choose(look) }
    }) {
        Span({ classes("look-name") }) { Text(look.name) }
        Span({
            classes("look-row")
            style { property("background", hex(p.surface)); property("border-color", hex(p.line)); property("border-radius", "${look.radius}px") }
        }) {
            Span({ style { property("color", hex(p.muted)) } }) { Text("Cheapest") }
            Span({
                style {
                    property("color", hex(p.lowest))
                    property("font-weight", "600")
                    if (look.monoPrices) property("font-family", "var(--mono)")
                }
            }) { Text("€ 1.240") }
        }
        Span({
            classes("look-accent")
            style { property("background", hex(p.accent)); property("color", hex(p.onAccent)); property("border-radius", "${look.radius}px") }
        }) { Text("Save") }
    }
}

private fun prefersDark(): Boolean = when (LookChoice.brightness) {
    Brightness.DARK -> true
    Brightness.LIGHT -> false
    Brightness.SYSTEM -> kotlinx.browser.window.matchMedia("(prefers-color-scheme: dark)").matches
}

@Composable
private fun CurrencySection() {
    Setting("Currency", "Offers from other markets are converted into it") {
        Div({ classes("chips") }) {
            listOf("EUR", "USD", "GBP", "CHF").forEach { currency ->
                Chip(currency, DisplayCurrency.current == currency) {
                    DisplayCurrency.current = currency
                    runCatching { saveDeviceSettings(loadDeviceSettings() + ("currency" to currency)) }
                }
            }
        }
    }
}

/** What a save to the server came to, said beside the setting it belongs to. */
private data class Saved(val ok: Boolean, val text: String)

@Composable
private fun SaveNote(saved: Saved?) {
    saved?.let { Span({ classes("save-note", if (it.ok) "ok" else "failed") }) { Text(it.text) } }
}

@Composable
private fun ServerSections(app: WebApp) {
    val scope = rememberCoroutineScope()
    val client = app.client
    var countries by remember { mutableStateOf(SearchCountries.current.countries) }
    var rules by remember { mutableStateOf(ImportRules.current) }
    var depth by remember { mutableStateOf<Int?>(null) }
    var alerts by remember { mutableStateOf<NotificationSettings?>(null) }
    val notes = remember { mutableStateOf<Map<String, Saved>>(emptyMap()) }

    LaunchedEffect(Unit) {
        runCatching { client.getMarketSettings() }.onSuccess { countries = it.countries; SearchCountries.current = it }
        runCatching { client.getImportSettings() }.onSuccess { rules = it; ImportRules.current = it }
        runCatching { client.getCrawlerConfig() }.onSuccess { depth = it.maxResultsPerPlatform }
        alerts = client.getNotificationSettings()
    }

    /** Sends one setting and says beside it whether the server took it. */
    fun save(key: String, block: suspend () -> Unit) {
        scope.launch {
            val outcome = try {
                block(); Saved(true, "Saved")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Saved(false, "Not saved: ${e.message ?: "the server did not answer"}")
            }
            notes.value = notes.value + (key to outcome)
        }
    }

    Setting("Countries a search covers", "A search that names no markets of its own asks the markets in these") {
        val available = remember { PlatformId.entries.map { MarketSets.countryOf(it) }.distinct().sorted() }
        Div({ classes("chips") }) {
            available.forEach { country ->
                val markets = PlatformId.entries.count { MarketSets.countryOf(it) == country }
                Chip("${flagEmoji(country)} $country · $markets", country in countries) {
                    countries = if (country in countries) countries - country else countries + country
                    val next = MarketSettings(countries)
                    save("countries") { SearchCountries.current = client.updateMarketSettings(next) }
                }
            }
        }
        P({ classes("muted", "small") }) {
            val general = MarketSets.platformsIn(MarketGroup.GENERAL, countries).size
            val vehicles = MarketSets.platformsIn(MarketGroup.VEHICLES, countries).size
            Text("$general markets for things, $vehicles for vehicles")
        }
        SaveNote(notes.value["countries"])
    }

    Setting("Buying from abroad", "A market outside your VAT area quotes its price without the import VAT due on the way in") {
        Switch("Add import VAT to prices from abroad", rules.enabled) {
            rules = rules.copy(enabled = it)
            val next = rules
            save("import") { ImportRules.current = client.updateImportSettings(next) }
        }
        Div({ classes("field-row") }) {
            Label(attrs = { classes("field", "narrow") }) {
                Span({ classes("field-label") }) { Text("You are in") }
                Input(InputType.Text) {
                    classes("control"); attr("maxlength", "2"); value(rules.homeCountry)
                    onInput { rules = rules.copy(homeCountry = it.value.uppercase().filter { c -> c.isLetter() }.take(2)) }
                    onChange { val next = rules; save("import") { ImportRules.current = client.updateImportSettings(next) } }
                }
            }
            Label(attrs = { classes("field", "narrow") }) {
                Span({ classes("field-label") }) { Text("Import VAT %") }
                Input(InputType.Number) {
                    classes("control"); value(rules.importVatPercent.toString())
                    onInput { rules = rules.copy(importVatPercent = it.value?.toInt() ?: 0) }
                    onChange { val next = rules; save("import") { ImportRules.current = client.updateImportSettings(next) } }
                }
            }
        }
        SaveNote(notes.value["import"])
    }

    Setting("Search depth", "Each market is read until it has given this many offers; deeper is slower") {
        Div({ classes("chips") }) {
            (listOf(30, 60, 120, 250) + listOfNotNull(depth)).distinct().sorted().forEach { n ->
                Chip("$n", depth == n) {
                    depth = n
                    save("depth") { client.updateCrawlerConfig(n) }
                }
            }
        }
        SaveNote(notes.value["depth"])
    }

    alerts?.let { current ->
        Setting("Notifications", "Only what is gone if you wait: a free item near you, and the rules set on a saved search") {
            BrowserPermission()
            Switch("A free item near you scoring at least ${current.freeItemScorePct}%", current.freeItemAlerts) {
                val next = current.copy(freeItemAlerts = it)
                alerts = next
                save("alerts") { client.updateNotificationSettings(next) }
            }
            Input(InputType.Range) {
                classes("range")
                attr("min", "70"); attr("max", "99")
                attr("aria-label", "Score a free item has to reach")
                value(current.freeItemScorePct.toString())
                onInput { alerts = current.copy(freeItemScorePct = it.value?.toInt() ?: current.freeItemScorePct) }
                onChange { val next = alerts ?: current; save("alerts") { client.updateNotificationSettings(next) } }
            }
            Div({ classes("chips") }) {
                Span({ classes("chips-label") }) { Text("The server looks every") }
                listOf(15 to "15 min", 30 to "30 min", 60 to "hour", 120 to "2 hours", 240 to "4 hours").forEach { (minutes, label) ->
                    Chip(label, current.checkEveryMinutes == minutes) {
                        val next = current.copy(checkEveryMinutes = minutes)
                        alerts = next
                        save("alerts") { client.updateNotificationSettings(next) }
                    }
                }
            }
            SaveNote(notes.value["alerts"])
        }
    }
}

/** Whether this browser may show a notification, and the one control that asks it. */
@Composable
private fun BrowserPermission() {
    var permission by remember { mutableStateOf(Notification.permission) }
    when (permission) {
        NotificationPermission.GRANTED -> Span({ classes("muted", "small") }) { Text("This browser shows a new free item match while Arbay is open in a tab") }
        NotificationPermission.DEFAULT -> QuietButton("Show them in this browser", Glyph.Bell) {
            Notification.requestPermission().then { permission = it }
        }
        else -> Span({ classes("muted", "small") }) { Text("This browser blocks notifications from Arbay; its site settings can allow them") }
    }
}
