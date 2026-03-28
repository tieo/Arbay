package io.github.tieo.arbay.crawler

import io.github.tieo.arbay.model.*
import io.github.tieo.arbay.repo.AlertRepo
import io.github.tieo.arbay.repo.ListingRepo
import io.github.tieo.arbay.repo.ProductRepo
import kotlinx.coroutines.*
import kotlinx.datetime.Clock
import org.slf4j.LoggerFactory
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

class CrawlerScheduler(
    private val productRepo: ProductRepo,
    private val listingRepo: ListingRepo,
    private val alertRepo: AlertRepo,
    private val interval: Duration = 10.minutes,
) {
    private val log = LoggerFactory.getLogger(CrawlerScheduler::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    fun start() {
        job = scope.launch {
            log.info("Crawler scheduler started (interval={})", interval)
            while (isActive) {
                try {
                    crawlAll()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.error("Crawler cycle failed", e)
                }
                delay(interval)
            }
        }
    }

    fun stop() {
        job?.cancel()
        scope.cancel()
    }

    private suspend fun crawlAll() {
        val products = productRepo.getAll().filter { it.active }
        if (products.isEmpty()) {
            log.debug("No active products to crawl")
            return
        }

        log.info("Starting crawl cycle for {} active products", products.size)

        for (product in products) {
            val platforms = product.searchQuery.platforms
            for (platformId in platforms) {
                val crawler = CrawlerRegistry.crawlerFor(platformId)
                if (crawler == null) {
                    log.debug("No crawler for {}, skipping", platformId)
                    continue
                }

                try {
                    val listings = crawler.trackedSearch(product.searchQuery)

                    val existingIds = mutableSetOf<String>()
                    for (listing in listings) {
                        val existing = listingRepo.getById(listing.id)
                        existingIds.add(listing.id)

                        if (existing == null) {
                            listingRepo.upsert(listing)
                            generateNewListingAlert(product, listing)
                        } else {
                            listingRepo.upsert(listing)
                            if (listing.price.amount < existing.price.amount) {
                                generatePriceDropAlert(product, listing, existing.price)
                            }
                        }
                    }

                    checkAlertRules(product, listings)

                    // Be polite — wait between platforms
                    delay(2000)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.warn(
                        "Failed to crawl {} on {}: {}",
                        product.name, platformId.displayName, e.message,
                    )
                }
            }
            // Wait between products
            delay(1000)
        }

        log.info("Crawl cycle complete")
    }

    private fun generateNewListingAlert(product: TrackedProduct, listing: Listing) {
        val hasNewListingRule = product.alertRules.any { it.type == AlertType.NEW_LISTING }
        if (!hasNewListingRule && product.alertRules.isNotEmpty()) return

        alertRepo.create(
            Alert(
                id = "alert:${Clock.System.now().toEpochMilliseconds()}:${listing.id.hashCode()}",
                productId = product.id,
                listingId = listing.id,
                type = AlertType.NEW_LISTING,
                message = "${listing.title} — ${formatMoney(listing.price)} on ${listing.platformId.displayName}",
                createdAt = Clock.System.now(),
            ),
        )
    }

    private fun generatePriceDropAlert(product: TrackedProduct, listing: Listing, oldPrice: Money) {
        val dropPercent = ((oldPrice.amount - listing.price.amount) * 100) / oldPrice.amount

        alertRepo.create(
            Alert(
                id = "alert:${Clock.System.now().toEpochMilliseconds()}:${listing.id.hashCode()}:drop",
                productId = product.id,
                listingId = listing.id,
                type = AlertType.PRICE_DROP_PERCENT,
                message = "${listing.title} dropped ${dropPercent}% (${formatMoney(oldPrice)} → ${formatMoney(listing.price)})",
                createdAt = Clock.System.now(),
            ),
        )
    }

    private fun checkAlertRules(product: TrackedProduct, listings: List<Listing>) {
        for (rule in product.alertRules) {
            when (rule.type) {
                AlertType.PRICE_BELOW -> {
                    val threshold = rule.threshold ?: continue
                    listings.filter { it.price.amount <= threshold }.forEach { listing ->
                        alertRepo.create(
                            Alert(
                                id = "alert:${Clock.System.now().toEpochMilliseconds()}:${listing.id.hashCode()}:below",
                                productId = product.id,
                                listingId = listing.id,
                                type = AlertType.PRICE_BELOW,
                                message = "${listing.title} is ${formatMoney(listing.price)} (below ${formatMoney(Money(threshold))})",
                                createdAt = Clock.System.now(),
                            ),
                        )
                    }
                }
                AlertType.ARBITRAGE -> {
                    detectArbitrage(product, listings)
                }
                else -> {}
            }
        }
    }

    private fun detectArbitrage(product: TrackedProduct, listings: List<Listing>) {
        if (listings.size < 2) return

        val byPlatform = listings.groupBy { it.platformId }
        if (byPlatform.size < 2) return

        val cheapest = listings.minByOrNull { it.price.amount } ?: return
        val mostExpensive = listings.filter { it.platformId != cheapest.platformId }
            .maxByOrNull { it.price.amount } ?: return

        val spread = mostExpensive.price.amount - cheapest.price.amount
        if (spread > cheapest.price.amount / 10) { // >10% spread
            alertRepo.create(
                Alert(
                    id = "alert:${Clock.System.now().toEpochMilliseconds()}:arb:${product.id}",
                    productId = product.id,
                    listingId = cheapest.id,
                    type = AlertType.ARBITRAGE,
                    message = "Buy on ${cheapest.platformId.displayName} (${formatMoney(cheapest.price)}) → sell on ${mostExpensive.platformId.displayName} (${formatMoney(mostExpensive.price)})",
                    createdAt = Clock.System.now(),
                ),
            )
        }
    }

    private fun formatMoney(money: Money): String {
        val symbol = when (money.currency) {
            Currency.EUR -> "€"
            Currency.USD -> "$"
            Currency.CHF -> "CHF "
            Currency.GBP -> "£"
        }
        val whole = money.amount / 100
        val cents = money.amount % 100
        return if (cents == 0L) "$symbol$whole" else "$symbol$whole.%02d".format(cents)
    }
}
