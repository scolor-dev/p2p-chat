# signaling（シグナリングサーバー）

場所: `services/signaling/`（Go）

## 概要
アプリ同士が P2P でつながるための「仲介役」。端末 ID を指定されて、2 台の間で SDP、ICE candidate、接続の拒否（reject）を取り次ぐ。
あわせて、端末の登録と公開鍵の配布を行う（QR で読んだ相手の鍵の取得先）。

チャットの本文は P2P で直接やりとりし、サーバーは通さない。連絡先・メッセージ・履歴は、すべてアプリ側（端末内）が持つ。

## 責務
| やること | やらないこと |
|---|---|
| 端末の登録、公開鍵の保存と配布（SQLite） | 秘密鍵の保管（端末の Keystore のみ） |
| トークンによる認証。WebSocket の接続を端末 ID に結びつける | 連絡先・友達関係の管理 |
| offer / answer / candidate / reject の取り次ぎ（中身は見ない） | チャット本文のデータ中継・保存 |
| 宛先がオフラインのときの `peer-offline` の通知 | メッセージの預かり、プッシュ通知 |

設計と API の仕様は [../design.md](../design.md) を参照。

## ファイル構造と責務
```text
services/signaling/
├── cmd/signaling/
│   └── main.go          # エントリーポイント。環境変数、依存の組み立て、ルーティング、終了処理
├── internal/
│   ├── signaling/       # WebSocket による取り次ぎ
│   │   ├── message.go   #   メッセージ型・種別・エラーコードの定義（design.md の API 仕様と対応）
│   │   ├── hub.go       #   接続中の端末の管理（端末 ID → 接続）、取り次ぎ
│   │   ├── client.go    #   1 接続分の読み書きループ、ping による死活監視
│   │   └── hub_test.go
│   ├── device/          # 端末登録と公開鍵配布の HTTP ハンドラー
│   │   ├── handler.go   #   POST /devices、GET /devices/{id}、入力検証
│   │   └── handler_test.go
│   ├── auth/
│   │   └── auth.go      # Authorization: Bearer の検証。端末 ID をリクエストのコンテキストに入れる
│   ├── store/           # SQLite による永続化
│   │   ├── store.go     #   接続、スキーマ、端末の作成・取得、トークン検証
│   │   └── store_test.go
│   └── httpx/
│       └── httpx.go     # JSON 応答とエラー応答の共通ヘルパー
├── go.mod
└── go.sum
```

### 依存の向き
```text
main ──▶ auth ──▶ store ──▶ SQLite
     ├─▶ signaling (hub, client) ──▶ auth
     └─▶ device ──▶ store
```

## データ（SQLite）
```text
devices   id, name, public_key, token_hash, created_at
```
どの端末が WebSocket に接続中かは、メモリ上にあり、DB には入れない。

## 設定（環境変数）
| 変数 | 既定値 | 説明 |
|---|---|---|
| `SIGNALING_ADDR` | `:8080` | 待ち受けアドレス |
| `DB_PATH` | `data/p2p-chat.db` | SQLite ファイル |

## 起動とテスト
```sh
cd services/signaling
go run ./cmd/signaling
go test -race ./...
```

## 現状の制限
- 端末の登録は誰でもできる（ローカル開発用）。公開する前に、登録の制限が必要。
- トークンの失効・再発行、端末の削除は未対応。
- オンライン状況はメモリ上にあるため、複数台構成には未対応。
