package org.openhu.companion

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryTest {
    @Test
    fun idsSurviveSeparatorsAndSpaces() {
        val id = Library.join("album", "[\"A|B\", \"Best of\"]")
        assertEquals(listOf("album", "[\"A|B\", \"Best of\"]"), Library.split(id))
    }

    @Test
    fun queriesForTheTabsAndPages() {
        assertEquals("albums", Library.query("albums")!!.getString("cmd"))
        val album = Library.query(Library.join("album", "x"))!!
        assertEquals("album", album.getString("cmd"))
        assertEquals("x", album.getString("album"))
        assertNull(Library.query("nonsense"))
    }

    @Test
    fun albumListBecomesFolders() {
        val result = JSONObject().put("ok", true).put("items", JSONArray()
            .put(JSONObject().put("kind", "album").put("id", "a1").put("title", "Echo").put("subtitle", "Quiet Hours")))
        val entries = Library.entries("albums", result)
        assertEquals(1, entries.size)
        assertFalse(entries[0].playable)
        assertEquals(listOf("album", "a1"), Library.split(entries[0].id))
        assertEquals("Quiet Hours", entries[0].subtitle)
    }

    @Test
    fun albumTracksPlayInTheirAlbum() {
        val result = JSONObject().put("ok", true).put("tracks", JSONArray()
            .put(JSONObject().put("id", "usb:a/1.mp3").put("title", "One").put("artist", "X"))
            .put(JSONObject().put("id", "usb:a/2.mp3").put("title", "Two").put("available", false)))
        val entries = Library.entries(Library.join("album", "a1"), result)
        assertEquals(1, entries.size)  // the missing file is left out
        val play = Library.play(entries[0].id)!!
        assertEquals(Library.Play("album", "a1", "usb:a/1.mp3"), play)
        val command = Library.playCommand(play)
        assertEquals("playItems", command.getString("cmd"))
        assertEquals("album", command.getJSONObject("context").getString("kind"))
        assertEquals("a1", command.getJSONObject("context").getString("id"))
        assertEquals("usb:a/1.mp3", command.getString("start"))
    }

    @Test
    fun songsPlayInTheSongList() {
        val result = JSONObject().put("ok", true).put("items", JSONArray()
            .put(JSONObject().put("kind", "song").put("id", "t1").put("title", "One")))
        val play = Library.play(Library.entries("songs", result)[0].id)!!
        val command = Library.playCommand(play)
        assertEquals("songs", command.getJSONObject("context").getString("kind"))
        assertFalse(command.getJSONObject("context").has("id"))
    }

    @Test
    fun failedQueryIsEmpty() {
        assertTrue(Library.entries("albums", JSONObject().put("ok", false)).isEmpty())
    }

    @Test
    fun voiceSearchFindsTheBestSong() {
        val songs = JSONObject().put("ok", true).put("items", JSONArray()
            .put(JSONObject().put("id", "1").put("title", "Low Tide").put("artist", "Waves"))
            .put(JSONObject().put("id", "2").put("title", "Lighthouse").put("artist", "Low"))
            .put(JSONObject().put("id", "3").put("title", "Gone").put("available", false)))
        assertEquals("1", Library.play(Library.bestSong("low tide", songs)!!.id)!!.track)
        assertEquals("2", Library.play(Library.bestSong("lighthouse by low", songs)!!.id)!!.track)
        assertNull(Library.bestSong("gone", songs))
        assertNull(Library.bestSong("nothing here", songs))
    }
}
