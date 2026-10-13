// Package device は端末登録と公開鍵配布の HTTP ハンドラーを提供する。
package device

import (
	"encoding/base64"
	"encoding/json"
	"errors"
	"log/slog"
	"net/http"
	"strings"
	"time"
	"unicode/utf8"

	"github.com/scolor-dev/p2p-chat/services/signaling/internal/httpx"
	"github.com/scolor-dev/p2p-chat/services/signaling/internal/store"
)

const (
	maxBodyBytes     = 4 << 10
	maxNameRunes     = 64
	maxPublicKeySize = 256 // デコード後のバイト数。鍵の種類は未確定なので緩めに制限
)

type Handler struct {
	Store *store.Store
}

type registerRequest struct {
	Name      string `json:"name"`
	PublicKey string `json:"publicKey"`
}

type registerResponse struct {
	DeviceID string `json:"deviceId"`
	Token    string `json:"token"`
}

type deviceResponse struct {
	DeviceID  string `json:"deviceId"`
	Name      string `json:"name"`
	PublicKey string `json:"publicKey"`
	CreatedAt string `json:"createdAt"`
}

// Register は POST /devices を処理する。
func (h Handler) Register(w http.ResponseWriter, r *http.Request) {
	var req registerRequest
	dec := json.NewDecoder(http.MaxBytesReader(w, r.Body, maxBodyBytes))
	if err := dec.Decode(&req); err != nil {
		httpx.Error(w, http.StatusBadRequest, "bad-request", "invalid JSON body")
		return
	}
	req.Name = strings.TrimSpace(req.Name)
	if req.Name == "" || utf8.RuneCountInString(req.Name) > maxNameRunes {
		httpx.Error(w, http.StatusBadRequest, "bad-request", "name must be 1-64 characters")
		return
	}
	key, err := base64.StdEncoding.DecodeString(req.PublicKey)
	if err != nil || len(key) == 0 || len(key) > maxPublicKeySize {
		httpx.Error(w, http.StatusBadRequest, "bad-request", "publicKey must be base64 (1-256 bytes)")
		return
	}

	d, token, err := h.Store.CreateDevice(r.Context(), req.Name, req.PublicKey)
	if err != nil {
		slog.Error("create device failed", "err", err)
		httpx.Error(w, http.StatusInternalServerError, "internal", "failed to register device")
		return
	}
	httpx.JSON(w, http.StatusCreated, registerResponse{DeviceID: d.ID, Token: token})
}

// Get は GET /devices/{id} を処理する。
func (h Handler) Get(w http.ResponseWriter, r *http.Request) {
	d, err := h.Store.GetDevice(r.Context(), r.PathValue("id"))
	if errors.Is(err, store.ErrNotFound) {
		httpx.Error(w, http.StatusNotFound, "not-found", "device not found")
		return
	}
	if err != nil {
		slog.Error("get device failed", "err", err)
		httpx.Error(w, http.StatusInternalServerError, "internal", "failed to get device")
		return
	}
	httpx.JSON(w, http.StatusOK, deviceResponse{
		DeviceID:  d.ID,
		Name:      d.Name,
		PublicKey: d.PublicKey,
		CreatedAt: d.CreatedAt.UTC().Format(time.RFC3339),
	})
}
