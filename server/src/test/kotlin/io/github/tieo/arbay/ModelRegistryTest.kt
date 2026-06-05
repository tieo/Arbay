package io.github.tieo.arbay

import io.github.tieo.arbay.classifier.*
import kotlin.test.*

class ModelRegistryTest {

    private fun dummyContext() = ScoringContext(
        profileEmbedding = null,
        profileText = null,
        lovedEmbeddings = emptyList(),
        dislikedEmbeddings = emptyList(),
    )

    private fun constantModel(modelId: String, constantScore: Double) = object : ScoringModel {
        override val id = modelId
        override val name = "Constant $constantScore"
        override val trainable = false
        override fun score(listingEmbedding: FloatArray, listingText: String, context: ScoringContext) = constantScore
    }

    @BeforeTest
    fun setup() {
        ModelRegistry.clear()
    }

    @Test
    fun `register and retrieve model`() {
        val model = constantModel("test", 0.5)
        ModelRegistry.register(model)
        assertSame(model, ModelRegistry.get("test"))
    }

    @Test
    fun `get returns null for unknown model`() {
        assertNull(ModelRegistry.get("nonexistent"))
    }

    @Test
    fun `allModels returns all registered`() {
        ModelRegistry.register(constantModel("a", 0.1))
        ModelRegistry.register(constantModel("b", 0.2))
        ModelRegistry.register(constantModel("c", 0.3))
        assertEquals(3, ModelRegistry.allModels().size)
    }

    @Test
    fun `scoreAll returns scores from all models`() {
        ModelRegistry.register(constantModel("low", 0.2))
        ModelRegistry.register(constantModel("high", 0.9))
        val scores = ModelRegistry.scoreAll(FloatArray(512), "", dummyContext())
        assertEquals(0.2, scores["low"])
        assertEquals(0.9, scores["high"])
    }

    @Test
    fun `scoreAll handles model failure gracefully`() {
        ModelRegistry.register(constantModel("good", 0.5))
        ModelRegistry.register(object : ScoringModel {
            override val id = "broken"
            override val name = "Broken"
            override val trainable = false
            override fun score(listingEmbedding: FloatArray, listingText: String, context: ScoringContext): Double {
                throw RuntimeException("boom")
            }
        })
        val scores = ModelRegistry.scoreAll(FloatArray(512), "", dummyContext())
        assertEquals(0.5, scores["good"])
        assertEquals(0.0, scores["broken"]) // failed model returns 0
    }

    @Test
    fun `setActive changes active model`() {
        ModelRegistry.register(constantModel("a", 0.1))
        ModelRegistry.register(constantModel("b", 0.2))
        ModelRegistry.setActive("b")
        assertEquals("b", ModelRegistry.activeModelId)
        assertEquals("Constant 0.2", ModelRegistry.activeModel()?.name)
    }

    @Test
    fun `setActive throws for unknown model`() {
        assertFailsWith<IllegalArgumentException> {
            ModelRegistry.setActive("nonexistent")
        }
    }

    @Test
    fun `default active model is logistic`() {
        assertEquals("logistic", ModelRegistry.activeModelId)
    }

    @Test
    fun `clear removes all models and resets active`() {
        ModelRegistry.register(constantModel("test", 0.5))
        ModelRegistry.setActive("test")
        ModelRegistry.clear()
        assertTrue(ModelRegistry.allModels().isEmpty())
        assertEquals("logistic", ModelRegistry.activeModelId)
    }

    @Test
    fun `retrainAll only trains trainable models`() {
        var trainCalled = false
        ModelRegistry.register(object : ScoringModel {
            override val id = "static"
            override val name = "Static"
            override val trainable = false
            override fun score(listingEmbedding: FloatArray, listingText: String, context: ScoringContext) = 0.5
            override fun train(data: List<TrainingExample>): TrainResult {
                trainCalled = true
                return TrainResult(0, 0.0)
            }
        })
        ModelRegistry.retrainAll()
        assertFalse(trainCalled, "Non-trainable model should not be trained")
    }
}
