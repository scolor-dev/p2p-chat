package signaling

import (
	"context"
	"errors"
	"log/slog"
	"net/http"
	"time"

	"github.com/coder/websocket"
	"github.com/coder/websocket/wsjson"

	"github.com/scolor-dev/p2p-chat/services/signaling/internal/auth"
)

const (
	sendBuffer   = 32
	readLimit    = 64 << 10 // SDP は数 KB 程度なので十分
	pingInterval = 20 * time.Second
	writeTimeout = 5 * time.Second
)

type client struct {
	id     string // 認証済みの端末 ID
	out    chan Message
	cancel context.CancelFunc
}

// send は送信キューに積む。詰まっているクライアントへのメッセージは捨てる。
func (c *client) send(m Message) {
	select {
	case c.out <- m:
	default:
		slog.Warn("send buffer full, dropping message", "device", c.id, "type", m.Type)
	}
}

// ServeWS は WebSocket 接続を受け付けるハンドラーを返す。
// auth.Require の内側で使い、接続は認証済みの端末 ID に結びつく。
func (h *Hub) ServeWS() http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		id := auth.DeviceID(r.Context())
		if id == "" {
			http.Error(w, "unauthorized", http.StatusUnauthorized)
			return
		}
		// ネイティブアプリは Origin を送らないため、オリジン検査は既定のままで問題ない。
		conn, err := websocket.Accept(w, r, nil)
		if err != nil {
			slog.Warn("websocket accept failed", "err", err)
			return
		}
		conn.SetReadLimit(readLimit)

		ctx, cancel := context.WithCancel(r.Context())
		defer cancel()
		c := &client{id: id, out: make(chan Message, sendBuffer), cancel: cancel}

		h.register(c)
		slog.Info("connected", "device", id, "remote", r.RemoteAddr)
		c.send(Message{Type: TypeReady, DeviceID: id})

		go c.writeLoop(ctx, conn)
		c.readLoop(ctx, conn, h)

		h.unregister(c)
		conn.CloseNow()
		slog.Info("disconnected", "device", id)
	}
}

func (c *client) readLoop(ctx context.Context, conn *websocket.Conn, h *Hub) {
	for {
		var m Message
		if err := wsjson.Read(ctx, conn, &m); err != nil {
			var ce websocket.CloseError
			if !errors.As(err, &ce) && ctx.Err() == nil {
				slog.Debug("read failed", "device", c.id, "err", err)
			}
			return
		}

		switch {
		case !isRelayType(m.Type):
			c.send(errorMessage(ErrBadMessage, "unknown type: "+m.Type, ""))
		case m.To == "":
			c.send(errorMessage(ErrBadMessage, "to is required", ""))
		default:
			h.relay(c, m)
		}
	}
}

func (c *client) writeLoop(ctx context.Context, conn *websocket.Conn) {
	defer c.cancel()
	ticker := time.NewTicker(pingInterval)
	defer ticker.Stop()

	for {
		select {
		case <-ctx.Done():
			return
		case m := <-c.out:
			wctx, wcancel := context.WithTimeout(ctx, writeTimeout)
			err := wsjson.Write(wctx, conn, m)
			wcancel()
			if err != nil {
				return
			}
		case <-ticker.C:
			// 応答のない接続（回線切り替えなど）を検出して切断する。
			pctx, pcancel := context.WithTimeout(ctx, writeTimeout)
			err := conn.Ping(pctx)
			pcancel()
			if err != nil {
				return
			}
		}
	}
}
