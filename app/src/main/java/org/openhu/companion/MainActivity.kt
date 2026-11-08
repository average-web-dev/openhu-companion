package org.openhu.companion

import android.Manifest
import android.app.Activity
import android.app.SearchManager
import android.content.Intent
import android.provider.MediaStore
import androidx.media3.common.MediaItem
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** The only screen on the phone: the state of the link and the Bluetooth permission. Everything else is automatic. */
class MainActivity : Activity() {
    private val scope = MainScope()
    private lateinit var link: HeadUnitLink

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        link = HeadUnitLink.get(this)
        val status = findViewById<TextView>(R.id.status)
        val allow = findViewById<Button>(R.id.allow)
        allow.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), REQUEST_BLUETOOTH)
            }
        }
        voiceRequest(intent)
        scope.launch {
            link.state.collect { state ->
                status.text = when (state) {
                    is HeadUnitLink.State.Connected -> getString(R.string.status_connected, state.name)
                    HeadUnitLink.State.NoPermission -> getString(R.string.status_no_permission)
                    HeadUnitLink.State.NoBluetooth -> getString(R.string.status_no_bluetooth)
                    HeadUnitLink.State.Waiting, HeadUnitLink.State.Stopped -> getString(R.string.status_waiting)
                }
                allow.visibility = if (state == HeadUnitLink.State.NoPermission) View.VISIBLE else View.GONE
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        voiceRequest(intent)
    }

    // "Play … on OpenHU" from the Assistant on the phone
    private fun voiceRequest(intent: Intent?) {
        if (intent?.action != MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH) return
        val query = intent.getStringExtra(SearchManager.QUERY).orEmpty()
        val search = VoiceSearch(link) { MediaItem.Builder().setMediaId(it.id).build() }
        scope.launch { search.play(query) }
    }

    override fun onResume() {
        super.onResume()
        link.start()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        link.start()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private companion object {
        const val REQUEST_BLUETOOTH = 1
    }
}
