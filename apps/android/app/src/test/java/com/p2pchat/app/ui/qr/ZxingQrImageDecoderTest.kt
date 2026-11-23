package com.p2pchat.app.ui.qr

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ZxingQrImageDecoderTest {
    /** QR を、カメラの輝度画像（黒 0 / 白 255）の形にする。[padding] は 1 行あたりの余分なバイト（rowStride 用）。 */
    private fun luminance(text: String, size: Int, padding: Int = 0): Pair<ByteArray, Int> {
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 4))
        val stride = size + padding
        val data = ByteArray(stride * size) { 255.toByte() }
        for (y in 0 until size) for (x in 0 until size) {
            if (matrix[x, y]) data[y * stride + x] = 0
        }
        return data to stride
    }

    @Test
    fun QRの画像から文字列を取り出せる() {
        val url = "p2pchat://contact?id=a1b2c3d4e5f60718&fp=ungWv48Bz-pBQUDeXa4iI7ADYaOWF3qctBD_YfIAFa0"
        val (data, stride) = luminance(url, 300)

        assertEquals(url, ZxingQrImageDecoder().decode(data, stride, 300, 300))
    }

    @Test
    fun 行の長さが画像の幅より大きくても読める() {
        val (data, stride) = luminance("hello", 300, padding = 24)

        assertEquals("hello", ZxingQrImageDecoder().decode(data, stride, 300, 300))
    }

    @Test
    fun QRが写っていなければnull() {
        val blank = ByteArray(200 * 200) { 255.toByte() }

        assertNull(ZxingQrImageDecoder().decode(blank, 200, 200, 200))
    }
}
