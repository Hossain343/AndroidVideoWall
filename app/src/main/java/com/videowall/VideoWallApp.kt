package com.videowall

import android.app.Application
import android.util.Log
import org.webrtc.PeerConnectionFactory

class VideoWallApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Initialize WebRTC once for the process
        val options = PeerConnectionFactory.InitializationOptions.builder(this)
            .setEnableInternalTracer(false)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(options)
        Log.i(TAG, "WebRTC initialized")
    }

    companion object {
        private const val TAG = "VideoWallApp"
    }
}
