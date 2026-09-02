package com.videowall.webrtc

import org.json.JSONObject

object Msg {
    const val TYPE = "type"
    const val OFFER = "offer"
    const val ANSWER = "answer"
    const val ICE = "ice"
    const val TIME = "time"
    const val TIME_REQ = "time_req"
    const val JOIN = "join"
    const val LEAVE = "leave"
    const val ASSIGN = "assign"
    const val BRIGHTNESS = "brightness"
    const val COLOR_SYNC = "color_sync"
    const val AUDIO_MUTE = "audio_mute"
    const val WELCOME = "welcome"
    const val SDP = "sdp"
    const val CANDIDATE = "candidate"
    const val SDP_MID = "sdpMid"
    const val SDP_MLINE = "sdpMLineIndex"
    const val CLIENT_ID = "clientId"
    const val PHONE = "phone"
    const val SESSION = "session"
    const val COL = "col"
    const val ROW = "row"
    const val COLUMNS = "columns"
    const val ROWS = "rows"
    const val VALUE = "value"
    const val ENABLED = "enabled"
    const val T = "t"
    const val WALL = "wall"
}

fun JSONObject.type(): String = optString(Msg.TYPE, "")

fun offerMsg(sdp: String, clientId: String) = JSONObject().apply {
    put(Msg.TYPE, Msg.OFFER)
    put(Msg.SDP, sdp)
    put(Msg.CLIENT_ID, clientId)
}

fun answerMsg(sdp: String, clientId: String) = JSONObject().apply {
    put(Msg.TYPE, Msg.ANSWER)
    put(Msg.SDP, sdp)
    put(Msg.CLIENT_ID, clientId)
}

fun iceMsg(candidate: String, sdpMid: String?, sdpMLineIndex: Int, clientId: String) = JSONObject().apply {
    put(Msg.TYPE, Msg.ICE)
    put(Msg.CANDIDATE, candidate)
    put(Msg.SDP_MID, sdpMid)
    put(Msg.SDP_MLINE, sdpMLineIndex)
    put(Msg.CLIENT_ID, clientId)
}

fun timeMsg(monoNs: Long, wallMs: Long) = JSONObject().apply {
    put(Msg.TYPE, Msg.TIME)
    put(Msg.T, monoNs)
    put(Msg.WALL, wallMs)
}

fun timeReqMsg() = JSONObject().apply { put(Msg.TYPE, Msg.TIME_REQ) }

fun joinMsg(clientId: String, session: String) = JSONObject().apply {
    put(Msg.TYPE, Msg.JOIN)
    put(Msg.CLIENT_ID, clientId)
    put(Msg.SESSION, session)
}

fun welcomeMsg(clientId: String, phone: Int) = JSONObject().apply {
    put(Msg.TYPE, Msg.WELCOME)
    put(Msg.CLIENT_ID, clientId)
    put(Msg.PHONE, phone)
}

fun assignMsg(clientId: String, col: Int, row: Int, columns: Int, rows: Int) = JSONObject().apply {
    put(Msg.TYPE, Msg.ASSIGN)
    put(Msg.CLIENT_ID, clientId)
    put(Msg.COL, col)
    put(Msg.ROW, row)
    put(Msg.COLUMNS, columns)
    put(Msg.ROWS, rows)
}

fun brightnessMsg(clientId: String, value: Float) = JSONObject().apply {
    put(Msg.TYPE, Msg.BRIGHTNESS)
    put(Msg.CLIENT_ID, clientId)
    put(Msg.VALUE, value.toDouble())
}

fun colorSyncMsg(enabled: Boolean) = JSONObject().apply {
    put(Msg.TYPE, Msg.COLOR_SYNC)
    put(Msg.ENABLED, enabled)
}

fun audioMuteMsg(clientId: String, muted: Boolean) = JSONObject().apply {
    put(Msg.TYPE, Msg.AUDIO_MUTE)
    put(Msg.CLIENT_ID, clientId)
    put(Msg.ENABLED, muted)
}
