package io.github.tieo.arbay.classifier

import io.github.tieo.arbay.DataDir
import io.github.tieo.arbay.model.FreeItemProfile
import io.github.tieo.arbay.repo.readStore
import io.github.tieo.arbay.repo.writeTextAtomically
import java.io.File
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

object FreeItemProfileStore {

    private val log = LoggerFactory.getLogger(FreeItemProfileStore::class.java)
    private val profileFile = DataDir.file("free_item_profile.json")
    private val legacyProfileFile = DataDir.file("free_item_profile.txt")
    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var cachedProfile: FreeItemProfile? = null

    @Volatile
    private var cachedEmbedding: FloatArray? = null

    fun get(): FreeItemProfile? {
        if (cachedProfile != null) return cachedProfile
        return try {
            when {
                profileFile.exists() ->
                    profileFile.readStore(log) { json.decodeFromString<FreeItemProfile>(it) }
                        ?.also { cachedProfile = it }
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

    private fun save(profile: FreeItemProfile) = synchronized(profileFile) {
        try {
            profileFile.writeTextAtomically(json.encodeToString(profile))
        } catch (e: Exception) {
            log.error("Could not save free item profile: {}", e.message)
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
