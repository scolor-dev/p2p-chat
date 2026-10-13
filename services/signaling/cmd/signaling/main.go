package main

import (
	"context"
	"errors"
	"log/slog"
	"net"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/scolor-dev/p2p-chat/services/signaling/internal/auth"
	"github.com/scolor-dev/p2p-chat/services/signaling/internal/device"
	"github.com/scolor-dev/p2p-chat/services/signaling/internal/signaling"
	"github.com/scolor-dev/p2p-chat/services/signaling/internal/store"
)

func main() {
	addr := env("SIGNALING_ADDR", ":8080")

	db, err := store.Open(env("DB_PATH", "data/p2p-chat.db"))
	if err != nil {
		slog.Error("open database failed", "err", err)
		os.Exit(1)
	}
	defer db.Close()

	hub := signaling.NewHub()
	devices := device.Handler{Store: db}

	mux := http.NewServeMux()
	mux.HandleFunc("GET /healthz", func(w http.ResponseWriter, _ *http.Request) {
		_, _ = w.Write([]byte("ok"))
	})
	mux.HandleFunc("POST /devices", devices.Register)
	mux.HandleFunc("GET /devices/{id}", devices.Get)
	mux.HandleFunc("GET /ws", auth.Require(db, hub.ServeWS()))

	srv := &http.Server{Addr: addr, Handler: mux, ReadHeaderTimeout: 10 * time.Second}

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	go func() {
		<-ctx.Done()
		shutdownCtx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		_ = srv.Shutdown(shutdownCtx)
	}()

	_, port, _ := net.SplitHostPort(addr)
	lanIP := detectLANIP()
	slog.Info("signaling server started", "http", "http://"+lanIP+":"+port, "ws", "ws://"+lanIP+":"+port+"/ws")
	if err := srv.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
		slog.Error("server stopped", "err", err)
		db.Close()
		os.Exit(1)
	}
}

// detectLANIP はスマホから到達できそうなプライベート IPv4 アドレスを返す（起動ログ用）。
func detectLANIP() string {
	ifaces, err := net.Interfaces()
	if err != nil {
		return "127.0.0.1"
	}
	for _, iface := range ifaces {
		if iface.Flags&net.FlagUp == 0 || iface.Flags&net.FlagLoopback != 0 {
			continue
		}
		addrs, _ := iface.Addrs()
		for _, a := range addrs {
			ipnet, ok := a.(*net.IPNet)
			if !ok {
				continue
			}
			if ip := ipnet.IP.To4(); ip != nil && ip.IsPrivate() {
				return ip.String()
			}
		}
	}
	return "127.0.0.1"
}

func env(key, def string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return def
}
