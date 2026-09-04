package io.github.tieo.arbay.repo

import io.github.tieo.arbay.crawler.CrawlerRegistry
import io.github.tieo.arbay.model.Listing
import io.ktor.client.call.*
import io.ktor.client.request.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * A durable, offline copy of every listing this server has ever fetched — the listing's own
 * fields plus its photos, mirrored to disk the first time it is seen. A platform deleting an ad
 * takes the live page and its images with it; this is what still shows the ad afterward.
 *
 * Written once per listing id, never re-fetched: the point is what the ad looked like when it
 * was found, not a live mirror that goes stale the same way the platform's own copy does. Called
 * from [ListingRepo.upsert] — every listing any crawl (interactive or background) ever returns
 * passes through there, so this needs no separate wiring per search path.
 */
object ListingArchive {

    private val log = LoggerFactory.getLogger(ListingArchive::class.java)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val root = File(System.getProperty("user.home"), ".arbay/archive")
    private val listingsDir = File(root, "listings")
    private val imagesDir = File(root, "images")

    // What has been archived already (or is being archived right now), so a listing seen many
    // times across concurrent requests is only ever written once. Seeded from disk at startup.
    private val known: MutableSet<String> = ConcurrentHashMap.newKeySet<String>().also { set ->
        listingsDir.listFiles { f -> f.extension == "json" }?.forEach { set.add(it.nameWithoutExtension) }
    }

    /** Whether this listing has already been archived (or an archive attempt is in flight). */
    fun isArchived(id: String): Boolean = known.contains(id)

    /** Archive a listing in the background if it hasn't been already. Never blocks the caller —
     *  a live search response must not wait on image downloads. */
    fun archiveAsync(listing: Listing) {
        if (!known.add(listing.id)) return
        scope.launch {
            try {
                archiveNow(listing)
            } catch (e: Exception) {
                known.remove(listing.id) // let a failed attempt retry next time this listing is seen
                log.debug("Archive failed for {}: {}", listing.id, e.message)
            }
        }
    }

    private suspend fun archiveNow(listing: Listing) {
        val dir = File(imagesDir, listing.id)
        dir.mkdirs()
        val localNames = listing.imageUrls.mapIndexedNotNull { index, url -> downloadImage(url, dir, index) }
        val archived = listing.copy(
            imageUrls = localNames.map { "/api/archive/images/${listing.id}/$it" },
        )
        listingsDir.mkdirs()
        File(listingsDir, "${listing.id}.json").writeText(json.encodeToString(archived))
        log.debug("Archived {} ({} of {} images saved)", listing.id, localNames.size, listing.imageUrls.size)
    }

    private suspend fun downloadImage(url: String, dir: File, index: Int): String? = try {
        val ext = url.substringAfterLast('.', "jpg").substringBefore('?').take(4)
            .takeIf { it.isNotBlank() && it.all { c -> c.isLetterOrDigit() } } ?: "jpg"
        val bytes: ByteArray = CrawlerRegistry.httpClient.get(url).body()
        if (bytes.isEmpty()) return null
        val name = "$index.$ext"
        File(dir, name).writeBytes(bytes)
        name
    } catch (e: Exception) {
        null
    }

    /** The archived copy of one listing, or null if it was never archived (or archiving is still
     *  in flight for it). */
    fun get(id: String): Listing? {
        val file = File(listingsDir, "$id.json")
        if (!file.exists()) return null
        return try {
            json.decodeFromString<Listing>(file.readText())
        } catch (e: Exception) {
            log.warn("Could not read archived listing {}: {}", id, e.message)
            null
        }
    }

    /** One of a listing's mirrored images, addressed by the filename [get] handed back in its
     *  imageUrls. Guards against a filename walking out of that listing's own directory. */
    fun imageFile(id: String, filename: String): File? {
        val listingDir = File(imagesDir, id)
        val file = File(listingDir, filename)
        if (!file.exists()) return null
        val listingCanonical = listingDir.canonicalFile
        return file.takeIf { it.canonicalFile.parentFile == listingCanonical }
    }
}
