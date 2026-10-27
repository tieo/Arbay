package io.github.tieo.arbay.model

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class RepostTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `an ad kept before reposting existed goes up again every week`() {
        val stored = """{"id":"d1","categoryId":"173","title":"Pixel","description":"Gut","postedAdId":"3541873903","postedAt":"2026-10-15T16:40:59Z"}"""
        val d = json.decodeFromString(AdDraft.serializer(), stored)
        assertEquals(7, d.repostEveryDays)
        assertEquals(Instant.parse("2026-10-22T16:40:59Z"), d.nextRepost())
    }

    @Test
    fun `an ad left as posted has no next time, and a draft none before it is posted`() {
        val posted = AdDraft("d1", categoryId = "173", title = "t", description = "x", postedAdId = "1", postedAt = Instant.parse("2026-10-15T00:00:00Z"))
        assertNull(posted.copy(repostEveryDays = null).nextRepost())
        assertNull(posted.copy(postedAdId = null, postedAt = null).nextRepost())
    }

    @Test
    fun `buyers who wrote under an earlier id write about the same ad`() {
        val d = AdDraft("d1", categoryId = "173", title = "t", description = "x", postedAdId = "2", earlierAdIds = listOf("1"))
        assertTrue(d.isAd("1"))
        assertTrue(d.isAd("2"))
        assertFalse(d.isAd("3"))
    }
}
