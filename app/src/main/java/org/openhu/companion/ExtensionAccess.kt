package org.openhu.companion

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.security.MessageDigest

/**
 * The user's answer per extension app: allowed or denied, bound to the app's signing certificate (another app
 * under the same package name asks again). Unknown apps get one notification with allow / deny; the companion's
 * screen lists them as well.
 */
object ExtensionAccess {
    private const val PREFS = "extension-access"
    private const val CHANNEL = "extensions"
    private const val EXTRA_PACKAGE = "package"
    private const val EXTRA_DIGEST = "digest"
    private const val EXTRA_ALLOW = "allow"

    private val _version = MutableStateFlow(0)
    /** Changes with every decision: the service and the screen look again. */
    val version: StateFlow<Int> = _version

    /** SHA-256 of the app's signing certificate, null when the app is not installed. */
    @Suppress("DEPRECATION")
    fun digest(context: Context, pkg: String): String? = try {
        val pm = context.packageManager
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val info = pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo
            if (info == null) null else if (info.hasMultipleSigners()) info.apkContentsSigners else info.signingCertificateHistory
        } else {
            pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES).signatures
        }
        signatures?.firstOrNull()?.toByteArray()?.let { bytes ->
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        }
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    /** true allowed, false denied, null not decided (or decided for another certificate). */
    fun decision(context: Context, pkg: String, digest: String): Boolean? {
        val stored = prefs(context).getString(pkg, null) ?: return null
        val (answer, forDigest) = stored.split(':', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
        return if (forDigest == digest) answer == "allow" else null
    }

    fun decide(context: Context, pkg: String, digest: String, allow: Boolean) {
        prefs(context).edit().putString(pkg, (if (allow) "allow:" else "deny:") + digest).apply()
        notifications(context).cancel(notificationId(pkg))
        _version.value++
    }

    fun forget(context: Context, pkg: String) {
        prefs(context).edit().remove(pkg).apply()
        _version.value++
    }

    /** The notification for an app that asks the first time. */
    fun ask(context: Context, pkg: String, digest: String, name: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return  // the companion's screen still lists it
        val manager = notifications(context)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.extension_channel), NotificationManager.IMPORTANCE_HIGH),
        )
        val label = appLabel(context, pkg)
        val notification = android.app.Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(context.getString(R.string.extension_ask_title, label))
            .setContentText(context.getString(R.string.extension_ask_text, label, name))
            .setStyle(android.app.Notification.BigTextStyle()
                .bigText(context.getString(R.string.extension_ask_text, label, name) + "\n" + pkg))
            .setContentIntent(PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE))
            .addAction(android.app.Notification.Action.Builder(null, context.getString(R.string.extension_allow),
                answer(context, pkg, digest, true)).build())
            .addAction(android.app.Notification.Action.Builder(null, context.getString(R.string.extension_deny),
                answer(context, pkg, digest, false)).build())
            .setAutoCancel(true)
            .build()
        manager.notify(notificationId(pkg), notification)
    }

    fun appLabel(context: Context, pkg: String): String = try {
        context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (_: PackageManager.NameNotFoundException) {
        pkg
    }

    private fun answer(context: Context, pkg: String, digest: String, allow: Boolean): PendingIntent =
        PendingIntent.getBroadcast(
            context, notificationId(pkg) * 2 + if (allow) 1 else 0,
            Intent(context, Receiver::class.java).putExtra(EXTRA_PACKAGE, pkg).putExtra(EXTRA_DIGEST, digest)
                .putExtra(EXTRA_ALLOW, allow),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun notificationId(pkg: String) = 1000 + (pkg.hashCode() and 0xffff)
    private fun notifications(context: Context) = context.getSystemService(NotificationManager::class.java)
    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The notification's buttons (not exported: only this app's pending intents reach it). */
    class Receiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val pkg = intent.getStringExtra(EXTRA_PACKAGE) ?: return
            val digest = intent.getStringExtra(EXTRA_DIGEST) ?: return
            decide(context, pkg, digest, intent.getBooleanExtra(EXTRA_ALLOW, false))
        }
    }
}
