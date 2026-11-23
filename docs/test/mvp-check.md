# MVP 動作確認表（#30）

`docs/apps/android.md` の「完了の確認」7 項目を、1 つずつ確かめるための表。
実機 2 台での確認（#30 の本来の内容）はまだ行っていない。この表の「結果」欄は、実機で確かめた人が埋める。

- 自動テスト（JVM）で確かめられるのは**ロジックだけ**。WebRTC（libwebrtc）・Android Keystore・Room・カメラ・画面・回線の切り替えは、JVM では動かない。
- 「自動テストで確認済み」と書いたものも、**偽のシグナリングサーバーと偽の WebRTC の上での確認**であり、実機で動くことの保証ではない。
- 今回の結合テストは `apps/android/app/src/test/java/com/p2pchat/app/integration/`（`MvpIntegrationTest`、道具は `TwoDeviceHarness.kt`）。

## 1. 検証マトリクス

凡例: 実機 = 実機で確かめる必要があるか。結果欄は「未実施」のままにしておき、実機で確かめたら 日付 / 端末 / ○× / メモ を書く。

| # | 完了の確認 | 実機が必要か | 既存の自動テスト | 今回追加したテスト（`MvpIntegrationTest`） | 結果 |
|---|---|---|---|---|---|
| 1 | 2 台の端末で、それぞれ登録が済み、`deviceId` が表示される | 必要（Keystore の鍵生成、実サーバーへの HTTP） | `DefaultDeviceRepositoryTest` / `RegistrationViewModelTest` / `StartViewModelTest` 系 / `OkHttpApiClientTest`（MockWebServer）/ `DataStoreDeviceIdentityStoreTest` / `KeystoreInstrumentedTest`（androidTest。未実行） | なし（今回の範囲外） | 未実施 |
| 2 | A が B の QR を読み、B が「確認済み」で連絡先に保存される | 必要（カメラ、QR の表示と読み取り） | `ContactQrParserTest` / `ContactQrUrlTest` / `PublicKeyFingerprintTest` / `AddContactViewModelTest` / `QrScanViewModelTest` / `QrViewModelTest` / `ZxingQrImageDecoderTest` / `OfflineContactRepositoryTest` / `ContactDaoTest`（androidTest。未実行） | なし | 未実施 |
| 3a | A から話しかけると、B に接続の申請が表示される | 必要（実 WebRTC、画面のダイアログ） | `DefaultConnectionRequestManagerTest`（申請の保留・順番・締め切り） | 「知らない相手の申請を拒否すると…」で、B の `ConnectionRequestManager.current` に A の名前で申請が出ること、許可まで B が PeerConnection を作らないことを確認 | 未実施 |
| 3b | 拒否すると、A に「拒否されました」と表示される | 必要（画面の文言） | `DefaultPeerConnectorTest` / `ChatViewModelTest`（Rejected → 文言の対応） | 同上。A の送信が `PEER / Rejected` で失敗し、B の連絡先が増えないこと | 未実施 |
| 3c | 申請に 60 秒応答しない | 必要（実時間） | `DefaultConnectionRequestManagerTest` | 「申請に 60 秒応答しないと A は Timeout…」（仮想時間） | 未実施 |
| 4 | もう一度話しかけて B が許可すると、A が「未確認」で B の連絡先に追加され、テキストが双方向に届く | 必要（実 WebRTC） | `DefaultConnectionRequestManagerTest` / `P2PChatMessageSenderTest` / `IncomingMessageHandlerTest` | 「申請を許可すると未確認の連絡先に追加されチャットできる」。拒否 → 再度話しかける → 許可、`isVerified=false`・公開鍵の保存、A→B と B→A の復号・保存・ack まで通し | 未実施 |
| 5a | 送ったメッセージが「送信済み」になる | 必要（実 DataChannel） | `DefaultOutgoingMessageTrackerTest` / `P2PChatMessageSenderTest` | 「連絡先にいる相手に送ると B に保存され ack で A が送信済みになる」、「B から A へも送れる」。本文が通信路に平文で流れないことも確認 | 未実施 |
| 5b | B の Wi-Fi を切った状態で送ると「送信失敗」になる | 必要（実回線の切断） | `P2PChatMessageSenderTest` / `ChatViewModelTest` | 「ack が来ないと 10 秒で送信失敗…」は通る（接続はあるが ack が来ない場合）。相手が WebSocket にいない場合も、送信失敗のメッセージが履歴に残り、つながった後に再送できる（#58 で修正。「相手がオフラインで送ると 送信失敗のメッセージが残り…」） | 未実施 |
| 5c | つながった後に再送すると「送信済み」になる（B 側に重複して表示されない） | 必要（実回線） | `ResendTest` / `IncomingMessageHandlerTest` | 「ack が来ないと 10 秒で送信失敗になり、手動再送で送信済みになる 相手には 1 件のまま」、「同じ id の msg が重複して届いても B には 1 件…」 | 未実施 |
| 6 | アプリを再起動しても、履歴と送信状態が残っている | 必要（プロセスの再起動、Room） | `RoomMessageRepositoryTest`（DAO は Fake）/ `MessageDaoTest`・`ContactDaoTest`（androidTest。未実行）/ `DefaultOutgoingMessageTrackerTest`（`failInterruptedSending`） | なし（メモリ上のリポジトリのため確認できない） | 未実施 |
| 7 | 片方の Wi-Fi を切って入れ直すと、自動で再接続される | 必要（実回線、ICE の再接続、WebSocket の再接続） | `PeerReconnectorTest` / `ReconnectBackoffTest` / `DefaultSignalingClientTest` / `OkHttpWebSocketClientTest` | 「接続が切れると自動で再接続し その後も送れる」（WebSocket と DataChannel が切れ、戻ったら張り直して両方向に送れる。仮想時間） | 未実施 |
| 補 | 連絡先にいる相手との接続（確認なし） | 必要 | `DefaultPeerConnectorTest` | 5a のテストに含む | 未実施 |
| 補 | 同時に offer を送り合っても 1 本の接続になる（辞書順） | 必要（実 SDP の rollback） | `DefaultPeerConnectorTest` | 「同時に offer を送り合っても 1 本の接続になり…」（`channelsOpened == 1`、answer は大きい側だけ） | 未実施 |
| 補 | 相手がオンラインでないとき PeerOffline | 必要（実サーバーの `error`） | `DefaultPeerConnectorTest` / `ChatViewModelTest` | 「相手が WebSocket につながっていないと PeerOffline になり…」 | 未実施 |
| 補 | 改ざん・鍵違いの msg を捨て、警告を出す | 画面の警告の見え方のみ必要 | `IncomingMessageHandlerTest` / `ChatViewModelTest` / `AesGcmMessageCipherTest` | 「暗号文が改ざんされた msg は捨てられ…」、「別の鍵で暗号化された msg も捨てられ…」 | 未実施 |
| 9 | サーバーの IP・ポートをアプリ内で入力・変更できる | 必要（実機の入力、平文通信の許可） | `SettingsViewModelTest` / `DataStoreServerSettingsRepositoryTest` | なし | 未実施 |

## 2. 今回の自動テストで確かめたこと / 確かめていないこと

### 確かめたこと（偽のシグナリングサーバー + 偽の WebRTC + 実物の暗号の上で）
2 台ぶんの `DefaultPeerConnector` / `DefaultConnectionRequestManager` / `P2PChatMessageSender` / `IncomingMessageHandler` / `DefaultOutgoingMessageTracker` / `DefaultPeerReconnector` と、実物の `AesGcmMessageCipher`（端末ごとに JVM で鍵ペアを生成）を組み、次を通しで確認した。時間はすべて仮想時間。

- (a) 連絡先にいる相手への接続 → A→B 送信 → B が復号・保存・ack → A が送信済み
- (b) B→A の逆向き、同じ接続での往復
- (c) 同じ id の msg の重複は B に 1 件、ack は毎回返る
- (d) 知らない相手の申請: 拒否 → A は Rejected、B の連絡先は増えない / 許可 → 未確認の連絡先（名前・公開鍵つき）に追加され、双方向にチャットできる / 60 秒無応答 → A は Timeout
- (e) 同時 offer: 接続は 1 本、小さい deviceId の offer が採用される
- (f) 相手が WebSocket にいない: `PeerOffline`
- (g) WebSocket と DataChannel が切れたあとの自動再接続と、再接続後の送信
- (h) ack が来ないと 10 秒で送信失敗、手動再送で送信済み、相手は 1 件のまま
- (i) 暗号文の改ざん・鍵の不一致: 捨てる、警告（`decryptFailures`）が出る、保存も ack もしない

### 確かめていないこと（実機・実環境が必要）
- 実際の libwebrtc の動作: SDP / ICE / STUN による接続、DataChannel の open / close、offer の rollback の代わりの作り直し、`disconnected` から `failed` への遷移の実際の時間。偽の WebRTC は「offer と answer が渡れば即座に open」「切断は即座に通知」という理想的な動きしかしない。
- 実サーバー（`services/signaling`）との WebSocket・HTTP。偽のサーバーが `docs/design.md` の仕様どおりに振る舞うと仮定している。
- Android Keystore での鍵生成・ECDH（JVM の鍵で代用）、Room（DB）、DataStore への保存。**項目 6（再起動後の履歴・送信状態）は自動では確かめていない**。
- カメラ・QR（項目 2）、画面の表示・文言・ダイアログ（項目 3 の「表示される」、5 の「送信済み」の見た目）、設定画面（項目 9）。
- 回線の切り替え（Wi-Fi ⇔ モバイル）、アプリが背面に回ったときの挙動。
- 実時間での 60 秒・10 秒のタイムアウト、端末のスリープ。

### 見つかった不具合
- 相手が WebSocket にいないとき送信失敗のメッセージが履歴に残らず再送できなかった不具合は、#58 で修正済み（接続を待つ前に保存し、接続に失敗したら FAILED にする）。

## 3. 実機での確認手順

### 準備
1. 端末 A・B（Android 12 以上）と、同じ Wi-Fi（またはテザリング）につないだ PC を用意する。両端末と PC が同じネットワークにいること。
2. PC でサーバーを起動する（ポートは任意。ここでは 8081）。

   ```sh
   cd services/signaling && SIGNALING_ADDR=:8081 go run ./cmd/signaling
   ```

3. PC の LAN IP を調べる（例: macOS は `ipconfig getifaddr en0`）。以降 `<PC の IP>` と書く。
4. 端末 A・B に、`cd apps/android && ./gradlew installDebug` でアプリを入れる（または Android Studio から実行）。
5. 各端末の**設定画面**で、サーバーの IP に `<PC の IP>`、ポートに `8081` を入力して保存する（エミュレーターなら `10.0.2.2`）。接続の確認が成功することを見る。

### 項目 1: 登録
1. A・B それぞれでアプリを開き、登録（名前の入力 → 登録）を行う。
2. **期待**: 各端末に `deviceId`（16 文字）が表示される。A と B で異なる。サーバーのログに `POST /devices` が 2 回出る。

### 項目 2: QR で連絡先に追加
1. B で自分の QR を表示する。A でその QR を読み取る。
2. **期待**: A の連絡先に B が「確認済み」で保存される。偽の QR（指紋の違うもの）では保存されずエラーになる（任意）。

### 項目 3: 申請の拒否
1. A の連絡先から B を開き、メッセージを送る（B はアプリを前面で開いておく）。B はまだ A を連絡先に入れていないこと。
2. **期待（B）**: 「A さんから接続の申請があります」の確認が出る。
3. B で拒否する。
4. **期待（A）**: 「接続を拒否されました」と表示される。B の連絡先に A は増えない。
5. （任意）申請に 60 秒何もしない: A は「応答がありません」になり、B の確認は閉じる。

### 項目 4: 許可して双方向にチャット
1. A からもう一度メッセージを送る。B の確認で今度は**許可**する。
2. **期待**: B の連絡先に A が「未確認」で追加される。A から送ったテキストが B に表示される。B から返信すると A に届く。

### 項目 5: 送信状態と再送
1. 5a: A から送る。**期待**: 送信中 → 数秒以内に「送信済み」になる。
2. 5b: B の Wi-Fi を切る（機内モードでも可）。A から送る。**期待**: 履歴に「送信失敗」のメッセージが残り、入力欄は空になる（失敗の理由が 1 行出る）。
3. 5c: B の Wi-Fi を戻し、B のアプリが前面でサーバーにつながったことを確認する。A で送信失敗のメッセージをタップして再送する。**期待**: 「送信済み」になる。B には同じメッセージが 1 件だけ表示される（重複しない）。

### 項目 6: 再起動
1. A・B のアプリを完全に終了（最近使ったアプリから消す）して、もう一度開く。
2. **期待**: 履歴がすべて残っている。送信済み・送信失敗の状態も残る。送信中のまま終了したメッセージは「送信失敗」になっている。

### 項目 7: 自動再接続
1. A と B がつながってチャットできる状態にする。
2. A の Wi-Fi を切り、数秒後に入れ直す。
3. **期待**: A の画面に「再接続中」が出たあと、自動でつながり、メッセージの送受信ができる（手動で操作しない）。
4. B 側でも同じことを試す。サーバーを一度止めて再起動した場合も、アプリが自動で WebSocket に再接続する。

### 項目 9: 設定
1. 設定画面で IP・ポートを変更して保存する（誤った値 → 正しい値）。
2. **期待**: 誤った値では接続の確認が失敗し、正しい値では成功する。保存後は再接続される。

## 4. 結果の記録欄

| 日付 | 確認者 | 端末（A / B）と OS | 項目 | 結果（○ / ×） | メモ・不具合の Issue |
|---|---|---|---|---|---|
| 未実施 | | | 1 | | |
| 未実施 | | | 2 | | |
| 未実施 | | | 3 | | |
| 未実施 | | | 4 | | |
| 未実施 | | | 5a | | |
| 未実施 | | | 5b | | |
| 未実施 | | | 5c | | |
| 未実施 | | | 6 | | |
| 未実施 | | | 7 | | |
| 未実施 | | | 9 | | |
