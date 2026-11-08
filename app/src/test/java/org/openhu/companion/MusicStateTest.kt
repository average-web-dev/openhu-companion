package org.openhu.companion

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicStateTest {
    private fun state(state: String, title: String?) = JSONObject()
        .put("state", state).put("position", 12.5).put("duration", 139.0)
        .put("track", title?.let { JSONObject().put("title", it).put("artist", "A").put("album", "B") } ?: JSONObject.NULL)

    @Test
    fun musicStateBecomesTheTrack() {
        val track = MusicState.parse(state("playing", "One"), null, now = 0)!!
        assertEquals("One", track.title)
        assertTrue(track.playing)
        assertEquals(12500, track.positionMs)
        assertEquals(139000, track.durationMs)
        assertNull(track.artwork)
    }

    @Test
    fun pausedByAnotherSourceIsPaused() {
        // Android Auto's own music paused the head unit's player: the app shows it paused, not the old "playing"
        assertFalse(MusicState.parse(state("paused", "One"), null, now = 0)!!.playing)
    }

    @Test
    fun coverOnlyForItsSong() {
        val cover = "One" to byteArrayOf(1, 2)
        assertEquals(2, MusicState.parse(state("playing", "One"), cover, now = 0)!!.artwork!!.size)
        assertNull(MusicState.parse(state("playing", "Two"), cover, now = 0)!!.artwork)
    }

    @Test
    fun noTrackIsIdle() {
        assertNull(MusicState.parse(state("stopped", null), null, now = 0))
    }
}
