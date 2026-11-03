package io.github.tieo.arbay.crawler

import io.github.tieo.arbay.model.*
import io.github.tieo.arbay.neuland.NeulandOffers
import kotlinx.coroutines.CancellationException

/**
 * ricardo.ch, Switzerland's largest general marketplace and the only Swiss source with a fetchable
 * search for vehicles too, read by Neuland. A car query is searched by its make and model words;
 * ricardo has no vehicle category in its search, so on a car query what it files as anything but
 * a whole vehicle (floor mats, a licence-plate light, a keychain for a Crafter) is left out. A
 * product search keeps every kind: its machines are filed as grinding_machine and the like.
 *
 * autoscout24.ch is not used: none of its search addresses applies the make/model filter and it
 * issues no client-side listings request, so no fetchable address yields filtered Swiss results.
 */
class RicardoCrawler : Crawler, StartsAtAnyPage, FetchesEveryPage, KnowsLocation, SellsByAuction {
    override val platformId = PlatformId.RICARDO

    override suspend fun search(query: SearchQuery): List<Listing> {
        val car = CarQueryResolver.resolve(query.positiveText)
        val words = car?.let { listOfNotNull(it.makeSlug.replace("-", " "), it.modelSlug).joinToString(" ") } ?: query.positiveText
        return paginate(query) { page ->
            NeulandOffers.search(
                "ricardo.ch", words, platformId, "ricardo.ch", page, max = query.maxPrice,
                otherwise = null, kinds = VEHICLE_KINDS.takeIf { car != null },
            )
        }
    }

    /** The seller's text and every photo of the gallery, off the item page. */
    override suspend fun fetchDetail(listing: Listing): ListingDetail? = try {
        val page = NeulandOffers.detail(listing.url, "ricardo.ch")
        ListingDetail(description = page.description, photos = page.photos).takeIf { it.description != null || it.photos.isNotEmpty() }
    } catch (e: CancellationException) { throw e } catch (_: Exception) {
        null
    }

    private companion object {
        /** ricardo's kinds that are a whole vehicle; everything else it files (auto_part, car_mat,
         *  wheel, rim, trailer_coupling, keychain, ...) is an accessory. */
        val VEHICLE_KINDS = setOf("car", "commercial_vehicle", "truck", "caravan", "motorhome", "camper", "van", "bus", "oldtimer")
    }
}
