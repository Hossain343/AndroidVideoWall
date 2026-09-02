package com.videowall.webrtc

import android.os.SystemClock
import android.util.Log
import com.videowall.sync.TimeSyncManager
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import org.json.JSONObject
import java.net.URI
import java.util.concurrent.atomic.AtomicLong

class SignalingClient(
    private val serverUri: URI,
    private val clientId: String,
    private val sessionId: String,
    private val timeSync: TimeSyncManager,
    private val onMessage: (JSONObject) -> Unit,
    private val onConnected: () -> Unit,
    private val onDisconnected: () -> Unit
) {
    private var client: WebSocketClient? = null
    private val lastTimeReqNs = AtomicLong(0)

    fun connect() {
        client = object : WebSocketClient(serverUri) {
            override fun onOpen(handshakedata: ServerHandshake?) {
                Log.i(TAG, "Connected")
                onConnected()
                send(joinMsg(clientId, sessionId))
                send(timeReqMsg())
            }

            override fun onMessage(message: String?) {
                if (message == null) return
                try {
                    val json = JSONObject(message)
                    when (json.type()) {
                        Msg.TIME -> {
                            val receive = SystemClock.elapsedRealtimeNanos()
                            val masterMono = json.getLong(Msg.T)
                            val sendNs = lastTimeReqNs.getAndSet(0)
                            timeSync.onMasterTime(
                                masterMonoNs = masterMono,
                                receiveLocalNs = receive,
                                sendLocalNs = if (sendNs > 0) sendNs else null
                            )
                        }
                        else -> onMessage(json)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Parse error", e)
                }
            }

            override fun onClose(code: Int, reason: String?, remote: Boolean) {
                Log.i(TAG, "Disconnected: $reason")
                onDisconnected()
            }

            override fun onError(ex: Exception?) {
                Log.e(TAG, "Client error", ex)
            }
        }
        client?.connectionLostTimeout = 20
        client?.connect()
    }

    fun send(msg: JSONObject) {
        if (msg.type() == Msg.TIME_REQ) {
            lastTimeReqNs.set(SystemClock.elapsedRealtimeNanos())
        }
        try {
            client?.send(msg.toString())
        } catch (e: Exception) {
            Log.w(TAG, "send failed", e)
        }
    }

    fun close() {
        try {
            client?.close()
        } catch (_: Exception) {
        }
        client = null
    }

    companion object {
        private const val TAG = "SignalingClient"
    }
}
