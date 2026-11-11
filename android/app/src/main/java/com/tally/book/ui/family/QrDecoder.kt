package com.tally.book.ui.family

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader

/**
 * Reads a QR code out of one camera frame's brightness plane (ZXing's pure-Java decoder: no Play Services, no network).
 * [luma] holds [dataHeight] rows of [dataWidth] bytes (the plane's row stride, which can be wider than the picture);
 * the picture is the top-left [width] x [height]. Returns the code's text, or null when there is no readable code.
 * Called on a background thread, once per frame.
 */
object QrDecoder {
    private val hints = mapOf<DecodeHintType, Any>(
        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
        DecodeHintType.TRY_HARDER to true,
    )

    fun decode(luma: ByteArray, dataWidth: Int, dataHeight: Int, width: Int = dataWidth, height: Int = dataHeight): String? {
        if (width <= 0 || height <= 0 || width > dataWidth || height > dataHeight) return null
        // the last row of a camera plane may stop at the picture's edge, short of a whole stride
        val data = if (luma.size >= dataWidth * dataHeight) luma else luma.copyOf(dataWidth * dataHeight)
        val reader = QRCodeReader()
        return try {
            val source = PlanarYUVLuminanceSource(data, dataWidth, dataHeight, 0, 0, width, height, false)
            reader.decode(BinaryBitmap(HybridBinarizer(source)), hints).text
        } catch (e: ReaderException) {
            null    // no code in this frame, or one too blurred to read: the next frame tries again
        } catch (e: RuntimeException) {
            null
        } finally {
            reader.reset()
        }
    }
}
