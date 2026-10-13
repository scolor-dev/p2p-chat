// Package auth は端末トークン（Authorization: Bearer）による認証を提供する。
package auth

import (
	"context"
	"errors"
	"log/slog"
	"net/http"
	"strings"

	"github.com/scolor-dev/p2p-chat/services/signaling/internal/httpx"
	"github.com/scolor-dev/p2p-chat/services/signaling/internal/store"
)

type Authenticator interface {
	AuthenticateDevice(ctx context.Context, token string) (deviceID string, err error)
}

type ctxKey struct{}

// DeviceID は Require を通過したリクエストの端末 ID を返す。
func DeviceID(ctx context.Context) string {
	id, _ := ctx.Value(ctxKey{}).(string)
	return id
}

// Require はトークンを検証し、端末 ID をコンテキストに入れて next を呼ぶ。
func Require(a Authenticator, next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		token, ok := strings.CutPrefix(r.Header.Get("Authorization"), "Bearer ")
		if !ok || token == "" {
			httpx.Error(w, http.StatusUnauthorized, "unauthorized", "Authorization: Bearer <token> is required")
			return
		}
		id, err := a.AuthenticateDevice(r.Context(), token)
		if errors.Is(err, store.ErrNotFound) {
			httpx.Error(w, http.StatusUnauthorized, "unauthorized", "invalid token")
			return
		}
		if err != nil {
			slog.Error("authenticate failed", "err", err)
			httpx.Error(w, http.StatusInternalServerError, "internal", "authentication failed")
			return
		}
		next(w, r.WithContext(context.WithValue(r.Context(), ctxKey{}, id)))
	}
}
