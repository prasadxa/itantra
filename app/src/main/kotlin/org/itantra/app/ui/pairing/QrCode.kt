package org.itantra.app.ui.pairing

import android.graphics.Bitmap
import android.graphics.Color
import androidx.camera.core.ImageProxy
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.BitMatrix
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter

/** Renders [text] (a [org.itantra.transport.crypto.PairingInfo.toQrString] value) as a square
 * black-on-white QR bitmap, using ZXing's own encoder directly (no Play Services dependency). */
fun encodeQrBitmap(text: String, sizePx: Int = 720): Bitmap {
    val matrix: BitMatrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, sizePx, sizePx)
    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.RGB_565)
    for (x in 0 until sizePx) {
        for (y in 0 until sizePx) {
            bitmap.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
        }
    }
    return bitmap
}

private val qrOnlyReader = MultiFormatReader().apply {
    setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE)))
}

/** Decodes a QR code from a CameraX [ImageProxy] (YUV_420_888 analysis frame), or null if none is
 * found in this frame — the normal case for most frames while the camera is still framing the
 * code. Caller is responsible for [ImageProxy.close]. */
fun decodeQrFromImageProxy(image: ImageProxy): String? {
    val plane = image.planes.firstOrNull() ?: return null
    val buffer = plane.buffer
    val data = ByteArray(buffer.remaining())
    buffer.get(data)
    val source = PlanarYUVLuminanceSource(
        data, plane.rowStride, image.height, 0, 0, image.width, image.height, false,
    )
    val binary = BinaryBitmap(HybridBinarizer(source))
    return try {
        qrOnlyReader.decodeWithState(binary).text
    } catch (e: NotFoundException) {
        null
    } catch (e: Exception) {
        null
    } finally {
        qrOnlyReader.reset()
    }
}
