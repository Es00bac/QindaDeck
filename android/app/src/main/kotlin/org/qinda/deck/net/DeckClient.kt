package org.qinda.deck.net

import android.util.Log
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

sealed interface Link {
    data object Idle : Link
    data class Connecting(val host: String) : Link
    data class Up(val host: String) : Link
    data class Down(val reason: String) : Link
}

class DeckException(message: String) : Exception(message)

/**
 * The control connection.
 *
 * One socket, one reader coroutine. Replies are matched to requests by the `id`
 * the device echoes, so calls can be issued concurrently from different screens
 * without interleaving; anything arriving with `id` 0 is an unsolicited device
 * event and goes to [events].
 *
 * Live input ([mouseRel] and friends) bypasses the request/reply machinery
 * entirely: those frames are fire-and-forget, because a dropped motion sample is
 * always better than a queue of stale ones.
 */
class DeckClient(private val scope: CoroutineScope) {

    private val _link = MutableStateFlow<Link>(Link.Idle)
    val link: StateFlow<Link> = _link.asStateFlow()

    private val _events = MutableSharedFlow<JSONObject>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<JSONObject> = _events

    private var socket: Socket? = null
    private var out: OutputStream? = null
    private var reader: Job? = null

    private val nextId = AtomicInteger(1)
    private val pending = HashMap<Int, CompletableDeferred<JSONObject>>()
    private val pendingLock = Mutex()
    private val writeLock = Mutex()

    val isUp: Boolean get() = _link.value is Link.Up

    suspend fun connect(host: String, port: Int = Net.PORT) {
        disconnect()
        _link.value = Link.Connecting(host)
        try {
            val s = withContext(Dispatchers.IO) {
                Socket().apply {
                    tcpNoDelay = true
                    // Long enough to cross a sleepy Wi-Fi link, short enough that a
                    // wrong address does not look like a hang.
                    connect(InetSocketAddress(host, port), 6000)
                    soTimeout = 0
                }
            }
            socket = s
            out = s.getOutputStream()
            _link.value = Link.Up(host)
            reader = scope.launch(Dispatchers.IO) { readLoop(s) }
        } catch (e: Exception) {
            _link.value = Link.Down(e.message ?: "could not connect")
            throw DeckException(e.message ?: "could not connect to $host")
        }
    }

    suspend fun disconnect() {
        reader?.cancel()
        reader = null
        withContext(Dispatchers.IO) {
            runCatching { socket?.close() }
        }
        socket = null
        out = null
        failAllPending("disconnected")
        if (_link.value is Link.Up || _link.value is Link.Connecting) {
            _link.value = Link.Idle
        }
    }

    private suspend fun failAllPending(why: String) {
        pendingLock.withLock {
            pending.values.forEach { it.completeExceptionally(DeckException(why)) }
            pending.clear()
        }
    }

    private suspend fun readLoop(s: Socket) {
        val input = DataInputStream(s.getInputStream().buffered())
        try {
            while (true) {
                val len = input.readInt()
                if (len <= 0 || len > Frame.MAX_LEN) {
                    throw IOException("frame length $len out of range")
                }
                val type = input.readUnsignedByte()
                val body = ByteArray(len - 1)
                input.readFully(body)

                if (type != Frame.JSON) continue
                val msg = runCatching { JSONObject(String(body, Charsets.UTF_8)) }.getOrNull()
                    ?: continue

                val id = msg.optInt("id", 0)
                if (id == 0) {
                    _events.emit(msg)
                } else {
                    val waiter = pendingLock.withLock { pending.remove(id) }
                    if (waiter != null) waiter.complete(msg) else _events.emit(msg)
                }
            }
        } catch (_: java.util.concurrent.CancellationException) {
            // Ordinary teardown.
        } catch (e: Exception) {
            Log.w(TAG, "read loop ended: ${e.message}")
            _link.value = Link.Down(e.message ?: "connection lost")
            failAllPending(e.message ?: "connection lost")
        }
    }

    private suspend fun sendFrame(type: Int, payload: ByteArray) {
        val stream = out ?: throw DeckException("not connected")
        val header = ByteArray(5)
        val flen = payload.size + 1
        header[0] = (flen ushr 24).toByte()
        header[1] = (flen ushr 16).toByte()
        header[2] = (flen ushr 8).toByte()
        header[3] = flen.toByte()
        header[4] = type.toByte()
        writeLock.withLock {
            withContext(Dispatchers.IO) {
                try {
                    stream.write(header)
                    stream.write(payload)
                    stream.flush()
                } catch (e: IOException) {
                    throw DeckException(e.message ?: "write failed")
                }
            }
        }
    }

    /** Issue an op and await its reply. Throws [DeckException] if the device says no. */
    suspend fun call(op: String, build: (JSONObject.() -> Unit)? = null): JSONObject {
        val id = nextId.getAndIncrement().let { if (it == 0) nextId.getAndIncrement() else it }
        val req = JSONObject().put("id", id).put("op", op)
        build?.invoke(req)

        val waiter = CompletableDeferred<JSONObject>()
        pendingLock.withLock { pending[id] = waiter }
        try {
            sendFrame(Frame.JSON, req.toString().toByteArray(Charsets.UTF_8))
            // A scan blocks the device for its whole duration, so the ceiling has
            // to clear the longest op rather than a typical one.
            val reply = withTimeout(25_000) { waiter.await() }
            if (!reply.optBoolean("ok", false)) {
                throw DeckException(reply.optString("err", "request failed"))
            }
            return reply
        } catch (e: TimeoutCancellationException) {
            pendingLock.withLock { pending.remove(id) }
            throw DeckException("the deck did not answer")
        } catch (e: Exception) {
            pendingLock.withLock { pending.remove(id) }
            throw e
        }
    }

    // --- live input -------------------------------------------------------
    // Errors are swallowed: these are sampled continuously from gestures, and a
    // toast per dropped frame would be worse than the drop.

    private fun send(bytes: ByteArray) {
        scope.launch { runCatching { sendFrame(Frame.INPUT, bytes) } }
    }

    fun mouseRel(dx: Int, dy: Int, wheel: Int = 0, hwheel: Int = 0, buttons: Int = 0) {
        val b = ByteArray(8)
        b[0] = InputKind.MOUSE_REL.toByte()
        b[1] = (dx shr 8).toByte(); b[2] = dx.toByte()
        b[3] = (dy shr 8).toByte(); b[4] = dy.toByte()
        b[5] = wheel.toByte(); b[6] = hwheel.toByte(); b[7] = buttons.toByte()
        send(b)
    }

    fun mouseAbs(x: Int, y: Int, wheel: Int = 0, buttons: Int = 0) {
        val b = ByteArray(7)
        b[0] = InputKind.MOUSE_ABS.toByte()
        b[1] = (x shr 8).toByte(); b[2] = x.toByte()
        b[3] = (y shr 8).toByte(); b[4] = y.toByte()
        b[5] = wheel.toByte(); b[6] = buttons.toByte()
        send(b)
    }

    fun key(down: Boolean, mods: Int, usage: Int) {
        send(byteArrayOf(InputKind.KEY.toByte(), if (down) 1 else 0, mods.toByte(), usage.toByte()))
    }

    fun ping() {
        scope.launch { runCatching { sendFrame(Frame.PING, ByteArray(0)) } }
    }

    private companion object {
        const val TAG = "DeckClient"
    }
}
