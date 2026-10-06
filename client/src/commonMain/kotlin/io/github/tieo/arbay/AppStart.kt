package io.github.tieo.arbay

import io.github.tieo.arbay.api.ArbayClient
import kotlinx.coroutines.CancellationException

/**
 * What every screen reads and the server holds, fetched once when the app starts: the exchange
 * rates prices are converted with, the import VAT a listing from outside the buyer's VAT area pays
 * (so the app and the server's own notification filters use the same number), the countries a
 * search covers (the same setting the server crawls by), and the car makes and models the vehicle
 * search offers. Each is fetched on its own, so one the server cannot give leaves the others.
 */
suspend fun loadServerSettings(client: ArbayClient) {
    suspend fun attempt(read: suspend () -> Unit) {
        try { read() } catch (e: CancellationException) { throw e } catch (_: Exception) {}
    }
    attempt { DisplayCurrency.rates = client.getExchangeRates() }
    attempt { ImportRules.current = client.getImportSettings() }
    attempt { SearchCountries.current = client.getMarketSettings() }
    attempt { CarTaxonomyStore.update(client.getCarTaxonomy()) }
}

/** Where the reader is when the device will not say: the home town from the free-items profile,
 *  so a distance is worth showing whether or not anyone granted a location permission. */
suspend fun positionFromHomeTown(client: ArbayClient) {
    if (DevicePosition.latitude != null) return
    try {
        client.getFreeItemProfile()?.location?.takeIf { it.isNotBlank() }?.let { home ->
            client.geocode(home)?.let { (lat, lon) -> DevicePosition.set(lat, lon) }
        }
    } catch (e: CancellationException) { throw e } catch (_: Exception) {}
}
