package com.theleftbit.skipspm

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IcuTrimTest {

    private val enEs = listOf("en", "es")

    @Test
    fun `keeps listed languages, root data and every locale's main data`() {
        listOf(
            "coll/root.res", "coll/en.res", "coll/es_MX.res", "rbnf/en.res", "rbnf/es.res",
            "lang/pool.res", "zone/res_index.res", "curr/en_GB.res", "unit/supplementalData.res",
            "fr.res", "zh_Hant_TW.res", "ucadata.icu", "brkitr/word.brk",
        ).forEach { assertTrue(keepsIcuItem(it, enEs), it) }
    }

    @Test
    fun `drops other languages' tailorings, dictionaries, converters and transliterators`() {
        listOf(
            "coll/fr.res", "rbnf/de.res", "lang/zh_Hant.res", "unit/ja.res", "zone/fil.res",
            "curr/pt_BR.res", "region/ru.res", "brkitr/thaidict.dict", "ibm-943_P15A-2003.cnv",
            "unames.icu", "cnvalias.icu", "translit/Any_Publishing.res",
            "coll/esu.res", // a different 3-letter language that starts with "es"
        ).forEach { assertFalse(keepsIcuItem(it, enEs), it) }
    }

    @Test
    fun `TOC rewrite keeps the surviving items intact, sorted and aligned`() {
        val items = listOf(
            "icudt77l/coll/en.res" to bytes(5, 1),
            "icudt77l/coll/fr.res" to bytes(17, 2),
            "icudt77l/en.res" to bytes(32, 3),
            "icudt77l/fr.res" to bytes(1, 4),
            "icudt77l/translit/a.res" to bytes(40, 5),
            "icudt77l/unames.icu" to bytes(3, 6),
        )
        val blob = icuBlob(items)
        assertEquals(items.map { it.first }, readIcuItems(blob).map { it.first })

        val trimmed = trimIcuData(blob) { keepsIcuItem(it, listOf("en")) }

        assertContentEquals(blob.copyOfRange(0, HEADER), trimmed.copyOfRange(0, HEADER), "header is copied verbatim")
        val kept = readIcuItems(trimmed)
        val expected = items.filter { it.first in setOf("icudt77l/coll/en.res", "icudt77l/en.res", "icudt77l/fr.res") }
        assertEquals(expected.map { it.first }, kept.map { it.first })
        assertEquals(kept.map { it.first }.sorted(), kept.map { it.first })
        expected.zip(kept).forEach { (want, got) ->
            // Items come back with their alignment padding; the original bytes must lead unchanged.
            assertContentEquals(want.second, got.second.copyOf(want.second.size), want.first)
        }
        val toc = ByteBuffer.wrap(trimmed).order(ByteOrder.LITTLE_ENDIAN)
        (0 until kept.size).forEach { assertEquals(0, toc.getInt(HEADER + 8 + 8 * it) % 16) }
        assertEquals(0, (trimmed.size - HEADER) % 16)
    }

    private fun bytes(size: Int, fill: Int) = ByteArray(size) { (fill * 31 + it).toByte() }

    /** A minimal ICU common-data blob: [HEADER] bytes of header, then the TOC and 16-byte aligned items. */
    private fun icuBlob(items: List<Pair<String, ByteArray>>): ByteArray {
        val header = ByteArray(HEADER) { (it + 7).toByte() }
        ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).putShort(0, HEADER.toShort())
        return trimIcuData(header + tocOf(items)) { true }.also { check(readIcuItems(it).size == items.size) }
    }

    /** An unaligned, unpadded TOC for [items]; [trimIcuData] with keep-all normalizes it. */
    private fun tocOf(items: List<Pair<String, ByteArray>>): ByteArray {
        val names = items.map { it.first.toByteArray() + 0 }
        val tableSize = 4 + 8 * items.size
        val out = ByteBuffer.allocate(tableSize + names.sumOf { it.size } + items.sumOf { it.second.size })
            .order(ByteOrder.LITTLE_ENDIAN).putInt(items.size)
        var name = tableSize
        var item = tableSize + names.sumOf { it.size }
        items.forEachIndexed { i, (_, data) ->
            out.putInt(name).putInt(item)
            name += names[i].size
            item += data.size
        }
        names.forEach { out.put(it) }
        items.forEach { out.put(it.second) }
        return out.array()
    }

    private companion object {
        const val HEADER = 32
    }
}
