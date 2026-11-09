package org.openhu.companion.music

import android.app.Activity
import android.app.SearchManager
import android.os.Bundle
import android.provider.MediaStore
import androidx.media3.common.MediaItem
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** "Play … on Music" from the Assistant outside Android Auto: plays on the head unit, shows nothing. */
class VoiceActivity : Activity() {
    private val scope = MainScope()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent?.action != MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH) { finish(); return }
        val query = intent.getStringExtra(SearchManager.QUERY).orEmpty()
        val link = MusicLink.get(this)
        scope.launch {
            if (link.awaitReady(READY_MS)) {
                VoiceSearch(link) { MediaItem.Builder().setMediaId(it.id).build() }.play(query)
            }
            finish()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private companion object {
        const val READY_MS = 5000L
    }
}
