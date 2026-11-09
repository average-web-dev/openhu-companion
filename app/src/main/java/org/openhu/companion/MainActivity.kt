package org.openhu.companion

import android.Manifest
import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.view.View
import android.content.pm.PackageManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * The only screen on the phone: the state of the link, the Bluetooth permission and the extension apps the
 * head unit's plugins want (installed or not, allowed or not). Everything else is automatic.
 */
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
        }
        val extensions = findViewById<LinearLayout>(R.id.extensions)
        scope.launch {
            combine(link.declared, ExtensionAccess.version) { declared, _ -> declared }.collect { showExtensions(extensions, it) }
        }
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

    /** One line per plugin that wants an app: installed? allowed? with the buttons to change it. */
    private fun showExtensions(container: LinearLayout, declared: List<Extensions.Declared>) {
        container.removeAllViews()
        if (declared.isEmpty()) {
            container.addView(TextView(this).apply { text = getString(R.string.extensions_none) })
            return
        }
        for (extension in declared) {
            val digest = ExtensionAccess.digest(this, extension.pkg)
            val decision = digest?.let { ExtensionAccess.decision(this, extension.pkg, it) }
            val label = TextView(this).apply {
                setPadding(0, 24, 0, 8)
                text = when {
                    digest == null -> getString(R.string.extension_not_installed, extension.name, extension.pkg)
                    decision == true -> getString(R.string.extension_allowed, extension.name)
                    decision == false -> getString(R.string.extension_denied, extension.name)
                    else -> getString(R.string.extension_pending, extension.name)
                }
            }
            container.addView(label)
            if (digest == null) continue
            val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            fun button(text: Int, onClick: () -> Unit) = buttons.addView(Button(this).apply {
                setText(text)
                setOnClickListener { onClick() }
            })
            if (decision != true) button(R.string.extension_allow) { ExtensionAccess.decide(this, extension.pkg, digest, true) }
            if (decision != false) button(R.string.extension_deny) { ExtensionAccess.decide(this, extension.pkg, digest, false) }
            if (decision != null) button(R.string.extension_forget) { ExtensionAccess.forget(this, extension.pkg) }
            container.addView(buttons)
        }
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
        const val REQUEST_NOTIFICATIONS = 2
    }
}
