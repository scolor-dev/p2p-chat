# p2p-chat

WebRTC による端末間 P2P 通信を行うチャットアプリ。

```text
.
├── apps/
│   └── android/        # Android アプリ（Kotlin）
├── services/
│   └── signaling/      # シグナリングサーバー（Go）
└── docs/
    ├── design.md       # MVP 設計（v0.1）：アーキテクチャ、通信フロー、API 仕様
    ├── apps/
    │   └── android.md  # Android アプリの要件
    └── services/
        └── signaling.md  # サーバーの概要・責務・ファイル構造
```

## 仕組み

1. QR コードを読んで、相手の情報（端末 ID と公開鍵）をアプリに登録する
2. 登録した相手の端末 ID を指定して、サーバーに P2P 接続の仲介を頼む
3. つながったらチャットを始める。履歴はアプリに保存する

サーバーは接続の仲介（シグナリング）だけを行い、チャットの内容は通らない。

## ローカル開発

PC でサーバーを起動し、同じ Wi-Fi のスマホからアプリで接続する。

```sh
cd services/signaling && go run ./cmd/signaling
```

起動ログに表示される `ws://<LAN IP>:8080/ws` にアプリから接続する。
スマホのブラウザで `http://<LAN IP>:8080/healthz` が開ければ、ネットワーク的に到達できている。

### 環境変数

| 変数 | 既定値 | 説明 |
|---|---|---|
| `SIGNALING_ADDR` | `:8080` | 待ち受けアドレス |
| `DB_PATH` | `data/p2p-chat.db` | SQLite ファイルの場所（なければ作成） |
