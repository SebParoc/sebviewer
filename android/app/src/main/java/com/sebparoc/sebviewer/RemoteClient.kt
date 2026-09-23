package com.sebparoc.sebviewer

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** WebSocket connection to sebviewer-host. Callbacks run on a background thread. */
class RemoteClient(
    private val host: String,
    private val port: Int,
    private val pin: String,
    private val listener: Listener,
) {
    interface Listener {
        fun onHello(width: Int, height: Int, name: String)
        fun onSize(width: Int, height: Int)
        fun onFrame(bitmap: Bitmap, bytes: Int)
        fun onPong(sentAt: Long)
        fun onClipboard(text: String)
        fun onError(message: String)
        fun onClosed()
    }

    private val http = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .build()
    private var ws: WebSocket? = null
    @Volatile private var closed = false

    private val pool = arrayOfNulls<Bitmap>(3)
    private var poolIndex = 0
    private val opts = BitmapFactory.Options().apply {
        inPreferredConfig = Bitmap.Config.RGB_565
        inMutable = true
    }

    fun connect() {
        val url = if (host.contains(":") && !host.startsWith("[")) "ws://[$host]:$port/" else "ws://$host:$port/"
        val req = Request.Builder().url(url).build()
        ws = http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val auth = JSONObject().put("t", "auth").put("pin", pin)
                    .put("name", android.os.Build.MODEL ?: "Android")
                webSocket.send(auth.toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val m = JSONObject(text)
                    when (m.optString("t")) {
                        "hello" -> listener.onHello(m.getInt("w"), m.getInt("h"), m.optString("name", "PC"))
                        "size" -> listener.onSize(m.getInt("w"), m.getInt("h"))
                        "err" -> listener.onError(m.optString("msg", "error"))
                        "pong" -> listener.onPong(m.optLong("ts"))
                        "clip" -> listener.onClipboard(m.optString("s"))
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "bad message", e)
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val data = bytes.toByteArray()
                val bmp = decode(data)
                webSocket.send(ACK)
                if (bmp != null) listener.onFrame(bmp, data.size)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!closed) listener.onError(t.message ?: t.javaClass.simpleName)
                closed = true
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!closed) listener.onClosed()
                closed = true
            }
        })
    }

    private fun decode(data: ByteArray): Bitmap? {
        val idx = poolIndex
        poolIndex = (poolIndex + 1) % pool.size
        val reuse = pool[idx]
        opts.inBitmap = reuse
        var bmp: Bitmap? = null
        try {
            bmp = BitmapFactory.decodeByteArray(data, 0, data.size, opts)
        } catch (e: IllegalArgumentException) {
            // inBitmap could not be reused (size/config changed)
        }
        if (bmp == null) {
            opts.inBitmap = null
            bmp = try { BitmapFactory.decodeByteArray(data, 0, data.size, opts) } catch (e: Exception) { null }
        }
        pool[idx] = bmp
        return bmp
    }

    fun send(obj: JSONObject) {
        ws?.send(obj.toString())
    }

    fun close() {
        closed = true
        ws?.close(1000, "bye")
        ws = null
        http.dispatcher.executorService.shutdown()
    }

    companion object {
        private const val TAG = "RemoteClient"
        private val ACK = JSONObject().put("t", "ack").toString()
    }
}
