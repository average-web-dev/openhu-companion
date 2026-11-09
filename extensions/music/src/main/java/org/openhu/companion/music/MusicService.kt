package org.openhu.companion.music

import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionError
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.launch

/**
 * The music app in Android Auto: the head unit's music library (the music plugin) as tabs, its player as the
 * session. It reaches the head unit through the OpenHU companion app ([MusicLink]); binding the companion also
 * starts its Bluetooth link, so Android Auto binding this app is enough.
 */
@OptIn(UnstableApi::class)
class MusicService : MediaLibraryService() {
    private val scope = MainScope()
    private lateinit var link: MusicLink
    private lateinit var player: HeadUnitPlayer
    private lateinit var voiceSearch: VoiceSearch
    private var session: MediaLibrarySession? = null

    override fun onCreate() {
        super.onCreate()
        link = MusicLink.get(this)
        link.connect()
        player = HeadUnitPlayer(mainLooper, link, scope)
        voiceSearch = VoiceSearch(link) { item(it) }
        session = MediaLibrarySession.Builder(this, player, Callback()).build()
        scope.launch {
            link.events.collect {
                when (it.topic) {
                    "music.state" -> player.onState(it.data)
                    "media.metadata" -> player.onMetadata(it.data)
                }
            }
        }
        scope.launch { link.state.collect { if (it.ready) player.refresh() } }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    // No notification and no foreground service: nothing plays on the phone
    override fun onUpdateNotification(session: MediaSession, startInForegroundRequired: Boolean) {}

    override fun onDestroy() {
        session?.release()
        session = null
        player.release()
        scope.cancel()
        super.onDestroy()
    }

    private inner class Callback : MediaLibrarySession.Callback {
        override fun onGetLibraryRoot(
            session: MediaLibrarySession, browser: MediaSession.ControllerInfo, params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> =
            Futures.immediateFuture(LibraryResult.ofItem(folder(Library.ROOT, getString(R.string.app_name), null), params))

        override fun onGetChildren(
            session: MediaLibrarySession, browser: MediaSession.ControllerInfo, parentId: String,
            page: Int, pageSize: Int, params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            if (parentId == Library.ROOT) {
                val tabs = Library.TABS.map { folder(it, getString(tabTitle(it)), null) }
                return Futures.immediateFuture(LibraryResult.ofItemList(tabs, params))
            }
            val query = Library.query(parentId)
                ?: return Futures.immediateFuture(LibraryResult.ofError(SessionError.ERROR_BAD_VALUE))
            return scope.future {
                val result = link.query(query)
                    ?: return@future LibraryResult.ofError(SessionError.ERROR_IO)
                val items = Library.entries(parentId, result).map { item(it) }
                val from = (page * pageSize).coerceIn(0, items.size)
                val to = if (pageSize <= 0) items.size else (from + pageSize).coerceAtMost(items.size)
                LibraryResult.ofItemList(items.subList(from, to), params)
            }
        }

        // A chosen track keeps its media id; the player turns it into music.command playItems. A voice request
        // ("play … on Music") comes as an item with only a search query: the best song of the head unit's library.
        override fun onAddMediaItems(
            mediaSession: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> {
            val query = mediaItems.firstOrNull()?.requestMetadata?.searchQuery
                ?: return Futures.immediateFuture(mediaItems)
            return scope.future { voiceSearch.find(query)?.let { mutableListOf(it) } ?: mutableListOf() }
        }
    }

    private fun tabTitle(tab: String) = when (tab) {
        "albums" -> R.string.tab_albums
        "artists" -> R.string.tab_artists
        "playlists" -> R.string.tab_playlists
        else -> R.string.tab_songs
    }

    private fun item(entry: Library.Entry): MediaItem =
        if (entry.playable) {
            MediaItem.Builder().setMediaId(entry.id).setMediaMetadata(
                MediaMetadata.Builder().setTitle(entry.title).setArtist(entry.subtitle)
                    .setIsBrowsable(false).setIsPlayable(true)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC).build(),
            ).build()
        } else {
            folder(entry.id, entry.title, entry.subtitle)
        }

    private fun folder(id: String, title: String, subtitle: String?): MediaItem =
        MediaItem.Builder().setMediaId(id).setMediaMetadata(
            MediaMetadata.Builder().setTitle(title).setSubtitle(subtitle)
                .setIsBrowsable(true).setIsPlayable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED).build(),
        ).build()
}
