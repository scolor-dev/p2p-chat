package signaling

import "encoding/json"

// メッセージ種別。仕様は docs/api.md を参照。
const (
	// クライアント ⇔ クライアント（サーバーは宛先の端末へ取り次ぐだけ）
	TypeOffer     = "offer"
	TypeAnswer    = "answer"
	TypeCandidate = "candidate"
	TypeReject    = "reject" // 接続の申請を拒否した

	// サーバー → クライアント
	TypeReady = "ready" // 接続と認証が完了した
	TypeError = "error"
)

// エラーコード
const (
	ErrBadMessage  = "bad-message"
	ErrPeerOffline = "peer-offline"
)

// Message はシグナリングで送受信する JSON メッセージ。
// 種別ごとに使うフィールドだけが設定される。端末 ID が宛先・送信元の識別子になる。
type Message struct {
	Type     string          `json:"type"`
	DeviceID string          `json:"deviceId,omitempty"` // ready: 自分の端末 ID
	To       string          `json:"to,omitempty"`
	From     string          `json:"from,omitempty"`
	Payload  json.RawMessage `json:"payload,omitempty"`
	Code     string          `json:"code,omitempty"`
	Message  string          `json:"message,omitempty"`
}

// errorMessage の to には、失敗した取り次ぎの宛先を入れる（クライアントが対応づけるため）。
func errorMessage(code, msg, to string) Message {
	return Message{Type: TypeError, Code: code, Message: msg, To: to}
}

func isRelayType(t string) bool {
	return t == TypeOffer || t == TypeAnswer || t == TypeCandidate || t == TypeReject
}
