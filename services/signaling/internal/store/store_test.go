package store

import (
	"context"
	"errors"
	"testing"
)

func TestDeviceLifecycle(t *testing.T) {
	ctx := context.Background()
	path := t.TempDir() + "/nested/test.db"

	s, err := Open(path)
	if err != nil {
		t.Fatal(err)
	}
	d, token, err := s.CreateDevice(ctx, "iPhone", "AQIDBA==")
	if err != nil {
		t.Fatal(err)
	}
	s.Close()

	// 再オープンしても残っている（永続化されている）
	s, err = Open(path)
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()

	got, err := s.GetDevice(ctx, d.ID)
	if err != nil || got.Name != "iPhone" || got.PublicKey != "AQIDBA==" {
		t.Fatalf("GetDevice = %+v, %v", got, err)
	}
	if id, err := s.AuthenticateDevice(ctx, token); err != nil || id != d.ID {
		t.Fatalf("AuthenticateDevice = %q, %v", id, err)
	}
	if _, err := s.AuthenticateDevice(ctx, "wrong"); !errors.Is(err, ErrNotFound) {
		t.Fatalf("wrong token err = %v", err)
	}
	if _, err := s.GetDevice(ctx, "missing"); !errors.Is(err, ErrNotFound) {
		t.Fatalf("missing err = %v", err)
	}
}
