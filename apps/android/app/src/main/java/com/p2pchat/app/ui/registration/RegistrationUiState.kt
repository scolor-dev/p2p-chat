package com.p2pchat.app.ui.registration

/** 登録画面が表示に使う状態。 */
data class RegistrationUiState(
    /** サーバー設定と登録状況を読み込み終わるまで true。 */
    val isLoading: Boolean = true,
    val name: String = "",
    val nameError: NameError? = null,
    /** サーバーの接続先が保存されているか。false なら登録できない（設定画面へ案内する）。 */
    val isServerConfigured: Boolean = false,
    val isRegistering: Boolean = false,
    val error: RegistrationError? = null,
    /** 登録済みならその `deviceId`。null なら未登録。 */
    val registeredDeviceId: String? = null,
)

/** 名前の入力の誤り。 */
enum class NameError { Empty, TooLong }

/** 登録に失敗した理由。画面はこれを文言にする（秘密の値は持たない）。 */
sealed interface RegistrationError {
    data object ServerNotConfigured : RegistrationError
    data object KeyFailed : RegistrationError
    data object Network : RegistrationError

    /** サーバーに拒否された（400 など）。[detail] はサーバーの `code` と `message`。 */
    data class Rejected(val detail: String) : RegistrationError

    /** サーバーでは登録できたが、端末に保存できなかった。 */
    data object StorageFailed : RegistrationError
}
