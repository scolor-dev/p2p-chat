package signaling

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/coder/websocket"
	"github.com/coder/websocket/wsjson"

	"github.com/scolor-dev/p2p-chat/services/signaling/internal/auth"
	"github.com/scolor-dev/p2p-chat/services/signaling/internal/store"
)

type fakeAuth map[string]string // token -> deviceID

func (f fakeAuth) AuthenticateDevice(_ context.Context, token string) (string, error) {
	if id, ok := f[token]; ok {
		return id, nil
	}
	return "", store.ErrNotFound
}

func newServer(t *testing.T) *httptest.Server {
	t.Helper()
	hub := NewHub()
	srv := httptest.NewServer(auth.Require(fakeAuth{"tokA": "A", "tokB": "B"}, hub.ServeWS()))
	t.Cleanup(srv.Close)
	return srv
}

func dial(t *testing.T, srv *httptest.Server, token string) *websocket.Conn {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	conn, _, err := websocket.Dial(ctx, "ws"+strings.TrimPrefix(srv.URL, "http"), &websocket.DialOptions{
		HTTPHeader: http.Header{"Authorization": {"Bearer " + token}},
	})
	if err != nil {
		t.Fatalf("dial: %v", err)
	}
	t.Cleanup(func() { conn.CloseNow() })
	return conn
}

// connect は接続して ready を受け取るところまで進める。
func connect(t *testing.T, srv *httptest.Server, token, wantID string) *websocket.Conn {
	t.Helper()
	c := dial(t, srv, token)
	if m := read(t, c); m.Type != TypeReady || m.DeviceID != wantID {
		t.Fatalf("got %+v, want ready for %s", m, wantID)
	}
	return c
}

func write(t *testing.T, c *websocket.Conn, m Message) {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	if err := wsjson.Write(ctx, c, m); err != nil {
		t.Fatalf("write: %v", err)
	}
}

func read(t *testing.T, c *websocket.Conn) Message {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	var m Message
	if err := wsjson.Read(ctx, c, &m); err != nil {
		t.Fatalf("read: %v", err)
	}
	return m
}

func TestRelay(t *testing.T) {
	srv := newServer(t)
	a := connect(t, srv, "tokA", "A")
	b := connect(t, srv, "tokB", "B")

	payload := json.RawMessage(`{"sdp":"v=0..."}`)
	write(t, a, Message{Type: TypeOffer, To: "B", Payload: payload})
	got := read(t, b)
	if got.Type != TypeOffer || got.From != "A" || string(got.Payload) != string(payload) {
		t.Fatalf("b got %+v", got)
	}

	write(t, b, Message{Type: TypeAnswer, To: "A", Payload: payload})
	if got := read(t, a); got.Type != TypeAnswer || got.From != "B" {
		t.Fatalf("a got %+v", got)
	}

	write(t, a, Message{Type: TypeCandidate, To: "B", Payload: json.RawMessage(`{"candidate":"x"}`)})
	if got := read(t, b); got.Type != TypeCandidate || got.From != "A" {
		t.Fatalf("b got %+v", got)
	}

	// reject は payload なしで取り次がれる
	write(t, b, Message{Type: TypeReject, To: "A"})
	if got := read(t, a); got.Type != TypeReject || got.From != "B" || got.Payload != nil {
		t.Fatalf("a got %+v", got)
	}
}

func TestPeerOffline(t *testing.T) {
	srv := newServer(t)
	a := connect(t, srv, "tokA", "A")

	write(t, a, Message{Type: TypeOffer, To: "B", Payload: json.RawMessage(`{}`)})
	if m := read(t, a); m.Type != TypeError || m.Code != ErrPeerOffline || m.To != "B" {
		t.Fatalf("got %+v, want peer-offline for B", m)
	}
}

func TestBadMessages(t *testing.T) {
	srv := newServer(t)
	a := connect(t, srv, "tokA", "A")

	for name, m := range map[string]Message{
		"unknown type": {Type: "join"},
		"missing to":   {Type: TypeOffer},
		"send to self": {Type: TypeOffer, To: "A"},
	} {
		write(t, a, m)
		if got := read(t, a); got.Type != TypeError || got.Code != ErrBadMessage {
			t.Errorf("%s: got %+v, want bad-message", name, got)
		}
	}
}

func TestReconnectReplacesOldConnection(t *testing.T) {
	srv := newServer(t)
	old := connect(t, srv, "tokA", "A")
	b := connect(t, srv, "tokB", "B")
	fresh := connect(t, srv, "tokA", "A")

	// 古い接続は切断される
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	var m Message
	if err := wsjson.Read(ctx, old, &m); err == nil {
		t.Fatalf("old connection still alive, got %+v", m)
	}

	// 古い接続の後片付けが、新しい接続の登録を消さない
	write(t, b, Message{Type: TypeOffer, To: "A", Payload: json.RawMessage(`{}`)})
	if got := read(t, fresh); got.Type != TypeOffer || got.From != "B" {
		t.Fatalf("fresh got %+v", got)
	}
}

func TestUnauthenticatedRejected(t *testing.T) {
	srv := newServer(t)
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	for name, opts := range map[string]*websocket.DialOptions{
		"no header": nil,
		"bad token": {HTTPHeader: http.Header{"Authorization": {"Bearer nope"}}},
	} {
		_, resp, err := websocket.Dial(ctx, "ws"+strings.TrimPrefix(srv.URL, "http"), opts)
		if err == nil || resp == nil || resp.StatusCode != http.StatusUnauthorized {
			t.Errorf("%s: err = %v, resp = %v, want 401", name, err, resp)
		}
	}
}
