package device

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/scolor-dev/p2p-chat/services/signaling/internal/store"
)

func newMux(t *testing.T) *http.ServeMux {
	t.Helper()
	s, err := store.Open(t.TempDir() + "/test.db")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { s.Close() })
	h := Handler{Store: s}
	mux := http.NewServeMux()
	mux.HandleFunc("POST /devices", h.Register)
	mux.HandleFunc("GET /devices/{id}", h.Get)
	return mux
}

func do(mux http.Handler, method, path, body string) *httptest.ResponseRecorder {
	rec := httptest.NewRecorder()
	mux.ServeHTTP(rec, httptest.NewRequest(method, path, strings.NewReader(body)))
	return rec
}

func TestRegisterAndGet(t *testing.T) {
	mux := newMux(t)

	rec := do(mux, "POST", "/devices", `{"name":"Pixel","publicKey":"AQIDBA=="}`)
	if rec.Code != http.StatusCreated {
		t.Fatalf("register status = %d, body = %s", rec.Code, rec.Body)
	}
	var reg registerResponse
	if err := json.Unmarshal(rec.Body.Bytes(), &reg); err != nil || reg.DeviceID == "" || reg.Token == "" {
		t.Fatalf("register response = %s", rec.Body)
	}

	rec = do(mux, "GET", "/devices/"+reg.DeviceID, "")
	if rec.Code != http.StatusOK {
		t.Fatalf("get status = %d", rec.Code)
	}
	var got deviceResponse
	_ = json.Unmarshal(rec.Body.Bytes(), &got)
	if got.Name != "Pixel" || got.PublicKey != "AQIDBA==" || got.DeviceID != reg.DeviceID {
		t.Fatalf("get response = %+v", got)
	}
	if strings.Contains(rec.Body.String(), reg.Token) {
		t.Fatal("token must not be returned by GET")
	}
}

func TestRegisterValidation(t *testing.T) {
	mux := newMux(t)
	for name, body := range map[string]string{
		"invalid json":  `{`,
		"empty name":    `{"name":" ","publicKey":"AQIDBA=="}`,
		"bad base64":    `{"name":"a","publicKey":"!!!"}`,
		"empty key":     `{"name":"a","publicKey":""}`,
		"key too large": `{"name":"a","publicKey":"` + strings.Repeat("QUFB", 100) + `"}`,
	} {
		if rec := do(mux, "POST", "/devices", body); rec.Code != http.StatusBadRequest {
			t.Errorf("%s: status = %d, want 400", name, rec.Code)
		}
	}
}

func TestGetNotFound(t *testing.T) {
	if rec := do(newMux(t), "GET", "/devices/nope", ""); rec.Code != http.StatusNotFound {
		t.Fatalf("status = %d, want 404", rec.Code)
	}
}
