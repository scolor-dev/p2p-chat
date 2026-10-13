// Package httpx は HTTP ハンドラー共通の JSON 応答ヘルパーを提供する。
package httpx

import (
	"encoding/json"
	"net/http"
)

func JSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v)
}

// Error は {"code": ..., "message": ...} 形式のエラーを返す。
func Error(w http.ResponseWriter, status int, code, msg string) {
	JSON(w, status, map[string]string{"code": code, "message": msg})
}
