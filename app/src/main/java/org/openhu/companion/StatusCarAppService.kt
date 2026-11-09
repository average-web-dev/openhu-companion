package org.openhu.companion

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.ApplicationInfo
import androidx.car.app.CarAppService
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarIcon
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.validation.HostValidator
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * The head unit's status as a template screen in Android Auto (Car App Library, IoT category): the Bluetooth link
 * and what the music player plays, with play/pause and next. Template apps appear in Android Auto only when they
 * come from Play (sideloaded ones are hidden), so this is also the test whether the internal testing track is
 * enough for that.
 */
class StatusCarAppService : CarAppService() {
    // release: only the hosts Google signs (Android Auto, Android Automotive's template host), the library's list
    // that its documentation names for this
    @SuppressLint("PrivateResource")
    override fun createHostValidator(): HostValidator =
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        } else {
            HostValidator.Builder(applicationContext)
                .addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample)
                .build()
        }

    override fun onCreateSession(): Session = object : Session() {
        override fun onCreateScreen(intent: Intent): Screen = StatusScreen(carContext, HeadUnitLink.get(carContext))
    }
}

/**
 * One list with fixed row titles (head unit, now playing): only the texts change, so an update counts as a refresh
 * of the same template and not against Android Auto's step limit.
 */
class StatusScreen(carContext: CarContext, private val link: HeadUnitLink) : Screen(carContext) {
    private var track: MusicState.Track? = null
    private var scope: CoroutineScope? = null

    init {
        link.start()
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                val started = MainScope().also { scope = it }
                started.launch { link.state.collect { state -> if (state is HeadUnitLink.State.Connected) fetch(); invalidate() } }
                started.launch {
                    link.events.collect {
                        if (it.topic == "music.state") { track = MusicState.parse(it.data, null); invalidate() }
                    }
                }
            }

            override fun onStop(owner: LifecycleOwner) {
                scope?.cancel()
                scope = null
            }
        })
    }

    private fun fetch() {
        scope?.launch {
            val state = link.request("bus.get", JSONObject().put("topic", "music.state"))
            if (state is JSONObject && state.length() > 0) { track = MusicState.parse(state, null); invalidate() }
        }
    }

    private fun command(cmd: String) = link.emit("music.command", JSONObject().put("cmd", cmd))

    override fun onGetTemplate(): Template {
        val connection = when (val state = link.state.value) {
            is HeadUnitLink.State.Connected -> carContext.getString(R.string.status_connected, state.name)
            is HeadUnitLink.State.NoPermission -> carContext.getString(R.string.status_no_permission)
            is HeadUnitLink.State.NoBluetooth -> carContext.getString(R.string.status_no_bluetooth)
            else -> carContext.getString(R.string.row_not_connected)
        }
        val t = track
        val nowPlaying = if (t == null) {
            carContext.getString(R.string.row_nothing_playing)
        } else {
            listOf(t.title, t.artist).filter { it.isNotEmpty() }.joinToString(" – ")
        }
        val items = ItemList.Builder()
            .addItem(Row.Builder().setTitle(carContext.getString(R.string.row_connection)).addText(connection).build())
            .addItem(
                Row.Builder().setTitle(carContext.getString(R.string.row_now_playing)).addText(nowPlaying)
                    .apply {
                        if (t != null) addText(carContext.getString(if (t.playing) R.string.row_playing else R.string.row_paused))
                    }
                    .build(),
            )
            .build()
        // icons: an action strip allows only one action with a text
        val actions = ActionStrip.Builder()
            .addAction(iconAction(R.drawable.ic_play_pause) { command("toggle") })
            .addAction(iconAction(R.drawable.ic_next) { command("next") })
            .build()
        return ListTemplate.Builder()
            .setTitle(carContext.getString(R.string.status_title))
            .setHeaderAction(Action.APP_ICON)
            .setSingleList(items)
            .setActionStrip(actions)
            .build()
    }

    private fun iconAction(icon: Int, onClick: () -> Unit): Action = Action.Builder()
        .setIcon(CarIcon.Builder(IconCompat.createWithResource(carContext, icon)).build())
        .setOnClickListener(onClick)
        .build()
}
