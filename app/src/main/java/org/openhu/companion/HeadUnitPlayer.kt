package org.openhu.companion

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * A player without sound: the music plays on the head unit. Its state is the music plugin's `music.state` (the
 * player whose library the app shows, also while another source has paused it), the cover comes from its
 * `media.metadata`. Play, pause, next, previous and a chosen track become `music.command`s. Android Auto shows its
 * own guess right after a tap, so a moment after the last command the state is fetched again ([refresh]).
 */
@OptIn(UnstableApi::class)
class HeadUnitPlayer(
    looper: Looper,
    private val link: HeadUnitLink,
    private val scope: CoroutineScope,
) : SimpleBasePlayer(looper) {
    private val handler = Handler(looper)
    private var track: MusicState.Track? = null
    private var cover: Pair<String, ByteArray>? = null  // title -> cover of the plugin's media.metadata
    private val refreshRunnable = Runnable { refresh() }

    /** `music.state` of the music plugin (on the player's looper). */
    fun onState(state: JSONObject) {
        track = MusicState.parse(state, cover)
        invalidateState()
    }

    /** `media.metadata` of the music plugin: its cover (a data URL), the rest comes with `music.state`. */
    fun onMetadata(metadata: JSONObject) {
        if (metadata.optString("source") != MusicState.SOURCE) return
        val bytes = dataUrl(metadata.optString("cover")) ?: return
        cover = metadata.optString("title") to bytes
        track?.let { if (it.title == metadata.optString("title")) { track = it.copy(artwork = bytes); invalidateState() } }
    }

    fun refresh() {
        scope.launch {
            // the cover first: it only comes again with the next song
            val metadata = link.request("bus.get", JSONObject().put("topic", "media.metadata"))
            if (metadata is JSONObject) onMetadata(metadata)
            val state = link.request("bus.get", JSONObject().put("topic", "music.state"))
            if (state is JSONObject && state.length() > 0) onState(state)
        }
    }

    private fun command(cmd: String) {
        link.emit("music.command", JSONObject().put("cmd", cmd))
        settle()
    }

    private fun settle() {
        handler.removeCallbacks(refreshRunnable)
        handler.postDelayed(refreshRunnable, SETTLE_MS)
    }

    override fun getState(): State {
        val builder = State.Builder().setAvailableCommands(COMMANDS)
        val t = track ?: return builder.setPlaybackState(Player.STATE_IDLE).build()
        val metadata = MediaMetadata.Builder()
            .setTitle(t.title).setArtist(t.artist).setAlbumTitle(t.album)
            .setIsBrowsable(false).setIsPlayable(true)
            .apply { t.artwork?.let { setArtworkData(it, MediaMetadata.PICTURE_TYPE_FRONT_COVER) } }
            .build()
        val current = MediaItemData.Builder("track:${t.title}:${t.artist}")
            .setMediaItem(MediaItem.Builder().setMediaId("current").setMediaMetadata(metadata).build())
            .setMediaMetadata(metadata)
            .setDurationUs(if (t.durationMs > 0) t.durationMs * 1000 else androidx.media3.common.C.TIME_UNSET)
            .setIsSeekable(false)
            .build()
        // placeholders before and after: next and previous stay available (the queue is the head unit's; the
        // timeline is not offered to controllers, so they are not shown)
        val playlist = listOf(placeholder("previous"), current, placeholder("next"))
        val elapsed = if (t.playing) SystemClock.elapsedRealtime() - t.receivedAt else 0
        val position = (t.positionMs + elapsed).coerceAtMost(if (t.durationMs > 0) t.durationMs else Long.MAX_VALUE)
        return builder
            .setPlaylist(playlist)
            .setCurrentMediaItemIndex(1)
            .setPlaybackState(Player.STATE_READY)
            .setPlayWhenReady(t.playing, Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE)
            .setContentPositionMs(PositionSupplier.getExtrapolating(position, if (t.playing) 1f else 0f))
            .build()
    }

    private fun placeholder(uid: String) = MediaItemData.Builder(uid)
        .setMediaItem(MediaItem.Builder().setMediaId(uid).build()).build()

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        command(if (playWhenReady) "play" else "pause")
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        when (seekCommand) {
            Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> command("next")
            Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> command("previous")
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleSetMediaItems(
        mediaItems: MutableList<MediaItem>, startIndex: Int, startPositionMs: Long,
    ): ListenableFuture<*> {
        val chosen = mediaItems.getOrNull(if (startIndex in mediaItems.indices) startIndex else 0)
        val play = chosen?.let { Library.play(it.mediaId) }
        if (play != null) {
            link.emit("music.command", Library.playCommand(play))
            settle()
        }
        return Futures.immediateVoidFuture()
    }

    override fun handlePrepare(): ListenableFuture<*> = Futures.immediateVoidFuture()

    override fun handleStop(): ListenableFuture<*> {
        command("pause")
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> {
        handler.removeCallbacks(refreshRunnable)
        return Futures.immediateVoidFuture()
    }

    companion object {
        private const val SETTLE_MS = 1200L

        private val COMMANDS = Player.Commands.Builder().addAll(
            Player.COMMAND_PLAY_PAUSE, Player.COMMAND_PREPARE, Player.COMMAND_STOP,
            Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            Player.COMMAND_SET_MEDIA_ITEM, Player.COMMAND_CHANGE_MEDIA_ITEMS,
            Player.COMMAND_GET_CURRENT_MEDIA_ITEM, Player.COMMAND_GET_METADATA,
        ).build()

        fun dataUrl(url: String): ByteArray? {
            if (!url.startsWith("data:")) return null
            return try { Base64.decode(url.substringAfter(','), Base64.DEFAULT) } catch (_: Exception) { null }
        }
    }
}

/** The music plugin's `music.state` as the player's track (pure, no Android classes: unit tested). */
object MusicState {
    /** What OpenHU reports; [receivedAt] for the position between two reports. */
    data class Track(
        val title: String, val artist: String, val album: String, val durationMs: Long, val artwork: ByteArray?,
        val playing: Boolean, val positionMs: Long, val receivedAt: Long,
    )

    const val SOURCE = "music"

    fun parse(state: JSONObject, cover: Pair<String, ByteArray>?, now: Long = android.os.SystemClock.elapsedRealtime()): Track? {
        val track = state.optJSONObject("track") ?: return null
        val title = track.optString("title")
        if (title.isEmpty()) return null
        val playing = state.optString("state") == "playing"
        return Track(
            title = title,
            artist = track.optString("artist"),
            album = track.optString("album"),
            durationMs = (state.optDouble("duration", track.optDouble("duration", 0.0)) * 1000).toLong(),
            artwork = cover?.takeIf { it.first == title }?.second,
            playing = playing,
            positionMs = (state.optDouble("position", 0.0) * 1000).toLong(),
            receivedAt = now,
        )
    }

}
