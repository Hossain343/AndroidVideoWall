package com.videowall.webrtc

import android.os.SystemClock
import android.util.Log
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import org.json.JSONObject
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class SignalingServer(
    private val port: Int = 8080,
    private val expectedSession: String,
    private val onClientMessage: (clientId: String, msg: JSONObject) -> Unit,
    private val onClientJoined: (clientId: String, phoneNumber: Int) -> Unit,
    private val onClientLeft: (clientId: String) -> Unit
) : WebSocketServer(InetSocketAddress(port)) {

    private val sockets = ConcurrentHashMap<String, WebSocket>()
    private val phoneCounter = AtomicInteger(0)
    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private var timeBeacon: ScheduledFuture<*>? = null

    override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
        Log.i(TAG, "Socket open: ${conn.remoteSocketAddress}")
    }

    override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) {
        val id = sockets.entries.find { it.value == conn }?.key
        if (id != null) {
            sockets.remove(id)
            onClientLeft(id)
            Log.i(TAG, "Client left: $id")
        }
    }

    override fun onMessage(conn: WebSocket, message: String) {
        try {
            val json = JSONObject(message)
            when (json.type()) {
                Msg.JOIN -> {
                    val session = json.optString(Msg.SESSION, "")
                    if (session != expectedSession) {
                        conn.close(4001, "bad session")
                        return
                    }
                    val id = json.getString(Msg.CLIENT_ID)
                    sockets[id] = conn
                    val phone = phoneCounter.incrementAndGet()
                    conn.send(welcomeMsg(id, phone).toString())
                    onClientJoined(id, phone)
                }
                Msg.TIME_REQ -> {
                    val mono = SystemClock.elapsedRealtimeNanos()
                    val wall = System.currentTimeMillis()
                    conn.send(timeMsg(mono, wall).toString())
                }
                else -> {
                    val id = json.optString(
                        Msg.CLIENT_ID,
                        sockets.entries.find { it.value == conn }?.key ?: ""
                    )
                    if (id.isNotEmpty()) onClientMessage(id, json)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Bad message", e)
        }
    }

    override fun onError(conn: WebSocket?, ex: Exception) {
        Log.e(TAG, "Server error", ex)
    }

    override fun onStart() {
        Log.i(TAG, "Signaling on port $port session=$expectedSession")
        connectionLostTimeout = 30
        startTimeBeacon()
    }

    private fun startTimeBeacon() {
        timeBeacon = scheduler.scheduleAtFixedRate({
            val msg = timeMsg(SystemClock.elapsedRealtimeNanos(), System.currentTimeMillis()).toString()
            broadcast(msg)
        }, 0, 250, TimeUnit.MILLISECONDS)
    }

    fun sendTo(clientId: String, msg: JSONObject) {
        sockets[clientId]?.send(msg.toString())
    }

    fun broadcastJson(msg: JSONObject) {
        broadcast(msg.toString())
    }

    fun stopServer() {
        timeBeacon?.cancel(true)
        scheduler.shutdownNow()
        try {
            stop(1000)
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val TAG = "SignalingServer"
    }
}
