package com.p2pchat.app.ui.settings

/** 設定画面が表示に使う状態。 */
data class SettingsUiState(
    /** 保存済みの値を読み込み終わるまで true（入力欄がちらつかないように、読み込み中は表示しない）。 */
    val isLoading: Boolean = true,
    val host: String = "",
    /** 入力途中の値も持つので、文字列のまま持つ。 */
    val port: String = "",
    val hostError: Boolean = false,
    val portError: Boolean = false,
    /** 入力中の値が保存済みの値と同じなら true。 */
    val isSaved: Boolean = false,
    val connectionTest: ConnectionTestState = ConnectionTestState.Idle,
)

/** 接続テスト（`GET /healthz`）の状態。 */
sealed interface ConnectionTestState {
    data object Idle : ConnectionTestState
    data object Running : ConnectionTestState
    data object Success : ConnectionTestState

    /** [detail] は例外のメッセージなど、原因の手がかり。 */
    data class Failure(val detail: String) : ConnectionTestState
}
