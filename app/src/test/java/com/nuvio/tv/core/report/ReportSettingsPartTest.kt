package com.nuvio.tv.core.report

import com.nuvio.tv.core.assessment.ServiceNetworkTables
import com.nuvio.tv.core.assessment.ServiceTable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

/** Spec 116 D3.5 / A7 / B3. */
class ReportSettingsPartTest {

    @Test
    fun `B3 keys in order, raw values, unset as default, nothing else`() {
        val stored = mapOf(
            "internal_player_engine" to "EXOPLAYER",
            "tunneling_enabled" to false,
            "target_buffer_size_mb" to 150,
            "auto_play_filter_text" to "my private words",
            "subtitle_size" to 32,
        )
        val lines = ReportSettingsPart.lines(stored, ZoneOffset.UTC)
        assertEquals(26, lines.size)
        assertEquals("internal_player_engine", lines.first().key)
        assertEquals("service_network_tables", lines.last().key)
        val byKey = lines.associate { it.key to it.value }
        assertEquals("EXOPLAYER", byKey["internal_player_engine"])
        assertEquals("false", byKey["tunneling_enabled"])
        assertEquals("150", byKey["target_buffer_size_mb"])
        assertEquals("default", byKey["decoder_priority"])
        assertFalse("parallel_chunk_size_mb" in byKey) // the KB key every setter writes, not the legacy MB one
        assertEquals("16 (as parallel_chunk_size_mb)",
            ReportSettingsPart.lines(mapOf("parallel_chunk_size_mb" to 16), ZoneOffset.UTC).associate { it.key to it.value }["parallel_chunk_size_kb"])
        assertEquals("none", ReportSettingsPart.lines(mapOf("service_network_tables" to "{}"), ZoneOffset.UTC).last().value)
        val text = ReportSettingsPart.text(lines)
        assertFalse("my private words" in text)
        assertFalse("subtitle" in text)
        assertTrue(text.startsWith("# player\ninternal_player_engine = EXOPLAYER\n"))
        assertTrue("\n\n# video\n" in text)
    }

    @Test
    fun `the network test table is reduced to its service count and date`() {
        val json = ServiceNetworkTables(
            tables = mapOf(
                "debrid.example" to ServiceTable(testedAtMs = 1_762_905_600_000L), // 2025-11-12
                "cdn.example" to ServiceTable(testedAtMs = 1_762_819_200_000L),
            ),
        ).encode()
        val value = ReportSettingsPart.lines(mapOf("service_network_tables" to json), ZoneOffset.UTC).last().value
        assertEquals("2 services, tested 2025-11-12", value)
    }
}
