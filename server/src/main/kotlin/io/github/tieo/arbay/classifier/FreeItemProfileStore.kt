package io.github.tieo.arbay.classifier

import io.github.tieo.arbay.model.FreeItemProfile
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File

object FreeItemProfileStore {

    private val log = LoggerFactory.getLogger(FreeItemProfileStore::class.java)
    private val profileFile = File(System.getProperty("user.home"), ".arbay/free_item_profile.json")
    private val legacyProfileFile = File(System.getProperty("user.home"), ".arbay/free_item_profile.txt")
    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var cachedProfile: FreeItemProfile? = null

    @Volatile
    private var cachedEmbedding: FloatArray? = null

    fun get(): FreeItemProfile? {
        if (cachedProfile != null) return cachedProfile
        return try {
            when {
                profileFile.exists() -> {
                    val profile = json.decodeFromString<FreeItemProfile>(profileFile.readText())
                    profile.also { cachedProfile = it }
                }
                legacyProfileFile.exists() -> {
                    // Migrate from plain-text format
                    val text = legacyProfileFile.readText().trim()
                    if (text.isBlank()) null
                    else FreeItemProfile(text).also { cachedProfile = it; save(it) }
                }
                else -> null
            }
        } catch (e: Exception) {
            log.warn("Could not read free item profile: ${e.message}")
            null
        }
    }

    fun set(profile: FreeItemProfile) {
        cachedProfile = profile
        cachedEmbedding = null
        save(profile)
    }

    private fun save(profile: FreeItemProfile) {
        try {
            profileFile.parentFile.mkdirs()
            profileFile.writeText(json.encodeToString(profile))
        } catch (e: Exception) {
            log.warn("Could not save free item profile: ${e.message}")
        }
    }

    /** Cached embedding of the profile text — only recomputed when profile changes. */
    fun getEmbedding(): FloatArray? {
        val profile = get() ?: return null
        if (profile.description.isBlank()) return null // no interests stated → no profile signal
        cachedEmbedding?.let { return it }
        return FreeItemScorer.embedProfile(profile.description)?.also { cachedEmbedding = it }
    }
}
