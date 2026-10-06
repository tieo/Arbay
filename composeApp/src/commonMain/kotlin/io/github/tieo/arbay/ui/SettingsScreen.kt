package io.github.tieo.arbay.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
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
import io.github.tieo.arbay.schedulePolling
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Settings in two halves: how this phone shows things, which only this phone keeps, and what the
 * server does for every device that uses it. Each server setting is sent as it is changed, and says
 * beside it whether the server took it.
 */
@Composable
fun SettingsScreen(session: Session) {
    Column(
        Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = arbay.pad, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(arbay.gap),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall)
        SectionTitle("This phone", Modifier.padding(top = 8.dp))
        Setting("Look") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Looks.all.forEach { look -> LookSample(look, LookChoice.look.id == look.id, Modifier.weight(1f)) }
            }
            Choices { Brightness.entries.forEach { b -> Choice(b.label, LookChoice.brightness == b) { LookChoice.choose(b) } } }
            Choices { OfferLayout.entries.forEach { l -> Choice("Offers as ${l.label.lowercase()}", LookChoice.layout == l) { LookChoice.choose(l) } } }
        }
        Setting("Currency", "Offers from other markets are converted into it") {
            Choices {
                listOf("EUR", "USD", "GBP", "CHF").forEach { c ->
                    Choice(c, DisplayCurrency.current == c) {
                        DisplayCurrency.current = c
                        runCatching { saveDeviceSettings(loadDeviceSettings() + ("currency" to c)) }
                    }
                }
            }
        }
        ServerAddress(session)
        SectionTitle("The server, for every device", Modifier.padding(top = 8.dp))
        ServerSettings(session)
    }
}

@Composable
private fun Setting(title: String, detail: String? = null, content: @Composable () -> Unit) {
    Panel {
        Text(title, style = MaterialTheme.typography.titleMedium)
        detail?.let { Muted(it) }
        Gap(4.dp)
        content()
    }
}

/** A look drawn in its own colours, so the choice is made by seeing it. */
@Composable
private fun LookSample(look: Look, chosen: Boolean, modifier: Modifier) {
    val p = if (arbay.dark) look.dark else look.light
    val r = look.radius.dp
    Column(
        modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(r)).background(color(p.background))
            .border(2.dp, if (chosen) MaterialTheme.colorScheme.primary else color(p.line), androidx.compose.foundation.shape.RoundedCornerShape(r))
            .clickable { LookChoice.choose(look) }.padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(look.name, color = color(p.text), style = MaterialTheme.typography.labelLarge)
        Text(
            "€ 1.240",
            color = color(p.lowest),
            fontWeight = FontWeight.Bold,
            fontFamily = if (look.monoPrices) FontFamily.Monospace else null,
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            "Save", color = color(p.onAccent), style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(r)).background(color(p.accent)).padding(horizontal = 10.dp, vertical = 3.dp),
        )
    }
}

/** Where this phone finds its server; the address is kept on the phone. */
@Composable
private fun ServerAddress(session: Session) {
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf(session.client.baseUrl) }
    var note by remember { mutableStateOf<String?>(null) }
    Setting("Server") {
        OutlinedTextField(url, { url = it; note = null }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Address") })
        Row(verticalAlignment = Alignment.CenterVertically) {
            note?.let { Muted(it, Modifier.weight(1f)) } ?: Gap()
            if (url.trimEnd('/') != session.client.baseUrl) {
                androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                TextButton(onClick = {
                    runCatching { session.client.updateBaseUrl(url) }
                    scope.launch {
                        note = try { session.client.getProducts(); session.products.loadProducts(); "Connected" }
                        catch (e: CancellationException) { throw e }
                        catch (e: Exception) { "Not reached: ${e.message ?: "no answer"}" }
                    }
                }) { Text("Use this server") }
            }
        }
    }
}

@Composable
private fun ServerSettings(session: Session) {
    val client = session.client
    val scope = rememberCoroutineScope()
    var countries by remember { mutableStateOf(SearchCountries.current.countries) }
    var rules by remember { mutableStateOf(ImportRules.current) }
    var depth by remember { mutableStateOf<Int?>(null) }
    var alerts by remember { mutableStateOf<NotificationSettings?>(null) }
    var notes by remember { mutableStateOf<Map<String, String>>(emptyMap()) }

    LaunchedEffect(Unit) {
        runCatching { client.getMarketSettings() }.onSuccess { countries = it.countries; SearchCountries.current = it }
        runCatching { client.getImportSettings() }.onSuccess { rules = it; ImportRules.current = it }
        runCatching { client.getCrawlerConfig() }.onSuccess { depth = it.maxResultsPerPlatform }
        alerts = client.getNotificationSettings()
    }

    fun save(key: String, block: suspend () -> Unit) {
        scope.launch {
            val outcome = try { block(); "Saved" } catch (e: CancellationException) { throw e } catch (e: Exception) { "Not saved: ${e.message ?: "the server did not answer"}" }
            notes = notes + (key to outcome)
        }
    }

    Setting("Countries a search covers", "A search that names no markets of its own asks the markets in these") {
        val available = remember { PlatformId.entries.map { MarketSets.countryOf(it) }.distinct().sorted() }
        Choices {
            available.forEach { c ->
                Choice("${flagEmoji(c)} $c", c in countries) {
                    countries = if (c in countries) countries - c else countries + c
                    val next = MarketSettings(countries)
                    save("countries") { SearchCountries.current = client.updateMarketSettings(next) }
                }
            }
        }
        Muted("${MarketSets.platformsIn(MarketGroup.GENERAL, countries).size} markets for things, ${MarketSets.platformsIn(MarketGroup.VEHICLES, countries).size} for vehicles" + (notes["countries"]?.let { " · $it" } ?: ""))
    }

    Setting("Buying from abroad", "A market outside your VAT area quotes its price without the import VAT due on the way in") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Add import VAT to prices from abroad", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Switch(rules.enabled, { rules = rules.copy(enabled = it); val next = rules; save("import") { ImportRules.current = client.updateImportSettings(next) } })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(rules.homeCountry, { rules = rules.copy(homeCountry = it.uppercase().filter(Char::isLetter).take(2)) }, Modifier.weight(1f), label = { Text("You are in") }, singleLine = true)
            OutlinedTextField(rules.importVatPercent.toString(), { rules = rules.copy(importVatPercent = it.filter(Char::isDigit).take(2).toIntOrNull() ?: 0) }, Modifier.weight(1f), label = { Text("Import VAT %") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            TextButton(onClick = { val next = rules; save("import") { ImportRules.current = client.updateImportSettings(next) } }) { Text("Save") }
        }
        notes["import"]?.let { Muted(it) }
    }

    Setting("Search depth", "Each market is read until it has given this many offers; deeper is slower") {
        Choices {
            (listOf(30, 60, 120, 250) + listOfNotNull(depth)).distinct().sorted().forEach { n ->
                Choice("$n", depth == n) { depth = n; save("depth") { client.updateCrawlerConfig(n) } }
            }
        }
        notes["depth"]?.let { Muted(it) }
    }

    alerts?.let { current ->
        Setting("Notifications", "Only what is gone if you wait: a free item near you, and the rules set on a saved search") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("A free item near you scoring at least ${current.freeItemScorePct}%", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Switch(current.freeItemAlerts, { on -> val next = current.copy(freeItemAlerts = on); alerts = next; save("alerts") { client.updateNotificationSettings(next) } })
            }
            Slider(
                current.freeItemScorePct.toFloat(), { alerts = current.copy(freeItemScorePct = it.toInt()) },
                valueRange = 70f..99f, steps = 28,
                onValueChangeFinished = { val next = alerts ?: current; save("alerts") { client.updateNotificationSettings(next) } },
            )
            Muted("The phone asks the server every")
            Choices {
                listOf(15 to "15 min", 30 to "30 min", 60 to "hour", 120 to "2 hours", 240 to "4 hours").forEach { (m, l) ->
                    Choice(l, current.checkEveryMinutes == m) {
                        val next = current.copy(checkEveryMinutes = m)
                        alerts = next
                        save("alerts") { client.updateNotificationSettings(next); schedulePolling(m) }
                    }
                }
            }
            notes["alerts"]?.let { Muted(it) }
        }
    }
}
