package com.tally.book

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.tally.book.ui.components.qrMatrix
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Base64
import java.util.Random

/** qrMatrix against ZXing's decoder over seeded random texts (a mask that leaves a finder lookalike in the data made about 1 code in 30 unreadable). */
class QrEncoderTest {
    private fun decode(text: String): String? {
        val m = qrMatrix(text); val px = 4; val q = 4
        val side = (m.size + 2 * q) * px
        val pix = IntArray(side * side) { i ->
            val mx = (i % side) / px - q; val my = (i / side) / px - q
            if (mx in m.indices && my in m.indices && m[my][mx] == 1) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
        val reader = QRCodeReader()
        return try {
            reader.decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(side, side, pix))),
                mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE), DecodeHintType.TRY_HARDER to true)).text
        } catch (e: Exception) { null }
    }

    @Test fun everyRandomTextReadsBack() {
        val rnd = Random(20261112L); val b64 = Base64.getUrlEncoder().withoutPadding()
        val bad = ArrayList<String>(); var n = 0
        fun check(s: String) { n++; if (decode(s) != s) bad.add("len ${s.length} size ${qrMatrix(s).size}: ${s.take(40)}") }
        // invite-like texts (both formats start the same way), small to large
        repeat(700) {
            val raw = ByteArray(rnd.nextInt(if (it % 30 == 0) 450 else 260)); rnd.nextBytes(raw)
            check("tally://join#t2." + b64.encodeToString(raw))
        }
        // plain ASCII of varied lengths
        repeat(300) { check(String(CharArray(1 + rnd.nextInt(if (it % 30 == 0) 450 else 300)) { (32 + rnd.nextInt(95)).toChar() })) }
        // one text near the top of every version's capacity, so each version's block structure is drawn
        for (len in listOf(1, 17, 32, 53, 78, 106, 134, 154, 192, 230, 271, 321, 367, 425, 458, 520, 586, 644, 718, 792, 858, 929))
            check(String(CharArray(len) { (32 + rnd.nextInt(95)).toChar() }))
        assertEquals("$n texts, ${bad.size} failed: ${bad.take(10)}", 0, bad.size)
    }
}
