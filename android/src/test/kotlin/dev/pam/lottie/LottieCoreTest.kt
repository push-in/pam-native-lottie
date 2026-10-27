package dev.pam.lottie

import dev.pam.nativeapp.protocol.WireValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LottieCoreTest {
    @Test
    fun propsUseTheRnDefaults() {
        val props = LottieProps.parse(mapOf("source" to WireValue.Text("https://x.dev/a.json")))
        assertEquals(LottieIdl.SOURCE_URL, props.kind)
        assertEquals("https://x.dev/a.json", props.key)
        assertTrue(props.loop)
        assertTrue(props.playing)
        assertEquals(1f, props.speed)
        assertEquals(LottieIdl.RESIZE_CONTAIN, props.resize)
        assertNull(props.progress)
        assertFalse(props.events)
    }

    @Test
    fun propsParseEveryHostProperty() {
        val props = LottieProps.parse(
            mapOf(
                "kind" to WireValue.Integer(2),
                "source" to WireValue.Text("{}"),
                "key" to WireValue.Text("sha1:ab"),
                "loop" to WireValue.Flag(false),
                "playing" to WireValue.Flag(false),
                "speed" to WireValue.Decimal(-20.0),
                "resize" to WireValue.Integer(2),
                "progress" to WireValue.Decimal(1.5),
                "fallback" to WireValue.Text("🎉"),
                "fallbackColor" to WireValue.Text("#fff"),
                "fallbackSize" to WireValue.Integer(40),
                "events" to WireValue.Flag(true),
            ),
        )
        assertEquals(LottieIdl.SOURCE_JSON, props.kind)
        assertEquals("sha1:ab", props.key)
        assertFalse(props.loop)
        assertFalse(props.playing)
        assertEquals(-LottieProps.MAX_SPEED, props.speed)
        assertEquals(LottieIdl.RESIZE_COVER, props.resize)
        assertEquals(1f, props.progress)
        assertEquals(0xFFFFFFFF.toInt(), props.fallbackColor)
        assertEquals(40f, props.fallbackSize)
        assertTrue(props.events)
    }

    @Test
    fun invalidEnumsAndSpeedsFallBack() {
        val props = LottieProps.parse(
            mapOf(
                "kind" to WireValue.Integer(9),
                "resize" to WireValue.Integer(0),
                "speed" to WireValue.Decimal(0.0),
            ),
        )
        assertEquals(LottieIdl.SOURCE_URL, props.kind)
        assertEquals(LottieIdl.RESIZE_CONTAIN, props.resize)
        assertEquals(1f, props.speed)
        assertFalse(props.hasSource)
    }

    @Test
    fun colorsAcceptShortLongAndCssAlpha() {
        assertEquals(0xFFFF0000.toInt(), LottieColor.argb("#f00"))
        assertEquals(0xFF102030.toInt(), LottieColor.argb("#102030"))
        assertEquals(0x80102030.toInt(), LottieColor.argb("#10203080"))
        assertNull(LottieColor.argb("red"))
        assertNull(LottieColor.argb("#12345"))
    }

    @Test
    fun lruEvictsByCountThenBytesInAccessOrder() {
        val lru = LottieLru<String>(maxEntries = 2, maxBytes = 100)
        lru.put("a", "A", 10)
        lru.put("b", "B", 10)
        lru["a"]
        lru.put("c", "C", 10)
        assertEquals(listOf("a", "c"), lru.keys())
        lru.put("d", "D", 95)
        assertEquals(listOf("d"), lru.keys())
        assertEquals(95L, lru.bytes)
        lru.put("huge", "H", 101)
        assertNull(lru["huge"])
    }

    @Test
    fun trimKeepsAFractionOfTheBudget() {
        val lru = LottieLru<String>(maxEntries = 4, maxBytes = 100)
        listOf("a", "b", "c", "d").forEach { lru.put(it, it, 20) }
        lru.trim(0.5f)
        assertEquals(listOf("c", "d"), lru.keys())
        lru.trim(0f)
        assertEquals(0, lru.size)
        assertEquals(0L, lru.bytes)
    }

    @Test
    fun inFlightLoadsAreSharedPerKey() {
        val inFlight = LottieInFlight<String>()
        val seen = mutableListOf<String>()
        assertTrue(inFlight.join("k") { seen += "1:$it" })
        assertFalse(inFlight.join("k") { seen += "2:$it" })
        assertEquals(2, inFlight.pending("k"))
        inFlight.complete("k", "ok")
        assertEquals(listOf("1:ok", "2:ok"), seen)
        assertEquals(0, inFlight.pending("k"))
        assertTrue(inFlight.join("k") { })
    }

    @Test
    fun playbackRunsOnlyAttachedVisibleAndPlaying() {
        assertEquals(LottiePlayback.RUN, lottiePlayback(true, null, attached = true, visible = true, animatorsEnabled = true, finished = false))
        assertEquals(LottiePlayback.PAUSE, lottiePlayback(true, null, attached = false, visible = true, animatorsEnabled = true, finished = false))
        assertEquals(LottiePlayback.PAUSE, lottiePlayback(true, null, attached = true, visible = false, animatorsEnabled = true, finished = false))
        assertEquals(LottiePlayback.PAUSE, lottiePlayback(false, null, attached = true, visible = true, animatorsEnabled = true, finished = false))
        assertEquals(LottiePlayback.PAUSE, lottiePlayback(true, null, attached = true, visible = true, animatorsEnabled = true, finished = true))
        assertEquals(LottiePlayback.FIRST_FRAME, lottiePlayback(true, null, attached = true, visible = true, animatorsEnabled = false, finished = false))
        assertEquals(LottiePlayback.PIN, lottiePlayback(true, 0.5f, attached = true, visible = true, animatorsEnabled = true, finished = false))
    }
}
