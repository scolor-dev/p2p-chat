package com.p2pchat.app.ui.qr

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer

/**
 * カメラの 1 フレーム（輝度だけの画像）から QR の文字列を取り出す。
 * ViewModel やカメラの部分から切り離して、差し替え・テストできるようにする。
 */
interface QrImageDecoder {
    /**
     * @param luminance 輝度（Y）の並び。1 行が [rowStride] バイト
     * @return QR が見つかればその文字列。見つからなければ null
     */
    fun decode(luminance: ByteArray, rowStride: Int, width: Int, height: Int): String?
}

/** ZXing による [QrImageDecoder]。QR コードだけを探す。 */
class ZxingQrImageDecoder : QrImageDecoder {
    private val reader = MultiFormatReader().apply {
        setHints(
            mapOf(
                DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                DecodeHintType.TRY_HARDER to true,
            ),
        )
    }

    override fun decode(luminance: ByteArray, rowStride: Int, width: Int, height: Int): String? {
        val source = PlanarYUVLuminanceSource(luminance, rowStride, height, 0, 0, width, height, false)
        return try {
            reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text
        } catch (e: ReaderException) {
            null
        } finally {
            reader.reset()
        }
    }
}
