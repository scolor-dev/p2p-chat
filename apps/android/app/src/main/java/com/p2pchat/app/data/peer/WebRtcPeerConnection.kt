package com.p2pchat.app.data.peer

import android.content.Context
import com.p2pchat.app.data.signaling.CandidatePayload
import kotlinx.coroutines.suspendCancellableCoroutine
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import java.nio.ByteBuffer
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * libwebrtc（stream-webrtc-android）で [RtcPeerConnection] を作る。端末の中でしか動かせないので、ユニットテストの対象外。
 * 状態遷移や offer / answer の取り回しは [DefaultPeerSession] にあり、ここは libwebrtc の呼び出しを包むだけ。
 *
 * TURN は使わない（MVP）。STUN は [stunUrls]（既定は Google の公開 STUN）。
 */
class WebRtcPeerConnectionFactory(
    context: Context,
    private val stunUrls: List<String> = listOf("stun:stun.l.google.com:19302"),
) : RtcPeerConnectionFactory {
    private val appContext = context.applicationContext

    /** ネイティブのライブラリの読み込みを伴うので、最初の接続まで作らない。 */
    private val factory: PeerConnectionFactory by lazy {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(appContext).createInitializationOptions(),
        )
        PeerConnectionFactory.builder().createPeerConnectionFactory()
    }

    override fun create(listener: RtcListener): RtcPeerConnection {
        val iceServers = stunUrls.map { PeerConnection.IceServer.builder(it).createIceServer() }
        val config = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }
        return WebRtcPeerConnection(factory, config, listener)
    }
}

internal class WebRtcPeerConnection(
    factory: PeerConnectionFactory,
    config: PeerConnection.RTCConfiguration,
    private val listener: RtcListener,
) : RtcPeerConnection {
    @Volatile private var channel: DataChannel? = null

    private val peerConnection: PeerConnection = factory.createPeerConnection(
        config,
        object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
                listener.onLocalCandidate(CandidatePayload(candidate.sdp, candidate.sdpMid, candidate.sdpMLineIndex))
            }

            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
                listener.onConnectionState(
                    when (newState) {
                        PeerConnection.PeerConnectionState.CONNECTED -> RtcConnectionState.Connected
                        PeerConnection.PeerConnectionState.FAILED -> RtcConnectionState.Failed
                        PeerConnection.PeerConnectionState.CLOSED -> RtcConnectionState.Closed
                        else -> RtcConnectionState.Other
                    },
                )
            }

            override fun onDataChannel(dataChannel: DataChannel) {
                if (dataChannel.label() == DATA_CHANNEL_LABEL) attach(dataChannel)
            }

            override fun onSignalingChange(newState: PeerConnection.SignalingState) = Unit
            override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState) = Unit
            override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
            override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState) = Unit
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit
            override fun onAddStream(stream: MediaStream) = Unit
            override fun onRemoveStream(stream: MediaStream) = Unit
            override fun onRenegotiationNeeded() = Unit
            override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) = Unit
        },
    ) ?: error("PeerConnection を作れませんでした")

    private fun attach(dataChannel: DataChannel) {
        channel = dataChannel
        dataChannel.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) = Unit

            override fun onStateChange() {
                when (dataChannel.state()) {
                    DataChannel.State.OPEN -> listener.onChannelOpen()
                    DataChannel.State.CLOSED -> listener.onChannelClosed()
                    else -> Unit
                }
            }

            override fun onMessage(buffer: DataChannel.Buffer) {
                if (buffer.binary) return // 文字列フレームだけ扱う
                val bytes = ByteArray(buffer.data.remaining())
                buffer.data.get(bytes)
                listener.onMessage(String(bytes, Charsets.UTF_8))
            }
        })
        // 登録の前にすでに open になっていた場合
        if (dataChannel.state() == DataChannel.State.OPEN) listener.onChannelOpen()
    }

    override suspend fun createOffer(): String {
        // ordered = true、再送の上限なし（信頼性あり）が既定
        attach(peerConnection.createDataChannel(DATA_CHANNEL_LABEL, DataChannel.Init()))
        val offer = suspendSdp { peerConnection.createOffer(it, MediaConstraints()) }
        setLocal(offer)
        return offer.description
    }

    override suspend fun acceptOffer(sdp: String): String {
        setRemote(SessionDescription(SessionDescription.Type.OFFER, sdp))
        val answer = suspendSdp { peerConnection.createAnswer(it, MediaConstraints()) }
        setLocal(answer)
        return answer.description
    }

    override suspend fun acceptAnswer(sdp: String) {
        setRemote(SessionDescription(SessionDescription.Type.ANSWER, sdp))
    }

    override suspend fun addRemoteCandidate(candidate: CandidatePayload) {
        val ok = peerConnection.addIceCandidate(
            IceCandidate(candidate.sdpMid, candidate.sdpMLineIndex, candidate.candidate),
        )
        check(ok) { "candidate を追加できませんでした" }
    }

    override fun send(text: String): Boolean {
        val dc = channel ?: return false
        if (dc.state() != DataChannel.State.OPEN) return false
        return dc.send(DataChannel.Buffer(ByteBuffer.wrap(text.toByteArray(Charsets.UTF_8)), false))
    }

    override fun close() {
        runCatching { channel?.close() }
        runCatching { peerConnection.close() }
        runCatching { peerConnection.dispose() }
    }

    private suspend fun setLocal(description: SessionDescription) =
        suspendUnit { peerConnection.setLocalDescription(it, description) }

    private suspend fun setRemote(description: SessionDescription) =
        suspendUnit { peerConnection.setRemoteDescription(it, description) }

    private suspend fun suspendSdp(start: (SdpObserver) -> Unit): SessionDescription =
        suspendCancellableCoroutine { cont ->
            start(object : SdpObserver {
                override fun onCreateSuccess(description: SessionDescription) = cont.resume(description)
                override fun onCreateFailure(error: String?) =
                    cont.resumeWithException(IllegalStateException("SDP の作成に失敗しました"))
                override fun onSetSuccess() = Unit
                override fun onSetFailure(error: String?) = Unit
            })
        }

    private suspend fun suspendUnit(start: (SdpObserver) -> Unit) =
        suspendCancellableCoroutine { cont ->
            start(object : SdpObserver {
                override fun onSetSuccess() = cont.resume(Unit)
                override fun onSetFailure(error: String?) =
                    cont.resumeWithException(IllegalStateException("SDP の設定に失敗しました"))
                override fun onCreateSuccess(description: SessionDescription) = Unit
                override fun onCreateFailure(error: String?) = Unit
            })
        }
}
