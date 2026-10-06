package io.github.tieo.arbay

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.tieo.arbay.model.ImportSettings
import io.github.tieo.arbay.model.MarketSettings

/** Opens an address in the device's browser: a listing on its market, a page the app links to. */
expect fun openBrowser(url: String)

// Device storage. Android and the desktop build keep these on disk, written whole and reporting a
// write that failed by throwing; the browser keeps them in the site's local storage. iOS does not
// keep anything: its actuals are empty stubs, so there every load returns nothing and every save is
// dropped. Nothing ships for iOS; code that works on Android is no proof that storage works there.

expect fun loadBannedIds(): Set<String>
expect fun saveBannedIds(ids: Set<String>)

/** Raw JSON for the search history (see history/SearchHistory.kt), or "" if none is stored yet.
 *  Kept as one opaque blob rather than a key-value setting, since it is a list of structured
 *  entries and not a flat set of preferences. */
expect fun loadSearchHistory(): String
expect fun saveSearchHistory(json: String)

/** Settings that belong to this device rather than to the server it talks to: which server that is,
 *  and which currency to show prices in. Kept here so a choice made in Settings survives a restart. */
expect fun loadDeviceSettings(): Map<String, String>
expect fun saveDeviceSettings(settings: Map<String, String>)

/** Show a local notification for new free item matches. No-op on unsupported platforms. */
expect fun showMatchNotification(title: String, body: String)

/**
 * Where the buyer is and what import VAT they pay, as the server holds it.
 *
 * Observable for the same reason the currency is: prices are on screen while this arrives, and a
 * price worked out under the old value must not stay there wearing the new one.
 */
object ImportRules {
    var current: ImportSettings by mutableStateOf(ImportSettings())
}

/** Where the reader is, for measuring a listing against. The device's own position where the app
 *  is allowed to read it, and the home town from the free-items profile otherwise, since a distance
 *  is worth showing whether or not anyone granted a location permission. */
object DevicePosition {
    var latitude: Double? by mutableStateOf(null)
    var longitude: Double? by mutableStateOf(null)

    fun set(lat: Double?, lon: Double?) {
        if (lat == null || lon == null) return
        latitude = lat
        longitude = lon
    }
}

/** The countries a search covers when it names no markets of its own. Observable: changing it in
 *  settings changes which markets the next search asks, and the search screens say so on screen. */
object SearchCountries {
    var current: MarketSettings by mutableStateOf(MarketSettings())
}



object DisplayCurrency {
    // Observable, because both of these change while prices are on screen: the currency when it is
    // switched in settings, and the rates when the live ones arrive from the server a moment after
    // start. Held as plain fields, a screen kept showing amounts worked out from the old ones and
    // relabelled them with the new symbol.
    var current: String by mutableStateOf(loadDeviceSettings()["currency"] ?: "EUR")
    // Overwritten at startup by the server's live rates; until then, the shared approximations
    // both sides use, so a price does not depend on which side worked it out.
    var rates: Map<String, Double> by mutableStateOf(FALLBACK_RATES_PER_EUR)

    /** True when the amount can be shown in [current] without mislabelling — both currencies
     *  have a known rate (or they are the same). */
    fun canConvert(fromCurrency: String): Boolean =
        fromCurrency == current || (rates.containsKey(fromCurrency) && rates.containsKey(current))

    fun convert(amountCents: Long, fromCurrency: String): Long {
        if (fromCurrency == current) return amountCents
        val fromRate = rates[fromCurrency] ?: return amountCents
        val toRate = rates[current] ?: return amountCents
        return (amountCents.toDouble() / fromRate * toRate).toLong()
    }
}
