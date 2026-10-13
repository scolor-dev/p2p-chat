// Package store は SQLite による端末と公開鍵の永続化を担当する。
package store

import (
	"context"
	"crypto/rand"
	"crypto/sha256"
	"database/sql"
	"encoding/hex"
	"errors"
	"fmt"
	"net/url"
	"os"
	"path/filepath"
	"time"

	_ "modernc.org/sqlite"
)

var ErrNotFound = errors.New("not found")

const schema = `
CREATE TABLE IF NOT EXISTS devices (
	id          TEXT PRIMARY KEY,
	name        TEXT NOT NULL,
	public_key  TEXT NOT NULL,       -- base64
	token_hash  TEXT NOT NULL,       -- 認証トークンの SHA-256（平文は保存しない）
	created_at  INTEGER NOT NULL     -- UNIX 秒
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_devices_token ON devices(token_hash);
`

type Device struct {
	ID        string
	Name      string
	PublicKey string
	CreatedAt time.Time
}

type Store struct {
	db *sql.DB
}

// Open は path の SQLite ファイルを開き（なければ作り）、スキーマを適用する。
func Open(path string) (*Store, error) {
	if path != ":memory:" {
		if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
			return nil, err
		}
	}
	q := url.Values{}
	q.Add("_pragma", "busy_timeout(5000)")
	q.Add("_pragma", "journal_mode(WAL)")
	q.Add("_pragma", "foreign_keys(1)")
	db, err := sql.Open("sqlite", "file:"+path+"?"+q.Encode())
	if err != nil {
		return nil, err
	}
	// SQLite は書き込みが直列なので、接続を 1 本にして競合を避ける。
	db.SetMaxOpenConns(1)
	if _, err := db.Exec(schema); err != nil {
		db.Close()
		return nil, fmt.Errorf("apply schema: %w", err)
	}
	return &Store{db: db}, nil
}

func (s *Store) Close() error { return s.db.Close() }

// CreateDevice は端末を登録し、認証トークンを返す。トークンは再取得できない。
func (s *Store) CreateDevice(ctx context.Context, name, publicKey string) (Device, string, error) {
	id, token := randomHex(8), randomHex(32)
	d := Device{ID: id, Name: name, PublicKey: publicKey, CreatedAt: time.Now().Truncate(time.Second)}
	_, err := s.db.ExecContext(ctx,
		`INSERT INTO devices (id, name, public_key, token_hash, created_at) VALUES (?, ?, ?, ?, ?)`,
		d.ID, d.Name, d.PublicKey, hashToken(token), d.CreatedAt.Unix())
	if err != nil {
		return Device{}, "", err
	}
	return d, token, nil
}

func (s *Store) GetDevice(ctx context.Context, id string) (Device, error) {
	var d Device
	var created int64
	err := s.db.QueryRowContext(ctx,
		`SELECT id, name, public_key, created_at FROM devices WHERE id = ?`, id).
		Scan(&d.ID, &d.Name, &d.PublicKey, &created)
	if errors.Is(err, sql.ErrNoRows) {
		return Device{}, ErrNotFound
	}
	if err != nil {
		return Device{}, err
	}
	d.CreatedAt = time.Unix(created, 0)
	return d, nil
}

// AuthenticateDevice はトークンに対応する端末 ID を返す。WebSocket 認証で使う予定。
func (s *Store) AuthenticateDevice(ctx context.Context, token string) (string, error) {
	var id string
	err := s.db.QueryRowContext(ctx,
		`SELECT id FROM devices WHERE token_hash = ?`, hashToken(token)).Scan(&id)
	if errors.Is(err, sql.ErrNoRows) {
		return "", ErrNotFound
	}
	return id, err
}

func randomHex(n int) string {
	b := make([]byte, n)
	_, _ = rand.Read(b)
	return hex.EncodeToString(b)
}

func hashToken(token string) string {
	sum := sha256.Sum256([]byte(token))
	return hex.EncodeToString(sum[:])
}
