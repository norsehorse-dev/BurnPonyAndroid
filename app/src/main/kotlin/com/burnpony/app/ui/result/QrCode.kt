//
// QrCode.kt
// ZXing-core QR generation for the in-person handoff tile: dark modules on
// the cream (#FFF0DD) ink tone, error correction M like iOS.
//

package com.burnpony.app.ui.result

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

object QrCode {

    private val DARK = 0xFF050408.toInt()   // ember background token
    private val CREAM = 0xFFFFF0DD.toInt()  // ink token, the tile color

    /** Renders [content] as a QR ImageBitmap, or null if encoding fails. */
    fun generate(content: String, sizePx: Int = 880): ImageBitmap? {
        return try {
            val hints = mapOf(
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                EncodeHintType.MARGIN to 2,
                EncodeHintType.CHARACTER_SET to "UTF-8",
            )
            val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
            val pixels = IntArray(matrix.width * matrix.height)
            for (y in 0 until matrix.height) {
                for (x in 0 until matrix.width) {
                    pixels[y * matrix.width + x] = if (matrix.get(x, y)) DARK else CREAM
                }
            }
            val bitmap = Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
            bitmap.setPixels(pixels, 0, matrix.width, 0, 0, matrix.width, matrix.height)
            bitmap.asImageBitmap()
        } catch (e: Exception) {
            null
        }
    }
}
