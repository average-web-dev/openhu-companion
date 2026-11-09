package org.openhu.companion

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * The link to the head unit over Bluetooth. The app listens on [SERVICE_UUID] (secure RFCOMM: only bonded,
 * encrypted links); OpenHU connects to it when Android Auto starts on this phone. One head unit at a time; a new
 * connection replaces the old one.
 */
class HeadUnitLink private constructor(private val context: Context) {
    sealed interface State {
        data object Stopped : State
        data object NoPermission : State
        data object NoBluetooth : State
        data object Waiting : State
        data class Connected(val name: String) : State
    }

    data class Event(val topic: String, val data: JSONObject)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<State>(State.Stopped)
    val state: StateFlow<State> = _state
    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 64)
    val events: SharedFlow<Event> = _events
    private val _declared = MutableStateFlow<List<Extensions.Declared>>(emptyList())
    /** The plugins that want an extension app (`companion.extensions`), the last list OpenHU sent. */
    val declared: StateFlow<List<Extensions.Declared>> = _declared

    private val ids = AtomicInteger()
    private val responses = ConcurrentHashMap<Int, CompletableDeferred<Any?>>()
    private var serverJob: Job? = null
    private var server: BluetoothServerSocket? = null
    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var output: OutputStream? = null
    private val extraTopics: MutableSet<String> = ConcurrentHashMap.newKeySet()

    fun hasPermission(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    init {
        // the names of the allowed media extensions change: tell OpenHU again
        scope.launch { combine(declared, ExtensionAccess.version) { _, _ -> }.collect { if (connected) sendHello() } }
    }

    /**
     * The announcement: the app, and the names Android Auto shows for the allowed media extensions (the local
     * player): OpenHU recognizes them as its own music in Android Auto's media status.
     */
    private fun sendHello() {
        val label = context.applicationInfo.loadLabel(context.packageManager).toString()
        send(Protocol.hello(BuildConfig.VERSION_NAME, label, listOf("extensions"), mediaLabels()))
    }

    private fun mediaLabels(): List<String> = declared.value.mapNotNull { extension ->
        val digest = ExtensionAccess.digest(context, extension.pkg) ?: return@mapNotNull null
        if (ExtensionAccess.decision(context, extension.pkg, digest) != true) return@mapNotNull null
        val intent = Intent("android.media.browse.MediaBrowserService").setPackage(extension.pkg)
        context.packageManager.queryIntentServices(intent, 0).firstOrNull()?.loadLabel(context.packageManager)?.toString()
    }.distinct()

    /** Starts listening (again, e.g. after the permission was granted). */
    @Synchronized
    fun start() {
        if (serverJob?.isActive == true) return
        serverJob = scope.launch { serve() }
    }

    @SuppressLint("MissingPermission")
    private suspend fun serve() {
        while (scope.isActive) {
            if (!hasPermission()) { _state.value = State.NoPermission; return }
            val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            if (adapter == null || !adapter.isEnabled) {
                _state.value = State.NoBluetooth
                delay(5000)
                continue
            }
            val listening = try {
                adapter.listenUsingRfcommWithServiceRecord(SERVICE_NAME, SERVICE_UUID)
            } catch (e: Exception) {
                Log.w(TAG, "listen failed", e)
                _state.value = State.NoBluetooth
                delay(5000)
                continue
            }
            server = listening
            if (socket == null) _state.value = State.Waiting
            try {
                while (scope.isActive) {
                    val accepted = runInterruptible { listening.accept() }
                    if (accepted.remoteDevice.bondState != BluetoothDevice.BOND_BONDED) {
                        accepted.close()
                        continue
                    }
                    scope.launch { serveConnection(accepted) }
                }
            } catch (e: Exception) {
                // Bluetooth off or the server closed: listen again
                Log.i(TAG, "server ended: $e")
            } finally {
                try { listening.close() } catch (_: Exception) {}
            }
            delay(1000)
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun serveConnection(connection: BluetoothSocket) {
        synchronized(this) {
            socket?.let { try { it.close() } catch (_: Exception) {} }
            socket = connection
            output = connection.outputStream
        }
        val name = try { connection.remoteDevice.name ?: connection.remoteDevice.address } catch (_: Exception) { "?" }
        Log.i(TAG, "head unit connected: $name")
        sendHello()
        send(Protocol.subscribe(listOf(Extensions.TOPIC) + extraTopics))
        _state.value = State.Connected(name)
        try {
            val reader = BufferedReader(InputStreamReader(connection.inputStream, Charsets.UTF_8))
            while (true) {
                val line = runInterruptible { reader.readLine() } ?: break
                handle(line)
            }
        } catch (e: Exception) {
            Log.i(TAG, "head unit link ended: $e")
        } finally {
            synchronized(this) {
                if (socket === connection) {
                    socket = null
                    output = null
                    _state.value = if (server != null) State.Waiting else State.Stopped
                }
            }
            try { connection.close() } catch (_: Exception) {}
            responses.values.forEach { it.cancel() }
            responses.clear()
        }
    }

    private fun handle(line: String) {
        when (val message = Protocol.parse(line)) {
            is Protocol.Message.Event -> {
                if (message.topic == Extensions.TOPIC) _declared.value = Extensions.parseDeclared(message.data)
                _events.tryEmit(Event(message.topic, message.data))
            }
            is Protocol.Message.Response -> responses.remove(message.id)?.complete(message.result)
            is Protocol.Message.Hello -> Log.i(TAG, "head unit: ${message.json}")
            else -> {}
        }
    }

    private fun send(message: JSONObject): Boolean {
        val bytes = (message.toString() + "\n").toByteArray(Charsets.UTF_8)
        synchronized(this) {
            val out = output ?: return false
            return try {
                out.write(bytes)
                out.flush()
                true
            } catch (e: Exception) {
                Log.i(TAG, "write failed: $e")
                false
            }
        }
    }

    val connected: Boolean get() = output != null

    /** More topics (extensions): sent now (OpenHU answers with their current values) and after every reconnect. */
    fun subscribe(topics: List<String>) {
        extraTopics.addAll(topics)
        if (topics.isNotEmpty()) send(Protocol.subscribe(topics))
    }

    /** bus.emit of a topic (fire and forget). */
    fun emit(topic: String, data: JSONObject) {
        send(Protocol.emit(ids.incrementAndGet(), topic, data))
    }

    /** An API request, null on an error, a timeout or without a link. */
    suspend fun request(method: String, params: JSONObject): Any? {
        val id = ids.incrementAndGet()
        val answer = CompletableDeferred<Any?>()
        responses[id] = answer
        if (!send(Protocol.request(id, method, params))) { responses.remove(id); return null }
        return withTimeoutOrNull(TIMEOUT_MS) { try { answer.await() } catch (_: Exception) { null } }
            .also { responses.remove(id) }
    }


    companion object {
        private const val TAG = "OpenHUCompanion"
        private const val TIMEOUT_MS = 8000L
        const val SERVICE_NAME = "OpenHU companion"
        /** The RFCOMM service OpenHU connects to (the same constant is in OpenHU's companion bridge). */
        val SERVICE_UUID: UUID = UUID.fromString("57b07c95-708d-46ae-a658-9f296dfd034c")

        // holds only the application context
        @android.annotation.SuppressLint("StaticFieldLeak")
        @Volatile private var instance: HeadUnitLink? = null
        fun get(context: Context): HeadUnitLink = instance ?: synchronized(this) {
            instance ?: HeadUnitLink(context.applicationContext).also { instance = it }
        }
    }
}
