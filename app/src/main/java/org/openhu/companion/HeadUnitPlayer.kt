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
 * A player without sound: the music plays on the head unit. Its state is OpenHU's `media.current`; play, pause,
 * next, previous and a chosen track become `music.command`s. Android Auto shows its own guess right after a tap, so
 * a moment after the last command the state is fetched again ([refresh]) and the head unit's state wins.
 */
@OptIn(UnstableApi::class)
class HeadUnitPlayer(
    looper: Looper,
    private val link: HeadUnitLink,
    private val scope: CoroutineScope,
) : SimpleBasePlayer(looper) {
    private val handler = Handler(looper)
    private var track: Track? = null
    private val refreshRunnable = Runnable { refresh() }

    /** What OpenHU reports; [receivedAt] for the position between two reports. */
    data class Track(
        val title: String, val artist: String, val album: String, val durationMs: Long, val artwork: ByteArray?,
        val playing: Boolean, val positionMs: Long, val receivedAt: Long,
    )

    /** A `media.current` from OpenHU (on the player's looper). */
    fun update(current: JSONObject) {
        // Android Auto's own music is not the head unit's; the companion's own echo is filtered by OpenHU
        if (current.optString("source") == "androidauto") return
        track = parse(current)
        invalidateState()
    }

    fun refresh() {
        scope.launch {
            val current = link.request("bus.get", JSONObject().put("topic", "media.current"))
            if (current is JSONObject) update(current)
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

        fun parse(current: JSONObject, now: Long = SystemClock.elapsedRealtime()): Track? {
            val meta = current.optJSONObject("metadata") ?: return null
            val title = meta.optString("title")
            if (title.isEmpty()) return null
            val status = current.optJSONObject("status") ?: JSONObject()
            return Track(
                title = title,
                artist = meta.optString("artist"),
                album = meta.optString("album"),
                durationMs = (meta.optDouble("duration", 0.0) * 1000).toLong(),
                artwork = dataUrl(meta.optString("cover")),
                playing = status.optString("state") == "playing",
                positionMs = (status.optDouble("position", 0.0) * 1000).toLong(),
                receivedAt = now,
            )
        }

        private fun dataUrl(url: String): ByteArray? {
            if (!url.startsWith("data:")) return null
            return try { Base64.decode(url.substringAfter(','), Base64.DEFAULT) } catch (_: Exception) { null }
        }
    }
}
