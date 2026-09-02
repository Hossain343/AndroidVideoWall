package com.videowall.webrtc

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.util.Log
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoCapturer
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class WebRtcEngine(private val context: Context) {

    val eglBase: EglBase = EglBase.create()
    private val factory: PeerConnectionFactory
    private val iceServers = listOf(
        PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
    )

    // Master side
    private var screenCapturer: VideoCapturer? = null
    private var videoSource: VideoSource? = null
    private var localVideoTrack: VideoTrack? = null
    private var localAudioTrack: AudioTrack? = null
    private val peerConnections = ConcurrentHashMap<String, PeerConnection>()

    // Client side
    var remoteVideoTrack: VideoTrack? = null
        private set
    private var clientPc: PeerConnection? = null

    init {
        val encoderFactory = DefaultVideoEncoderFactory(
            eglBase.eglBaseContext, true, true
        )
        val decoderFactory = DefaultVideoDecoderFactory(eglBase.eglBaseContext)
        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .createPeerConnectionFactory()
    }

    // ─────────────────── MASTER ───────────────────

    fun startScreenCapture(resultCode: Int, data: Intent, width: Int, height: Int, fps: Int = 30) {
        val capturer = ScreenCapturerAndroid(data, object : MediaProjection.Callback() {
            override fun onStop() {
                Log.i(TAG, "MediaProjection stopped")
            }
        })
        screenCapturer = capturer

        val helper = SurfaceTextureHelper.create("CaptureThread", eglBase.eglBaseContext)
        videoSource = factory.createVideoSource(capturer.isScreencast)
        capturer.initialize(helper, context, videoSource!!.capturerObserver)
        capturer.startCapture(width, height, fps)

        localVideoTrack = factory.createVideoTrack("screen0", videoSource)
        localVideoTrack?.setEnabled(true)

        val audioConstraints = MediaConstraints()
        val audioSource = factory.createAudioSource(audioConstraints)
        localAudioTrack = factory.createAudioTrack("audio0", audioSource)
        localAudioTrack?.setEnabled(true)
    }

    fun createOfferForClient(
        clientId: String,
        onLocalSdp: (SessionDescription) -> Unit,
        onIce: (IceCandidate) -> Unit
    ) {
        val observer = object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                Log.d(TAG, "[$clientId] ICE connection: $state")
            }
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
            override fun onIceCandidate(candidate: IceCandidate?) {
                candidate?.let { onIce(it) }
            }
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
            override fun onAddStream(stream: MediaStream?) {}
            override fun onRemoveStream(stream: MediaStream?) {}
            override fun onDataChannel(dc: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {}
        }

        val pc = factory.createPeerConnection(
            PeerConnection.RTCConfiguration(iceServers).apply {
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            },
            observer
        ) ?: return

        peerConnections[clientId] = pc

        localVideoTrack?.let { pc.addTrack(it, listOf("stream0")) }
        localAudioTrack?.let { pc.addTrack(it, listOf("stream0")) }

        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
        }

        pc.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription?) {
                sdp ?: return
                pc.setLocalDescription(SimpleSdpObserver(), sdp)
                onLocalSdp(sdp)
            }
            override fun onSetSuccess() {}
            override fun onCreateFailure(error: String?) { Log.e(TAG, "createOffer failed: $error") }
            override fun onSetFailure(error: String?) {}
        }, constraints)
    }

    fun handleAnswer(clientId: String, sdp: SessionDescription) {
        peerConnections[clientId]?.setRemoteDescription(SimpleSdpObserver(), sdp)
    }

    fun addIceCandidate(clientId: String, candidate: IceCandidate) {
        peerConnections[clientId]?.addIceCandidate(candidate)
    }

    fun removeClient(clientId: String) {
        peerConnections.remove(clientId)?.close()
    }

    // ─────────────────── CLIENT ───────────────────

    fun createAnswerPeer(
        onRemoteTrack: (VideoTrack) -> Unit,
        onLocalSdp: (SessionDescription) -> Unit,
        onIce: (IceCandidate) -> Unit
    ) {
        val observer = object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                Log.d(TAG, "Client ICE: $state")
            }
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
            override fun onIceCandidate(candidate: IceCandidate?) {
                candidate?.let { onIce(it) }
            }
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
            override fun onAddStream(stream: MediaStream?) {}
            override fun onRemoveStream(stream: MediaStream?) {}
            override fun onDataChannel(dc: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
                val track = receiver?.track()
                if (track is VideoTrack) {
                    remoteVideoTrack = track
                    onRemoteTrack(track)
                }
            }
        }

        clientPc = factory.createPeerConnection(
            PeerConnection.RTCConfiguration(iceServers).apply {
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            },
            observer
        )
    }

    fun handleOffer(sdp: SessionDescription, onAnswer: (SessionDescription) -> Unit) {
        val pc = clientPc ?: return
        pc.setRemoteDescription(SimpleSdpObserver(), sdp)
        val constraints = MediaConstraints()
        pc.createAnswer(object : SdpObserver {
            override fun onCreateSuccess(answer: SessionDescription?) {
                answer ?: return
                pc.setLocalDescription(SimpleSdpObserver(), answer)
                onAnswer(answer)
            }
            override fun onSetSuccess() {}
            override fun onCreateFailure(error: String?) { Log.e(TAG, "createAnswer failed: $error") }
            override fun onSetFailure(error: String?) {}
        }, constraints)
    }

    fun addRemoteIce(candidate: IceCandidate) {
        clientPc?.addIceCandidate(candidate)
    }

    fun release() {
        peerConnections.values.forEach { it.close() }
        peerConnections.clear()
        clientPc?.close()
        clientPc = null
        screenCapturer?.stopCapture()
        screenCapturer?.dispose()
        screenCapturer = null
        videoSource?.dispose()
        localVideoTrack?.dispose()
        localAudioTrack?.dispose()
        factory.dispose()
        eglBase.release()
    }

    companion object {
        private const val TAG = "WebRtcEngine"
        fun newClientId() = UUID.randomUUID().toString().take(8)
    }
}

/** Minimal no-op SDP observer. */
class SimpleSdpObserver : SdpObserver {
    override fun onCreateSuccess(p0: SessionDescription?) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(p0: String?) {}
    override fun onSetFailure(p0: String?) {}
}
