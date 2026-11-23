package com.p2pchat.app.ui.chat

/** チャット画面が表示に使う状態。 */
data class ChatUiState(
    /** 相手の名前。連絡先が見つからない間は null（画面は既定のタイトルを出す）。 */
    val contactName: String? = null,
    val messages: List<ChatMessageUiModel> = emptyList(),
    /** 入力欄の文字列。 */
    val input: String = "",
    /** 本文が UTF-8 で 4096 バイトを超えている。 */
    val isInputTooLong: Boolean = false,
    /** 送信ボタンを押せるか（空白だけ・長すぎ・送信中は false）。 */
    val canSend: Boolean = false,
    val isSending: Boolean = false,
    /** 直近の送信が失敗した。入力を変えると消える。 */
    val sendFailed: Boolean = false,
    /** [sendFailed] のときの理由。画面は理由ごとの文言を出す。 */
    val sendError: ChatSendError = ChatSendError.Generic,
    /** 相手との P2P 接続の状態。トップバー下の帯に出す。 */
    val connection: ChatConnection = ChatConnection.Connecting,
)

/** 相手との接続の状態（表示用）。 */
enum class ChatConnection {
    Connecting,
    Connected,

    /** 切れた・失敗したあと、自動でつなぎ直している。 */
    Reconnecting,

    /** 接続が切れた（open だったものが閉じた）。 */
    Disconnected,
    PeerOffline,
    Rejected,
    NoResponse,
    SignalingUnavailable,

    /** ICE / SDP の失敗。 */
    Failed,
}

/** 送信失敗の理由（表示用）。 */
enum class ChatSendError {
    Generic,
    NoPublicKey,
    PeerOffline,
    Rejected,
    NoResponse,
    SignalingUnavailable,
    ConnectionFailed,
    Disconnected,
}

/** 吹き出し 1 つ分の情報。 */
data class ChatMessageUiModel(
    val id: String,
    val body: String,
    /** 自分が送ったものか（右寄せ）。false なら受信（左寄せ）。 */
    val isSent: Boolean,
    /** 吹き出しの「しっぽ」を付けるか。 */
    val showTail: Boolean,
)
