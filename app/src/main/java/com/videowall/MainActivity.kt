package com.videowall

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier.modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.videowall.audio.SpatialAudioController
import com.videowall.render.CroppedVideoSink
import com.videowall.render.GridCalculator
import com.videowall.sync.TimeSyncManager
import com.videowall.webrtc.Msg
import com.videowall.webrtc.SignalingClient
import com.videowall.webrtc.SignalingServer
import com.videowall.webrtc.WebRtcEngine
import com.videowall.webrtc.answerMsg
import com.videowall.webrtc.iceMsg
import com.videowall.webrtc.offerMsg
import com.videowall.webrtc.type
import org.json.JSONObject
import org.webrtc.IceCandidate
import org.webrtc.SessionDescription
import org.webrtc.SurfaceViewRenderer
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URI

enum class Role { NONE, MASTER, CLIENT }

class MainActivity : ComponentActivity() {

    private lateinit var engine: WebRtcEngine
    private val timeSync = TimeSyncManager()
    private val spatial = SpatialAudioController()
    private var signalingServer: SignalingServer? = null
    private var signalingClient: SignalingClient? = null
    private var croppedSink: CroppedVideoSink? = null
    private var renderer: SurfaceViewRenderer? = null

    private var role by mutableStateOf(Role.NONE)
    private var status by mutableStateOf("Ready")
    private var localIp by mutableStateOf("-")

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            startMaster(result.resultCode, result.data!!)
        } else {
            status = "Screen capture denied"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        engine = WebRtcEngine(applicationContext)
        localIp = getLocalIpAddress() ?: "unknown"

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color.Black
                ) {
                    when (role) {
                        Role.NONE -> RoleSelection(
                            onMaster = { requestScreenCapture() },
                            onClient = { role = Role.CLIENT }
                        )
                        Role.MASTER -> MasterScreen(
                            ip = localIp,
                            status = status,
                            onStop = {
                                stopAll()
                                role = Role.NONE
                            }
                        )
                        Role.CLIENT -> ClientScreen(
                            status = status,
                            onConnect = { ip, port, index, cols, rows ->
                                connectClient(ip, port, index, cols, rows)
                            },
                            onStop = {
                                stopAll()
                                role = Role.NONE
                            },
                            onRendererReady = { r ->
                                renderer = r
                                r.init(engine.eglBase.eglBaseContext, null)
                                r.setMirror(false)
                                r.setEnableHardwareScaler(true)
                            }
                        )
                    }
                }
            }
        }
    }

    private fun requestScreenCapture() {
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(mpm.createScreenCaptureIntent())
    }

    private fun startMaster(resultCode: Int, data: Intent) {
        role = Role.MASTER
        status = "Starting capture..."
        val dm = resources.displayMetrics
        engine.startScreenCapture(resultCode, data, dm.widthPixels, dm.heightPixels, 30)

        signalingServer = SignalingServer(
            port = 8080,
            onClientMessage = { clientId, msg -> handleMasterMessage(clientId, msg) },
            onClientJoined = { clientId, index, cols, rows ->
                status = "Client $clientId joined as tile $index"
                engine.createOfferForClient(
                    clientId = clientId,
                    onLocalSdp = { sdp ->
                        signalingServer?.sendTo(clientId, offerMsg(sdp.description, clientId))
                    },
                    onIce = { ice ->
                        signalingServer?.sendTo(
                            clientId,
                            iceMsg(ice.sdp, ice.sdpMid, ice.sdpMLineIndex, clientId)
                        )
                    }
                )
            },
            onClientLeft = { clientId ->
                engine.removeClient(clientId)
                status = "Client $clientId left"
            }
        )
        try {
            signalingServer?.start()
            status = "Master live @ $localIp:8080"
        } catch (e: Exception) {
            status = "Server error: ${e.message}"
            Log.e(TAG, "Server start failed", e)
        }
    }

    private fun handleMasterMessage(clientId: String, msg: JSONObject) {
        when (msg.type()) {
            Msg.ANSWER -> {
                val sdp = SessionDescription(SessionDescription.Type.ANSWER, msg.getString(Msg.SDP))
                engine.handleAnswer(clientId, sdp)
            }
            Msg.ICE -> {
                val ice = IceCandidate(
                    msg.optString(Msg.SDP_MID),
                    msg.getInt(Msg.SDP_MLINE),
                    msg.getString(Msg.CANDIDATE)
                )
                engine.addIceCandidate(clientId, ice)
            }
        }
    }

    private fun connectClient(ip: String, port: Int, index: Int, cols: Int, rows: Int) {
        status = "Connecting..."
        val clientId = WebRtcEngine.newClientId()
        val crop = GridCalculator.cropForIndex(index, cols, rows)
        spatial.setGridPan(GridCalculator.horizontalPan(index, cols, rows))

        engine.createAnswerPeer(
            onRemoteTrack = { track ->
                runOnUiThread {
                    val r = renderer ?: return@runOnUiThread
                    croppedSink = CroppedVideoSink(r, crop)
                    track.addSink(croppedSink)
                    status = "Streaming tile $index"
                }
            },
            onLocalSdp = { },
            onIce = { ice ->
                signalingClient?.send(
                    iceMsg(ice.sdp, ice.sdpMid, ice.sdpMLineIndex, clientId)
                )
            }
        )

        signalingClient = SignalingClient(
            serverUri = URI("ws://$ip:$port"),
            clientId = clientId,
            timeSync = timeSync,
            onMessage = { msg -> handleClientMessage(msg, clientId) },
            onConnected = {
                signalingClient?.join(index, cols, rows)
                status = "Joined, waiting for offer..."
            },
            onDisconnected = { status = "Disconnected" }
        )
        signalingClient?.connect()
    }

    private fun handleClientMessage(msg: JSONObject, clientId: String) {
        when (msg.type()) {
            Msg.OFFER -> {
                val sdp = SessionDescription(SessionDescription.Type.OFFER, msg.getString(Msg.SDP))
                engine.handleOffer(sdp) { answer ->
                    signalingClient?.send(answerMsg(answer.description, clientId))
                }
            }
            Msg.ICE -> {
                val ice = IceCandidate(
                    msg.optString(Msg.SDP_MID),
                    msg.getInt(Msg.SDP_MLINE),
                    msg.getString(Msg.CANDIDATE)
                )
                engine.addRemoteIce(ice)
            }
            Msg.ACTION_X -> {
                spatial.setActionX(msg.getDouble(Msg.X).toFloat())
            }
        }
    }

    private fun stopAll() {
        signalingClient?.close()
        signalingClient = null
        signalingServer?.stopServer()
        signalingServer = null
        croppedSink = null
        renderer?.release()
        renderer = null
        engine.release()
        engine = WebRtcEngine(applicationContext)
        status = "Stopped"
    }

    override fun onDestroy() {
        stopAll()
        super.onDestroy()
    }

    private fun getLocalIpAddress(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                val addrs = iface.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        return addr.hostAddress
                    }
                }
            }
        } catch (_: Exception) {
        }
        return null
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}

@Composable
fun RoleSelection(onMaster: () -> Unit, onClient: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Video Wall",
            style = MaterialTheme.typography.headlineLarge,
            color = Color.White
        )
        Spacer(modifier = Modifier.height(48.dp))
        Button(
            onClick = onMaster,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("MASTER - Stream Screen")
        }
        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = onClient,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("CLIENT - Join as Tile")
        }
    }
}

@Composable
fun MasterScreen(ip: String, status: String, onStop: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "MASTER",
            style = MaterialTheme.typography.headlineMedium,
            color = Color.Green
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(text = "IP: $ip", color = Color.White)
        Text(text = "Port: 8080", color = Color.White)
        Spacer(modifier = Modifier.height(8.dp))
        Text(text = status, color = Color.LightGray)
        Spacer(modifier = Modifier.height(32.dp))
        Button(onClick = onStop) {
            Text("Stop")
        }
    }
}

@Composable
fun ClientScreen(
    status: String,
    onConnect: (ip: String, port: Int, index: Int, cols: Int, rows: Int) -> Unit,
    onStop: () -> Unit,
    onRendererReady: (SurfaceViewRenderer) -> Unit
) {
    var ip by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("8080") }
    var index by remember { mutableStateOf("1") }
    var cols by remember { mutableStateOf("2") }
    var rows by remember { mutableStateOf("2") }
    var connected by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        if (!connected) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "CLIENT",
                    style = MaterialTheme.typography.headlineMedium,
                    color = Color.Cyan
                )
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedTextField(
                    value = ip,
                    onValueChange = { ip = it },
                    label = { Text("Master IP") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it },
                    label = { Text("Port") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = index,
                    onValueChange = { index = it },
                    label = { Text("Grid Number (1-based)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
                Row {
                    OutlinedTextField(
                        value = cols,
                        onValueChange = { cols = it },
                        label = { Text("Columns") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    OutlinedTextField(
                        value = rows,
                        onValueChange = { rows = it },
                        label = { Text("Rows") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
                Text(text = status, color = Color.LightGray)
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = {
                        connected = true
                        onConnect(
                            ip.trim(),
                            port.toIntOrNull() ?: 8080,
                            index.toIntOrNull() ?: 1,
                            cols.toIntOrNull() ?: 2,
                            rows.toIntOrNull() ?: 2
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Connect")
                }
                Spacer(modifier = Modifier.height(8.dp))
                TextButton(onClick = onStop) {
                    Text("Back")
                }
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
            ) {
                AndroidView(
                    factory = { ctx ->
                        SurfaceViewRenderer(ctx).also { onRendererReady(it) }
                    },
                    modifier = Modifier.fillMaxSize()
                )
                Text(
                    text = status,
                    color = Color.White.copy(alpha = 0.7f),
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                )
                Button(
                    onClick = {
                        connected = false
                        onStop()
                    },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(16.dp)
                ) {
                    Text("Stop")
                }
            }
        }
    }
}
