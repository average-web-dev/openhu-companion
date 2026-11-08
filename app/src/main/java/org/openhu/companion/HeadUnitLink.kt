package org.openhu.companion

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
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

    private val ids = AtomicInteger()
    private val responses = ConcurrentHashMap<Int, CompletableDeferred<Any?>>()
    private val queries = ConcurrentHashMap<String, CompletableDeferred<JSONObject>>()
    private var serverJob: Job? = null
    private var server: BluetoothServerSocket? = null
    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var output: OutputStream? = null

    fun hasPermission(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

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
        val label = context.applicationInfo.loadLabel(context.packageManager).toString()
        send(Protocol.hello(BuildConfig.VERSION_NAME, label, listOf("media")))
        send(Protocol.subscribe(listOf("music.state", "media.metadata", "music.result")))
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
            queries.values.forEach { it.cancel() }
            responses.clear()
            queries.clear()
        }
    }

    private fun handle(line: String) {
        when (val message = Protocol.parse(line)) {
            is Protocol.Message.Event -> {
                if (message.topic == "music.result") {
                    queries.remove(message.data.optString("id"))?.complete(message.data)
                }
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

    /** music.query → music.result with the same id, null on a timeout or without a link. */
    suspend fun query(data: JSONObject): JSONObject? {
        val id = "c" + ids.incrementAndGet()
        val answer = CompletableDeferred<JSONObject>()
        queries[id] = answer
        data.put("id", id)
        emit("music.query", data)
        if (!connected) { queries.remove(id); return null }
        return withTimeoutOrNull(TIMEOUT_MS) { try { answer.await() } catch (_: Exception) { null } }
            .also { queries.remove(id) }
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
