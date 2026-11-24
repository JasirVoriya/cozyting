package com.cozyting.app.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LogEntryParserTest {

    @Test
    fun parsesStandardKvLineWithSpacesInValue() {
        val raw = "2026-11-25 02:02:16.375 I/CozyTing: discover_row ip=192.0.2.10 name=Sound Pro-0001 model=L17A castable=true"
        val entry = LogEntryParser.parseLine(0, raw)

        assertEquals("2026-11-25", entry.date)
        assertEquals("02:02:16.375", entry.time)
        assertEquals('I', entry.level)
        assertEquals("CozyTing", entry.tag)
        assertFalse(entry.isCore)
        assertFalse(entry.isOps)
        assertEquals("discover_row", entry.eventTitle)
        assertNull(entry.jsonRaw)
        assertEquals(
            listOf(
                "ip" to "192.0.2.10",
                "name" to "Sound Pro-0001",
                "model" to "L17A",
                "castable" to "true",
            ),
            entry.kvPairs,
        )
    }

    @Test
    fun detectsCoreTagAndWarnErrorLevels() {
        val warnRaw = "2026-11-25 02:02:19.004 W/CozyTingCore: ntp_jitter_high target=192.0.2.11 rtt_ms=48.6"
        val errRaw = "2026-11-25 02:02:21.518 E/CozyTingCore: rtsp_teardown_timeout code=ETIMEDOUT"

        val warnEntry = LogEntryParser.parseLine(0, warnRaw)
        val errEntry = LogEntryParser.parseLine(1, errRaw)

        assertEquals('W', warnEntry.level)
        assertTrue(warnEntry.isCore)
        assertEquals("ntp_jitter_high", warnEntry.eventTitle)

        assertEquals('E', errEntry.level)
        assertTrue(errEntry.isCore)
        assertEquals("rtsp_teardown_timeout", errEntry.eventTitle)
    }

    @Test
    fun extractsAndFormatsLongEmbeddedJson() {
        val json = """{"devices":[{"id":"192.0.2.10","name":"Speaker-0001","castable":true},{"id":"192.0.2.11","name":"Speaker-0002","castable":true}]}"""
        val raw = "2026-11-25 02:02:14.112 I/CozyTing: discover_snapshot source=mdns $json"

        val entry = LogEntryParser.parseLine(0, raw)
        assertEquals("discover_snapshot", entry.eventTitle)
        assertEquals(listOf("source" to "mdns"), entry.kvPairs)
        assertEquals(json, entry.jsonRaw)
        assertNotNull(entry.jsonSummary)
        assertEquals(2, entry.jsonSummary!!.itemCount)
        assertFalse(entry.jsonSummary!!.isArray)

        val pretty = LogEntryParser.prettyFormatJson(entry.jsonRaw!!)
        assertTrue(pretty.contains("\n"))
        assertTrue(pretty.contains("\"devices\""))
    }

    @Test
    fun ignoresShortArrayTokensLikeAppsList() {
        val raw = "2026-11-25 02:02:16.375 I/CozyTing: discover_row ip=192.0.2.10 apps=[5]"
        val entry = LogEntryParser.parseLine(0, raw)

        assertNull(entry.jsonRaw)
        assertEquals(listOf("ip" to "192.0.2.10", "apps" to "[5]"), entry.kvPairs)
    }

    @Test
    fun handlesPlainAndContinuationLinesGracefully() {
        val raw = "unformatted continuation line without timestamp"
        val entry = LogEntryParser.parseLine(3, raw, defaultIsOps = true)

        assertNull(entry.date)
        assertNull(entry.time)
        assertEquals('I', entry.level)
        assertTrue(entry.isOps)
        assertEquals(raw, entry.eventTitle)
    }
}
