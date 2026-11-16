package com.p2pchat.app.data.signaling

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class SignalingCodecTest {

    private fun parse(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    @Test
    fun readyを読める() {
        assertEquals(
            IncomingSignal.Ready("a1b2c3d4e5f60718"),
            SignalingCodec.decode("""{"type":"ready","deviceId":"a1b2c3d4e5f60718"}"""),
        )
    }

    @Test
    fun 相手からのofferとanswerとcandidateとrejectを読める() {
        assertEquals(
            IncomingSignal.Offer("peer", SdpPayload("v=0\r\n")),
            SignalingCodec.decode("""{"type":"offer","from":"peer","payload":{"sdp":"v=0\r\n"}}"""),
        )
        assertEquals(
            IncomingSignal.Answer("peer", SdpPayload("v=0")),
            SignalingCodec.decode("""{"type":"answer","from":"peer","payload":{"sdp":"v=0"}}"""),
        )
        assertEquals(
            IncomingSignal.Candidate("peer", CandidatePayload("candidate:1 1 udp", "0", 0)),
            SignalingCodec.decode(
                """{"type":"candidate","from":"peer","payload":{"candidate":"candidate:1 1 udp","sdpMid":"0","sdpMLineIndex":0}}""",
            ),
        )
        assertEquals(IncomingSignal.Reject("peer"), SignalingCodec.decode("""{"type":"reject","from":"peer"}"""))
    }

    @Test
    fun errorを読めてtoは省略できる() {
        assertEquals(
            IncomingSignal.ServerError(ErrorCodes.PEER_OFFLINE, "peer is offline", "peer"),
            SignalingCodec.decode("""{"type":"error","code":"peer-offline","message":"peer is offline","to":"peer"}"""),
        )
        assertEquals(
            IncomingSignal.ServerError(ErrorCodes.BAD_MESSAGE, "unknown type"),
            SignalingCodec.decode("""{"type":"error","code":"bad-message","message":"unknown type"}"""),
        )
    }

    @Test
    fun 知らない項目は無視して読める() {
        assertEquals(
            IncomingSignal.Ready("dev"),
            SignalingCodec.decode("""{"type":"ready","deviceId":"dev","extra":1}"""),
        )
    }

    @Test
    fun 知らないtypeや壊れたJSONはnull() {
        assertNull(SignalingCodec.decode("""{"type":"hello"}"""))
        assertNull(SignalingCodec.decode("""{"deviceId":"dev"}"""))
        assertNull(SignalingCodec.decode("""{"type":"offer","from":"peer"}"""))
        assertNull(SignalingCodec.decode("not json"))
    }

    @Test
    fun 送るメッセージは設計書の形式になる() {
        assertEquals(
            parse("""{"type":"offer","to":"peer","payload":{"sdp":"v=0"}}"""),
            parse(SignalingCodec.encode(OutgoingSignal.Offer("peer", SdpPayload("v=0")))),
        )
        assertEquals(
            parse("""{"type":"answer","to":"peer","payload":{"sdp":"v=0"}}"""),
            parse(SignalingCodec.encode(OutgoingSignal.Answer("peer", SdpPayload("v=0")))),
        )
        assertEquals(
            parse("""{"type":"candidate","to":"peer","payload":{"candidate":"c","sdpMid":"0","sdpMLineIndex":1}}"""),
            parse(SignalingCodec.encode(OutgoingSignal.Candidate("peer", CandidatePayload("c", "0", 1)))),
        )
        assertEquals(
            parse("""{"type":"reject","to":"peer"}"""),
            parse(SignalingCodec.encode(OutgoingSignal.Reject("peer"))),
        )
    }

    @Test
    fun sdpMidがnullなら項目ごと送らない() {
        val json = parse(SignalingCodec.encode(OutgoingSignal.Candidate("peer", CandidatePayload("c", null, 0))))
        assertFalse("sdpMid" in json.getValue("payload").jsonObject)
    }
}
