package io.github.tieo.arbay

import io.github.tieo.arbay.classifier.ModelArena
import kotlin.test.*

class ModelArenaTest {

    @BeforeTest
    fun setup() {
        ModelArena.clear()
    }

    @Test
    fun `correct prediction when model scores high and user loves`() {
        ModelArena.recordPredictions("item1", mapOf("modelA" to 0.8))
        ModelArena.recordOutcome("item1", "LOVE")
        val stats = ModelArena.getStats("modelA")!!
        assertEquals(1, stats.totalPredictions)
        assertEquals(1, stats.correctPredictions)
        assertEquals(1, stats.truePositives)
        assertEquals(1.0, stats.accuracy)
    }

    @Test
    fun `correct prediction when model scores low and user dislikes`() {
        ModelArena.recordPredictions("item1", mapOf("modelA" to 0.2))
        ModelArena.recordOutcome("item1", "DISLIKE")
        val stats = ModelArena.getStats("modelA")!!
        assertEquals(1, stats.correctPredictions)
        assertEquals(1, stats.trueNegatives)
    }

    @Test
    fun `false negative when model scores low but user loves`() {
        ModelArena.recordPredictions("item1", mapOf("modelA" to 0.2))
        ModelArena.recordOutcome("item1", "LOVE")
        val stats = ModelArena.getStats("modelA")!!
        assertEquals(0, stats.correctPredictions)
        assertEquals(1, stats.falseNegatives)
        assertEquals(0.0, stats.accuracy)
    }

    @Test
    fun `false positive when model scores high but user dislikes`() {
        ModelArena.recordPredictions("item1", mapOf("modelA" to 0.9))
        ModelArena.recordOutcome("item1", "DISLIKE")
        val stats = ModelArena.getStats("modelA")!!
        assertEquals(1, stats.falsePositives)
    }

    @Test
    fun `PASS actions are ignored`() {
        ModelArena.recordPredictions("item1", mapOf("modelA" to 0.8))
        ModelArena.recordOutcome("item1", "PASS")
        val stats = ModelArena.getStats("modelA")
        assertNull(stats) // no stats recorded for PASS
    }

    @Test
    fun `multiple models tracked independently`() {
        ModelArena.recordPredictions("item1", mapOf("modelA" to 0.8, "modelB" to 0.3))
        ModelArena.recordOutcome("item1", "LOVE")

        val statsA = ModelArena.getStats("modelA")!!
        val statsB = ModelArena.getStats("modelB")!!
        assertEquals(1, statsA.correctPredictions) // 0.8 >= 0.5, LOVE → correct
        assertEquals(0, statsB.correctPredictions) // 0.3 < 0.5, LOVE → wrong (false negative)
        assertEquals(1, statsB.falseNegatives)
    }

    @Test
    fun `leaderboard sorts by accuracy`() {
        // Model A: 4/5 correct
        for (i in 1..4) {
            ModelArena.recordPredictions("a$i", mapOf("modelA" to 0.8, "modelB" to 0.6))
            ModelArena.recordOutcome("a$i", "LOVE")
        }
        ModelArena.recordPredictions("a5", mapOf("modelA" to 0.8, "modelB" to 0.6))
        ModelArena.recordOutcome("a5", "DISLIKE")

        // Model B gets same items, same outcomes — 4 correct, 1 wrong
        // Both have 80% accuracy, but let's add one where they differ
        ModelArena.recordPredictions("b6", mapOf("modelA" to 0.3, "modelB" to 0.8))
        ModelArena.recordOutcome("b6", "LOVE")

        val board = ModelArena.leaderboard()
        assertEquals(2, board.size)
        // modelB: 5/6 correct = 83%, modelA: 4/6 = 67%
        assertEquals("modelB", board.first().modelId)
    }

    @Test
    fun `separation tracks score difference between loved and disliked`() {
        ModelArena.recordPredictions("l1", mapOf("m" to 0.8))
        ModelArena.recordOutcome("l1", "LOVE")
        ModelArena.recordPredictions("l2", mapOf("m" to 0.9))
        ModelArena.recordOutcome("l2", "LOVE")
        ModelArena.recordPredictions("d1", mapOf("m" to 0.3))
        ModelArena.recordOutcome("d1", "DISLIKE")
        ModelArena.recordPredictions("d2", mapOf("m" to 0.2))
        ModelArena.recordOutcome("d2", "DISLIKE")

        val stats = ModelArena.getStats("m")!!
        // avgLoved = (0.8+0.9)/2 = 0.85, avgDisliked = (0.3+0.2)/2 = 0.25, separation = 0.6
        assertTrue(stats.separation > 0.5, "Separation should be > 0.5, got ${stats.separation}")
    }

    @Test
    fun `worst predictions tracked for false negatives`() {
        ModelArena.recordPredictions("item1", mapOf("m" to 0.1))
        ModelArena.recordOutcome("item1", "LOVE") // confident wrong: score 0.1, actually loved
        val stats = ModelArena.getStats("m")!!
        assertTrue(stats.worstPredictions.isNotEmpty())
        assertEquals("item1", stats.worstPredictions.first().listingId)
    }

    @Test
    fun `leaderboard requires minimum 5 predictions`() {
        ModelArena.recordPredictions("i1", mapOf("m" to 0.8))
        ModelArena.recordOutcome("i1", "LOVE")
        assertTrue(ModelArena.leaderboard().isEmpty(), "Should need >= 5 predictions for leaderboard")
    }
}
