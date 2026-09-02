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
    const val GRID = "grid"
    const val ACTION_X = "action_x"
    const val SDP = "sdp"
    const val CANDIDATE = "candidate"
    const val SDP_MID = "sdpMid"
    const val SDP_MLINE = "sdpMLineIndex"
    const val CLIENT_ID = "clientId"
    const val INDEX = "index"
    const val COLUMNS = "columns"
    const val ROWS = "rows"
    const val T = "t"
    const val WALL = "wall"
    const val X = "x"
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

fun joinMsg(clientId: String, index: Int, columns: Int, rows: Int) = JSONObject().apply {
    put(Msg.TYPE, Msg.JOIN)
    put(Msg.CLIENT_ID, clientId)
    put(Msg.INDEX, index)
    put(Msg.COLUMNS, columns)
    put(Msg.ROWS, rows)
}

fun actionXMsg(x: Float) = JSONObject().apply {
    put(Msg.TYPE, Msg.ACTION_X)
    put(Msg.X, x.toDouble())
}
