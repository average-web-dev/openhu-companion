package org.openhu.companion

import androidx.media3.common.MediaItem
import org.json.JSONObject

/**
 * Voice requests ("play … on OpenHU", Android Auto and the Assistant): an empty query continues the head unit's
 * music, otherwise the best matching song of its library plays (title, artist, album).
 */
class VoiceSearch(private val link: HeadUnitLink, private val toItem: (Library.Entry) -> MediaItem) {
    /** The item to play, null when nothing matches; an empty query continues playback instead. */
    suspend fun find(query: String): MediaItem? {
        if (query.isBlank()) {
            link.emit("music.command", JSONObject().put("cmd", "play"))
            return null
        }
        val result = link.query(Library.query("songs") ?: return null) ?: return null
        return Library.bestSong(query, result)?.let(toItem)
    }

    /** Started from MEDIA_PLAY_FROM_SEARCH outside Android Auto: play right away. */
    suspend fun play(query: String) {
        val item = find(query) ?: return
        Library.play(item.mediaId)?.let { link.emit("music.command", Library.playCommand(it)) }
    }
}
