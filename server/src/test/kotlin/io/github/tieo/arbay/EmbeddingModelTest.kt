package io.github.tieo.arbay

import io.github.tieo.arbay.classifier.EmbeddingModel
import kotlin.math.sqrt
import kotlin.test.*

class EmbeddingModelTest {

    // ── similarity() — pure math, no model needed ──────────────────────────────

    @Test
    fun `similarity of identical normalized vectors is 1`() {
        val raw = FloatArray(512) { (it + 1).toFloat() }
        val v = normalize(raw)
        val result = EmbeddingModel.similarity(v, v)
        assertEquals(1.0, result, 1e-5, "Identical vectors should have similarity 1.0")
    }

    @Test
    fun `similarity of opposite normalized vectors is -1`() {
        val raw = FloatArray(512) { (it + 1).toFloat() }
        val v = normalize(raw)
        val neg = FloatArray(512) { -v[it] }
        val result = EmbeddingModel.similarity(v, neg)
        assertEquals(-1.0, result, 1e-5, "Opposite vectors should have similarity -1.0")
    }

    @Test
    fun `similarity of orthogonal vectors is 0`() {
        val a = FloatArray(512) { if (it == 0) 1f else 0f }
        val b = FloatArray(512) { if (it == 1) 1f else 0f }
        val result = EmbeddingModel.similarity(a, b)
        assertEquals(0.0, result, 1e-5, "Orthogonal unit vectors should have similarity 0.0")
    }

    @Test
    fun `similarity result is clamped to -1 to 1`() {
        // Even with floating point noise, result stays in [-1, 1]
        val v = FloatArray(512) { 1f }
        val result = EmbeddingModel.similarity(v, v)
        assertTrue(result >= -1.0 && result <= 1.0, "Similarity must be in [-1, 1], got $result")
    }

    // ── embed() — requires downloaded model ────────────────────────────────────

    @Test
    fun `model is available`() {
        assertTrue(EmbeddingModel.isAvailable, "ONNX model should be downloaded and loadable")
    }

    @Test
    fun `embed returns a fixed-dimensional vector`() {
        val v = EmbeddingModel.embed("test text")
        assertNotNull(v, "embed should return a vector when model is available")
        assertTrue(v.size > 64, "Expected embedding dimension > 64, got ${v.size}")
    }

    @Test
    fun `embed returns L2-normalized vector (norm close to 1)`() {
        val v = EmbeddingModel.embed("some test sentence for normalization check")
        assertNotNull(v)
        val norm = sqrt(v.map { it * it.toDouble() }.sum())
        assertEquals(1.0, norm, 1e-4, "Embedding vector should be L2-normalized, norm was $norm")
    }

    @Test
    fun `different texts produce different embeddings`() {
        val a = EmbeddingModel.embed("monitor display screen")
        val b = EmbeddingModel.embed("bicycle wheel tire chain")
        assertNotNull(a); assertNotNull(b)
        assertFalse(a.zip(b.toList()).all { (x, y) -> x == y }, "Different texts should yield different embeddings")
    }

    @Test
    fun `same text produces same embedding (deterministic)`() {
        val text = "Samsung 27 inch monitor"
        val a = EmbeddingModel.embed(text)
        val b = EmbeddingModel.embed(text)
        assertNotNull(a); assertNotNull(b)
        for (i in a.indices) {
            assertEquals(a[i], b[i], "Embedding should be deterministic for the same input")
        }
    }

    // ── Semantic ordering — core value proposition ──────────────────────────────

    @Test
    fun `electronics profile is more similar to monitor than to bicycle`() {
        val profile = EmbeddingModel.embed("electronics monitors keyboards home office setup")!!
        val monitor = EmbeddingModel.embed("27 Zoll Monitor Samsung Full HD Display")!!
        val bicycle = EmbeddingModel.embed("Fahrrad Mountainbike Reifen Kette Sattel")!!

        val simMonitor = EmbeddingModel.similarity(profile, monitor)
        val simBicycle = EmbeddingModel.similarity(profile, bicycle)

        assertTrue(
            simMonitor > simBicycle,
            "Electronics profile should be closer to monitor ($simMonitor) than bicycle ($simBicycle)"
        )
    }

    @Test
    fun `cycling profile is more similar to bicycle than to monitor`() {
        val profile = EmbeddingModel.embed("cycling bikes outdoor sports Fahrrad")!!
        val bicycle = EmbeddingModel.embed("Fahrrad Mountainbike gebraucht guter Zustand")!!
        val monitor = EmbeddingModel.embed("Samsung Monitor 27 Zoll HDMI DisplayPort")!!

        val simBicycle = EmbeddingModel.similarity(profile, bicycle)
        val simMonitor = EmbeddingModel.similarity(profile, monitor)

        assertTrue(
            simBicycle > simMonitor,
            "Cycling profile should be closer to bicycle ($simBicycle) than monitor ($simMonitor)"
        )
    }

    @Test
    fun `empty string embeds without crashing`() {
        val v = EmbeddingModel.embed("")
        // May return null or a valid vector, but must not throw
        if (v != null) assertTrue(v.isNotEmpty())
    }

    @Test
    fun `german text embeds without crashing and produces valid vector`() {
        // Multilingual model supports German natively.
        val german = EmbeddingModel.embed("Schreibtisch Büro Heimarbeitsplatz")
        assertNotNull(german, "German text should embed without error")
        assertTrue(german.isNotEmpty())
        // L2 norm should still be ~1.0
        val norm = kotlin.math.sqrt(german.map { it * it.toDouble() }.sum())
        assertEquals(1.0, norm, 0.01, "German embedding should be L2-normalized")
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private fun normalize(v: FloatArray): FloatArray {
        val norm = sqrt(v.map { it * it.toDouble() }.sum()).toFloat()
        return FloatArray(v.size) { v[it] / norm }
    }
}
