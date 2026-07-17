package io.github.tieo.arbay

import io.github.tieo.arbay.crawler.Translator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TranslatorTest {

    @Test
    fun parsesSingleSegmentGtxResponse() {
        // gtx shape: [[["levigatrice per parquet","Parkettschleifmaschine",null,null,10]],null,"de"]
        val body = """[[["levigatrice per parquet","Parkettschleifmaschine",null,null,10]],null,"de"]"""
        assertEquals("levigatrice per parquet", Translator.parseFirstSegment(body))
    }

    @Test
    fun concatenatesMultipleSegments() {
        val body = """[[["Hello ","Hallo "],["world","Welt"]],null,"de"]"""
        assertEquals("Hello world", Translator.parseFirstSegment(body))
    }

    @Test
    fun emptyOrGarbageYieldsNull() {
        assertNull(Translator.parseFirstSegment("""[[],null,"de"]"""))
    }
}
