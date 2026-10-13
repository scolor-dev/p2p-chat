package signaling

import "sync"

// Hub は接続中の端末を端末 ID で管理し、端末間でメッセージを取り次ぐ。
type Hub struct {
	mu      sync.Mutex
	clients map[string]*client // deviceID -> client
}

func NewHub() *Hub {
	return &Hub{clients: make(map[string]*client)}
}

// register は c を登録する。同じ端末の古い接続があれば切断する（回線切り替え後の再接続など）。
func (h *Hub) register(c *client) {
	h.mu.Lock()
	defer h.mu.Unlock()
	if old := h.clients[c.id]; old != nil {
		old.cancel()
	}
	h.clients[c.id] = c
}

func (h *Hub) unregister(c *client) {
	h.mu.Lock()
	defer h.mu.Unlock()
	if h.clients[c.id] == c { // 新しい接続に置き換え済みなら何もしない
		delete(h.clients, c.id)
	}
}

// relay は offer / answer / candidate を宛先の端末へ転送する。
// 宛先がオンラインでなければ送信元に peer-offline を返す（クライアントはメールボックスに切り替える）。
func (h *Hub) relay(from *client, m Message) {
	if m.To == from.id {
		from.send(errorMessage(ErrBadMessage, "cannot send to self", m.To))
		return
	}
	h.mu.Lock()
	to, ok := h.clients[m.To]
	h.mu.Unlock()
	if !ok {
		from.send(errorMessage(ErrPeerOffline, "peer is offline: "+m.To, m.To))
		return
	}
	to.send(Message{Type: m.Type, From: from.id, Payload: m.Payload})
}
