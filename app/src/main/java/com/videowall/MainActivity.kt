package com.videowall

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.zxing.integration.android.IntentIntegrator
import com.videowall.audio.SpatialAudioController
import com.videowall.model.ClientNode
import com.videowall.model.GridMath
import com.videowall.model.JoinUri
import com.videowall.render.CroppedVideoSink
import com.videowall.render.JitterBufferSink
import com.videowall.sync.TimeSyncManager
import com.videowall.util.QrBitmap
import com.videowall.webrtc.Msg
import com.videowall.webrtc.ScreenCaptureService
import com.videowall.webrtc.SignalingClient
import com.videowall.webrtc.SignalingServer
import com.videowall.webrtc.WebRtcEngine
import com.videowall.webrtc.answerMsg
import com.videowall.webrtc.assignMsg
import com.videowall.webrtc.brightnessMsg
import com.videowall.webrtc.colorSyncMsg
import com.videowall.webrtc.iceMsg
import com.videowall.webrtc.offerMsg
import com.videowall.webrtc.type
import org.json.JSONObject
import org.webrtc.IceCandidate
import org.webrtc.RendererCommon
import org.webrtc.SessionDescription
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URI
import java.util.UUID
import kotlin.math.roundToInt

enum class Role { NONE, MASTER, CLIENT }

class MainActivity : ComponentActivity() {

    private lateinit var engine: WebRtcEngine
    private val timeSync = TimeSyncManager()
    private val spatial = SpatialAudioController()

    private var signalingServer: SignalingServer? = null
    private var signalingClient: SignalingClient? = null
    private var croppedSink: CroppedVideoSink? = null
    private var jitterSink: JitterBufferSink? = null
    private var renderer: SurfaceViewRenderer? = null
    private var remoteTrack: VideoTrack? = null

    private var role by mutableStateOf(Role.NONE)
    private var status by mutableStateOf("Ready")
    private var localIp by mutableStateOf("-")
    private var sessionId by mutableStateOf("")
    private var qrBitmap by mutableStateOf<Bitmap?>(null)

    private var gridColumns by mutableIntStateOf(2)
    private var gridRows by mutableIntStateOf(2)
    private val clients = mutableStateListOf<ClientNode>()
    private var colorSyncOn by mutableStateOf(false)
    private var masterBrightness by mutableFloatStateOf(0.7f)

    private var myPhone by mutableIntStateOf(0)
    private var myClientId by mutableStateOf("")
    private var audioEnabled by mutableStateOf(true)
    private var clientColorSync by mutableStateOf(false)

    private var controlsVisible by mutableStateOf(true)
    private val hideHandler = Handler(Looper.getMainLooper())
    private val hideRunnable = Runnable { controlsVisible = false }

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    private val cameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) launchQrScanner() else status = "Camera permission required to scan QR"
    }

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            beginMasterSession(result.resultCode, result.data!!)
        } else {
            status = "Screen capture denied"
            ScreenCaptureService.stop(this)
        }
    }

    private val qrScanLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val scanned = IntentIntegrator.parseActivityResult(result.resultCode, result.data)
        val contents = scanned?.contents
        if (contents.isNullOrBlank()) {
            status = "QR scan cancelled"
            return@registerForActivityResult
        }
        val parsed = JoinUri.decode(contents)
        if (parsed == null) {
            status = "Invalid QR code"
            return@registerForActivityResult
        }
        connectClient(parsed.first, parsed.second, parsed.third)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyImmersiveMode()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        engine = WebRtcEngine(applicationContext)
        localIp = getLocalIpAddress() ?: "unknown"

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
                    when (role) {
                        Role.NONE -> RoleSelection(
                            onMaster = { prepareMaster() },
                            onClient = { prepareClient() }
                        )
                        Role.MASTER -> MasterDashboard(
                            ip = localIp,
                            sessionId = sessionId,
                            status = status,
                            qr = qrBitmap,
                            columns = gridColumns,
                            rows = gridRows,
                            clients = clients.toList(),
                            colorSync = colorSyncOn,
                            masterBrightness = masterBrightness,
                            controlsVisible = controlsVisible,
                            onUserInteraction = { bumpControls() },
                            onColumns = { gridColumns = it.coerceIn(1, 6) },
                            onRows = { gridRows = it.coerceIn(1, 6) },
                            onDrop = { clientId, col, row -> placeClient(clientId, col, row) },
                            onBrightness = { id, v -> setClientBrightness(id, v) },
                            onSyncAllBrightness = { syncAllBrightness() },
                            onMasterBrightness = { masterBrightness = it },
                            onColorSync = { toggleColorSync(it) },
                            onStop = { stopAll(); role = Role.NONE }
                        )
                        Role.CLIENT -> ClientScreen(
                            status = status,
                            phoneNumber = myPhone,
                            audioEnabled = audioEnabled,
                            colorSync = clientColorSync,
                            controlsVisible = controlsVisible,
                            onUserInteraction = { bumpControls() },
                            onToggleAudio = { toggleLocalAudio(!audioEnabled) },
                            onScanAgain = { requestCameraAndScan() },
                            onStop = { stopAll(); role = Role.NONE },
                            onRendererReady = { r ->
                                renderer = r
                                try {
                                    r.init(engine.eglBase.eglBaseContext, null)
                                    r.setMirror(false)
                                    r.setEnableHardwareScaler(true)
                                    r.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                                } catch (e: Exception) {
                                    Log.e(TAG, "renderer init", e)
                                }
                                remoteTrack?.let { attachTrack(it) }
                            }
                        )
                    }
                }
            }
        }
        bumpControls()
    }

    private fun applyImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyImmersiveMode()
    }

    private fun bumpControls() {
        controlsVisible = true
        hideHandler.removeCallbacks(hideRunnable)
        hideHandler.postDelayed(hideRunnable, 3000L)
    }

    private fun prepareMaster() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        try {
            ScreenCaptureService.start(this)
        } catch (e: Exception) {
            status = "FGS start failed: ${e.message}"
            Log.e(TAG, "FGS", e)
            return
        }
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        try {
            projectionLauncher.launch(mpm.createScreenCaptureIntent())
        } catch (e: Exception) {
            status = "Cannot start capture: ${e.message}"
            ScreenCaptureService.stop(this)
        }
    }

    private fun beginMasterSession(resultCode: Int, data: Intent) {
        role = Role.MASTER
        sessionId = UUID.randomUUID().toString().take(8)
        status = "Starting room..."
        clients.clear()
        bumpControls()
        val dm = resources.displayMetrics
        try {
            engine.startScreenCapture(resultCode, data, dm.widthPixels, dm.heightPixels, 30)
        } catch (e: Exception) {
            status = "Capture crash: ${e.message}"
            Log.e(TAG, "capture", e)
            ScreenCaptureService.stop(this)
            return
        }

        signalingServer = SignalingServer(
            port = 8080,
            expectedSession = sessionId,
            onClientMessage = { id, msg -> handleMasterMessage(id, msg) },
            onClientJoined = { id, phone ->
                runOnUiThread {
                    clients.add(ClientNode(clientId = id, phoneNumber = phone))
                    status = "Phone #$phone joined"
                    bumpControls()
                    engine.createOfferForClient(
                        clientId = id,
                        onLocalSdp = { sdp ->
                            signalingServer?.sendTo(id, offerMsg(sdp.description, id))
                        },
                        onIce = { ice ->
                            signalingServer?.sendTo(
                                id,
                                iceMsg(ice.sdp, ice.sdpMid, ice.sdpMLineIndex, id)
                            )
                        }
                    )
                }
            },
            onClientLeft = { id ->
                runOnUiThread {
                    clients.removeAll { it.clientId == id }
                    engine.removeClient(id)
                    status = "Client $id left"
                }
            }
        )
        try {
            Thread { signalingServer?.start() }.start()
            val uri = JoinUri.encode(localIp, 8080, sessionId)
            qrBitmap = QrBitmap.encode(uri, 640)
            status = "Room live — scan QR"
        } catch (e: Exception) {
            status = "Server error: ${e.message}"
            Log.e(TAG, "server", e)
        }
    }

    private fun placeClient(clientId: String, col: Int, row: Int) {
        val idx = clients.indexOfFirst { it.clientId == clientId }
        if (idx < 0) return
        for (i in clients.indices) {
            val c = clients[i]
            if (c.gridCol == col && c.gridRow == row && c.clientId != clientId) {
                clients[i] = c.copy(gridCol = null, gridRow = null)
            }
        }
        clients[idx] = clients[idx].copy(gridCol = col, gridRow = row)
        signalingServer?.sendTo(
            clientId,
            assignMsg(clientId, col, row, gridColumns, gridRows)
        )
        status = "Phone #${clients[idx].phoneNumber} → tile ($col,$row)"
        bumpControls()
    }

    private fun setClientBrightness(clientId: String, value: Float) {
        val idx = clients.indexOfFirst { it.clientId == clientId }
        if (idx >= 0) clients[idx] = clients[idx].copy(brightness = value)
        signalingServer?.sendTo(clientId, brightnessMsg(clientId, value))
    }

    private fun syncAllBrightness() {
        val v = masterBrightness
        clients.forEach { setClientBrightness(it.clientId, v) }
        status = "Brightness synced to ${(v * 100).toInt()}%"
        bumpControls()
    }

    private fun toggleColorSync(enabled: Boolean) {
        colorSyncOn = enabled
        signalingServer?.broadcastJson(colorSyncMsg(enabled))
        status = if (enabled) "Color Sync ON" else "Color Sync OFF"
        bumpControls()
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

    private fun prepareClient() {
        role = Role.CLIENT
        status = "Scan Master QR code"
        bumpControls()
        requestCameraAndScan()
    }

    private fun requestCameraAndScan() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            launchQrScanner()
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun launchQrScanner() {
        val integrator = IntentIntegrator(this)
        integrator.setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)
        integrator.setPrompt("Scan Video Wall Master QR")
        integrator.setBeepEnabled(false)
        integrator.setOrientationLocked(false)
        qrScanLauncher.launch(integrator.createScanIntent())
    }

    private fun connectClient(ip: String, port: Int, session: String) {
        status = "Connecting..."
        myClientId = WebRtcEngine.newClientId()
        sessionId = session

        engine.createAnswerPeer(
            onRemoteTrack = { track ->
                runOnUiThread {
                    remoteTrack = track
                    attachTrack(track)
                    status = "Streaming"
                    bumpControls()
                }
            },
            onRemoteAudio = { audioTrack ->
                runOnUiThread {
                    audioTrack.setEnabled(audioEnabled)
                    spatial.applyToDevice(this, audioEnabled)
                    Log.i(TAG, "Client audio track live, enabled=$audioEnabled")
                }
            },
            onLocalSdp = { },
            onIce = { ice ->
                signalingClient?.send(iceMsg(ice.sdp, ice.sdpMid, ice.sdpMLineIndex, myClientId))
            }
        )

        signalingClient = SignalingClient(
            serverUri = URI("ws://$ip:$port"),
            clientId = myClientId,
            sessionId = session,
            timeSync = timeSync,
            onMessage = { msg -> handleClientMessage(msg) },
            onConnected = { runOnUiThread { status = "Joined — waiting for offer" } },
            onDisconnected = { runOnUiThread { status = "Disconnected" } }
        )
        signalingClient?.connect()
    }

    private fun attachTrack(track: VideoTrack) {
        val r = renderer ?: return
        remoteTrack?.let { t ->
            jitterSink?.let { t.removeSink(it) }
            croppedSink?.let { t.removeSink(it) }
        }
        jitterSink?.release()
        jitterSink = null
        croppedSink = null

        val defaultCrop = GridMath.cropForTile(0, 0, 1, 1)
        val cropSink = CroppedVideoSink(r, defaultCrop)
        croppedSink = cropSink
        val jitter = JitterBufferSink(cropSink, timeSync, targetDelayMs = 80L)
        jitterSink = jitter
        track.addSink(jitter)
    }

    private fun handleClientMessage(msg: JSONObject) {
        when (msg.type()) {
            Msg.WELCOME -> {
                runOnUiThread {
                    myPhone = msg.getInt(Msg.PHONE)
                    status = "This Phone Number: $myPhone"
                    bumpControls()
                }
            }
            Msg.OFFER -> {
                val sdp = SessionDescription(SessionDescription.Type.OFFER, msg.getString(Msg.SDP))
                engine.handleOffer(sdp) { answer ->
                    signalingClient?.send(answerMsg(answer.description, myClientId))
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
            Msg.ASSIGN -> {
                val col = msg.getInt(Msg.COL)
                val row = msg.getInt(Msg.ROW)
                val cols = msg.getInt(Msg.COLUMNS)
                val rows = msg.getInt(Msg.ROWS)
                val crop = GridMath.cropForTile(col, row, cols, rows)
                spatial.setGridPan(GridMath.panForCol(col, cols))
                spatial.applyToDevice(this, audioEnabled)
                runOnUiThread {
                    croppedSink?.updateCrop(crop)
                    status = "Phone #$myPhone @ ($col,$row)"
                    bumpControls()
                }
            }
            Msg.BRIGHTNESS -> {
                val v = msg.getDouble(Msg.VALUE).toFloat().coerceIn(0.05f, 1f)
                runOnUiThread { applyBrightness(v) }
            }
            Msg.COLOR_SYNC -> {
                val en = msg.getBoolean(Msg.ENABLED)
                runOnUiThread { clientColorSync = en }
            }
            Msg.AUDIO_MUTE -> {
                val muted = msg.getBoolean(Msg.ENABLED)
                runOnUiThread { toggleLocalAudio(!muted) }
            }
        }
    }

    private fun applyBrightness(value: Float) {
        try {
            val lp = window.attributes
            lp.screenBrightness = value
            window.attributes = lp
        } catch (e: Exception) {
            Log.w(TAG, "brightness", e)
        }
    }

    private fun toggleLocalAudio(enabled: Boolean) {
        audioEnabled = enabled
        engine.setRemoteAudioEnabled(enabled)
        spatial.applyToDevice(this, enabled)
        bumpControls()
    }

    private fun stopAll() {
        hideHandler.removeCallbacks(hideRunnable)
        signalingClient?.close()
        signalingClient = null
        signalingServer?.stopServer()
        signalingServer = null
        remoteTrack?.let { t ->
            jitterSink?.let { t.removeSink(it) }
            croppedSink?.let { t.removeSink(it) }
        }
        remoteTrack = null
        jitterSink?.release()
        jitterSink = null
        croppedSink = null
        try { renderer?.release() } catch (_: Exception) {}
        renderer = null
        try { engine.release() } catch (_: Exception) {}
        engine = WebRtcEngine(applicationContext)
        ScreenCaptureService.stop(this)
        clients.clear()
        qrBitmap = null
        myPhone = 0
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
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Video Wall", style = MaterialTheme.typography.headlineLarge, color = Color.White)
        Spacer(Modifier.height(48.dp))
        Button(onClick = onMaster, modifier = Modifier.fillMaxWidth()) {
            Text("MASTER — Host Room")
        }
        Spacer(Modifier.height(16.dp))
        Button(onClick = onClient, modifier = Modifier.fillMaxWidth()) {
            Text("CLIENT — Scan QR")
        }
    }
}

@Composable
fun MasterDashboard(
    ip: String,
    sessionId: String,
    status: String,
    qr: Bitmap?,
    columns: Int,
    rows: Int,
    clients: List<ClientNode>,
    colorSync: Boolean,
    masterBrightness: Float,
    controlsVisible: Boolean,
    onUserInteraction: () -> Unit,
    onColumns: (Int) -> Unit,
    onRows: (Int) -> Unit,
    onDrop: (clientId: String, col: Int, row: Int) -> Unit,
    onBrightness: (clientId: String, value: Float) -> Unit,
    onSyncAllBrightness: () -> Unit,
    onMasterBrightness: (Float) -> Unit,
    onColorSync: (Boolean) -> Unit,
    onStop: () -> Unit
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                detectTapGestures { onUserInteraction() }
            }
    ) {
        if (controlsVisible) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp)
            ) {
                Text("MASTER DASHBOARD", color = Color.Green, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                Text("IP $ip:8080  session $sessionId", color = Color.LightGray, fontSize = 12.sp)
                Text(status, color = Color.Cyan, fontSize = 13.sp)
                Spacer(Modifier.height(12.dp))

                if (qr != null) {
                    Image(
                        bitmap = qr.asImageBitmap(),
                        contentDescription = "Join QR",
                        modifier = Modifier
                            .size(200.dp)
                            .align(Alignment.CenterHorizontally)
                            .background(Color.White)
                            .padding(8.dp)
                    )
                    Text(
                        "Clients scan this QR",
                        color = Color.Gray,
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    )
                }

                Spacer(Modifier.height(16.dp))
                Text("Grid size", color = Color.White)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Cols $columns", color = Color.White, modifier = Modifier.width(72.dp))
                    Slider(
                        value = columns.toFloat(),
                        onValueChange = { onColumns(it.roundToInt()); onUserInteraction() },
                        valueRange = 1f..6f,
                        steps = 4,
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Rows $rows", color = Color.White, modifier = Modifier.width(72.dp))
                    Slider(
                        value = rows.toFloat(),
                        onValueChange = { onRows(it.roundToInt()); onUserInteraction() },
                        valueRange = 1f..6f,
                        steps = 4,
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(Modifier.height(8.dp))
                Text("Chessboard — tap tile to place next unplaced phone", color = Color.White)
                Chessboard(
                    columns = columns,
                    rows = rows,
                    clients = clients,
                    onDrop = { id, c, r -> onDrop(id, c, r); onUserInteraction() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(columns.toFloat() / rows.coerceAtLeast(1).toFloat())
                        .padding(vertical = 8.dp)
                )

                Spacer(Modifier.height(12.dp))
                Text("Brightness", color = Color.White)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Master ref", color = Color.LightGray, modifier = Modifier.width(80.dp))
                    Slider(
                        value = masterBrightness,
                        onValueChange = { onMasterBrightness(it); onUserInteraction() },
                        modifier = Modifier.weight(1f)
                    )
                }
                Button(onClick = { onSyncAllBrightness(); onUserInteraction() }, modifier = Modifier.fillMaxWidth()) {
                    Text("Sync All Clients to Master Brightness")
                }
                clients.forEach { c ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Text("#${c.phoneNumber}", color = Color.White, modifier = Modifier.width(40.dp))
                        Slider(
                            value = c.brightness,
                            onValueChange = { onBrightness(c.clientId, it); onUserInteraction() },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))
                FilterChip(
                    selected = colorSync,
                    onClick = { onColorSync(!colorSync); onUserInteraction() },
                    label = { Text(if (colorSync) "Sync Color ON" else "Sync Color") }
                )

                Spacer(Modifier.height(16.dp))
                Text("Unplaced devices", color = Color.Gray)
                Row(modifier = Modifier.fillMaxWidth()) {
                    clients.filter { !it.isPlaced }.forEach { c ->
                        Box(
                            Modifier
                                .padding(4.dp)
                                .background(Color(0xFF1565C0), RoundedCornerShape(8.dp))
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Text("Phone #${c.phoneNumber}", color = Color.White)
                        }
                    }
                }

                Spacer(Modifier.height(24.dp))
                OutlinedButton(onClick = onStop, modifier = Modifier.fillMaxWidth()) {
                    Text("Stop Room")
                }
            }
        } else {
            Text(
                "Tap for controls",
                color = Color.DarkGray,
                fontSize = 12.sp,
                modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp)
            )
        }
    }
}

@Composable
fun Chessboard(
    columns: Int,
    rows: Int,
    clients: List<ClientNode>,
    onDrop: (String, Int, Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier
            .background(Color(0xFF1A1A1A))
            .border(1.dp, Color.Gray)
    ) {
        Column(Modifier.fillMaxSize()) {
            repeat(rows) { r ->
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    repeat(columns) { c ->
                        val occupant = clients.find { it.gridCol == c && it.gridRow == r }
                        val dark = (c + r) % 2 == 0
                        Box(
                            Modifier
                                .weight(1f)
                                .fillMaxSize()
                                .background(if (dark) Color(0xFF2E2E2E) else Color(0xFF3A3A3A))
                                .border(0.5.dp, Color.DarkGray)
                                .clickable {
                                    val free = clients.firstOrNull { !it.isPlaced }
                                    if (free != null) onDrop(free.clientId, c, r)
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            if (occupant != null) {
                                Text(
                                    "#${occupant.phoneNumber}",
                                    color = Color.Cyan,
                                    fontWeight = FontWeight.Bold
                                )
                            } else {
                                Text("${c + 1},${r + 1}", color = Color.DarkGray, fontSize = 10.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ClientScreen(
    status: String,
    phoneNumber: Int,
    audioEnabled: Boolean,
    colorSync: Boolean,
    controlsVisible: Boolean,
    onUserInteraction: () -> Unit,
    onToggleAudio: () -> Unit,
    onScanAgain: () -> Unit,
    onStop: () -> Unit,
    onRendererReady: (SurfaceViewRenderer) -> Unit
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                detectTapGestures { onUserInteraction() }
            }
    ) {
        AndroidView(
            factory = { ctx ->
                SurfaceViewRenderer(ctx).also { onRendererReady(it) }
            },
            modifier = Modifier.fillMaxSize()
        )
        if (colorSync) {
            Box(Modifier.fillMaxSize().background(Color(0x22FFCC80)))
        }
        if (controlsVisible) {
            Column(
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(16.dp)
                    .background(Color(0xAA000000), RoundedCornerShape(12.dp))
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    if (phoneNumber > 0) "This Phone Number: $phoneNumber" else "Waiting for assignment…",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
                Text(status, color = Color.LightGray, fontSize = 12.sp)
            }
            Row(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(onClick = { onToggleAudio(); onUserInteraction() }) {
                    Text(if (audioEnabled) "Mute Speaker" else "Unmute Speaker")
                }
                Button(onClick = { onScanAgain(); onUserInteraction() }) { Text("Rescan QR") }
                Button(onClick = onStop) { Text("Stop") }
            }
        }
    }
}
