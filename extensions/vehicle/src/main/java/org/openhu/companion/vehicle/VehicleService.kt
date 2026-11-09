package org.openhu.companion.vehicle

import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import org.json.JSONObject
import org.openhu.companion.client.OpenHUExtension

/**
 * The vehicle plugin's values in Android Auto. A media app only for its own entry in Android Auto's launcher
 * (sideloaded apps get one only as media apps): the values are a list (title = what, subtitle = the value) in the
 * only tab, a tap opens all fields of that value. Nothing is playable: a playable item would make this the active media app.
 */
@OptIn(UnstableApi::class)
class VehicleService : MediaLibraryService(), OpenHUExtension.Listener {
    private lateinit var openhu: OpenHUExtension
    private var session: MediaLibrarySession? = null
    private val values = linkedMapOf<String, JSONObject>()
    private val handler = Handler(Looper.getMainLooper())
    private var changePosted = false

    override fun onCreate() {
        super.onCreate()
        session = MediaLibrarySession.Builder(this, IdlePlayer(mainLooper), Callback()).build()
        openhu = OpenHUExtension(this, PLUGIN, this)
        openhu.subscribe("vehicle.*")
        if (!openhu.connect()) changed()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = session
    override fun onUpdateNotification(session: MediaSession, startInForegroundRequired: Boolean) {}

    override fun onDestroy() {
        openhu.disconnect()
        handler.removeCallbacksAndMessages(null)
        session?.run { player.release(); release() }
        session = null
        super.onDestroy()
    }

    override fun onState(state: OpenHUExtension.State) {
        if (!state.ready) values.clear()
        changed()
    }

    override fun onEvent(topic: String, data: JSONObject) {
        if (topic !in VehicleValues.TOPICS) return
        values[topic] = data
        changed()
    }

    /** At most one refresh per second: the list is browsed while driving. */
    private fun changed() {
        if (changePosted) return
        changePosted = true
        handler.postDelayed({
            changePosted = false
            session?.notifyChildrenChanged(VALUES, Int.MAX_VALUE, null)
            values.keys.forEach { session?.notifyChildrenChanged(it, Int.MAX_VALUE, null) }
        }, REFRESH_MS)
    }

    private fun valueItems(): List<MediaItem> {
        val state = openhu.state
        val problem = when {
            !state.bound -> R.string.state_no_companion
            !state.connected -> R.string.state_not_connected
            state.access == OpenHUExtension.ACCESS_PENDING -> R.string.state_pending
            state.access == OpenHUExtension.ACCESS_DENIED -> R.string.state_denied
            state.access != OpenHUExtension.ACCESS_ALLOWED -> R.string.state_not_declared
            else -> null
        }
        if (problem != null) return listOf(item("state", getString(problem), null))
        val shown = VehicleValues.TOPICS.mapNotNull { topic ->
            val data = values[topic] ?: return@mapNotNull null
            val text = VehicleValues.format(topic, data, getString(R.string.on), getString(R.string.off)) ?: return@mapNotNull null
            item(topic, getString(label(topic)), text)
        }
        return shown.ifEmpty { listOf(item("empty", getString(R.string.no_values), null)) }
    }

    private fun label(topic: String) = when (topic) {
        "vehicle.speed" -> R.string.value_speed
        "vehicle.gear" -> R.string.value_gear
        "vehicle.fuel" -> R.string.value_fuel
        "vehicle.engine" -> R.string.value_engine
        "vehicle.odometer" -> R.string.value_odometer
        "vehicle.climate" -> R.string.value_climate
        "vehicle.compass" -> R.string.value_compass
        else -> R.string.value_parking_brake
    }

    private inner class Callback : MediaLibrarySession.Callback {
        override fun onGetLibraryRoot(
            session: MediaLibrarySession, browser: MediaSession.ControllerInfo, params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> =
            Futures.immediateFuture(LibraryResult.ofItem(item(ROOT, getString(R.string.app_name), null, browsable = true), params))

        override fun onGetChildren(
            session: MediaLibrarySession, browser: MediaSession.ControllerInfo, parentId: String,
            page: Int, pageSize: Int, params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val items = when (parentId) {
                // Android Auto shows the root's children as tabs: one tab, the list is in it
                ROOT -> listOf(item(VALUES, getString(R.string.app_name), null, browsable = true))
                VALUES -> valueItems()
                // a tap on a value (Android Auto asks a plain item for its children): all its fields
                else -> values[parentId]?.let { data -> VehicleValues.fields(data).map { (key, value) -> item("$parentId/$key", key, value) } }
                    ?: emptyList()
            }
            return Futures.immediateFuture(LibraryResult.ofItemList(items, params))
        }
    }

    private fun item(id: String, title: String, subtitle: String?, browsable: Boolean = false): MediaItem =
        MediaItem.Builder().setMediaId(id).setMediaMetadata(
            MediaMetadata.Builder().setTitle(title).setSubtitle(subtitle).setArtist(subtitle)
                .setIsBrowsable(browsable).setIsPlayable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED).build(),
        ).build()

    /** A media session needs a player; this one never plays. */
    private class IdlePlayer(looper: Looper) : SimpleBasePlayer(looper) {
        override fun getState(): State = State.Builder()
            .setAvailableCommands(Player.Commands.Builder().build())
            .setPlaybackState(Player.STATE_IDLE).build()
    }

    private companion object {
        const val PLUGIN = "org.openhu.vehicle"
        const val ROOT = "root"
        const val VALUES = "values"
        const val REFRESH_MS = 1000L
    }
}
