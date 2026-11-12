package com.tally.book

import com.tally.book.sync.Invite
import com.tally.book.ui.components.qrMatrix
import com.tally.book.ui.family.QrDecoder
import org.junit.Assert.*
import org.junit.Test

/** The scanner's decoder against our own encoder: what the Invite sheet draws is what the camera reads back. */
class QrDecoderTest {
    /** The code as a brightness plane (0 dark, 255 light) with a 4-module quiet zone, [px] pixels a module, rows [stride] bytes apart. */
    private fun plane(text: String, px: Int = 6, stride: Int? = null): Triple<ByteArray, Int, Int> {
        val m = qrMatrix(text)
        val side = (m.size + 8) * px
        val s = stride ?: side
        val out = ByteArray(s * side) { 0x37 }            // padding past the picture is noise
        for (y in 0 until side) for (x in 0 until side) {
            val mx = x / px - 4; val my = y / px - 4
            val dark = mx in m.indices && my in m.indices && m[my][mx] == 1
            out[y * s + x] = if (dark) 0 else 255.toByte()
        }
        return Triple(out, s, side)
    }

    // fixed bytes keep the test the same every run; QrEncoderTest covers the random ones
    private val invite = Invite.make(Invite.b64url(ByteArray(16) { (it * 11).toByte() }), ByteArray(32) { (it * 5).toByte() }, 1_800_000_000_000L, "Smith family", "Ravi")

    @Test fun readsTheInviteTheSheetDraws() {
        val (luma, stride, side) = plane(invite)
        val text = QrDecoder.decode(luma, stride, side, side, side)
        assertEquals(invite, text)
        assertNotNull(Invite.find(text))
    }

    @Test fun readsAPlaneWiderThanThePicture() {
        val (luma, stride, side) = plane(invite, stride = (6 * 60) + 24)      // a camera row stride is padded
        assertTrue(stride > side)
        assertEquals(invite, QrDecoder.decode(luma, stride, side, side, side))
        // the last row stopping at the picture's edge is fine too
        assertEquals(invite, QrDecoder.decode(luma.copyOf(stride * (side - 1) + side), stride, side, side, side))
    }

    @Test fun otherCodesAreReadButNotAccepted() {
        val (luma, stride, side) = plane("https://example.com/menu")
        val text = QrDecoder.decode(luma, stride, side, side, side)
        assertEquals("https://example.com/menu", text)
        assertNull(Invite.find(text))
    }

    @Test fun noCodeIsNull() {
        assertNull(QrDecoder.decode(ByteArray(640 * 480) { 200.toByte() }, 640, 480))
        assertNull(QrDecoder.decode(ByteArray(640 * 480) { (it * 31).toByte() }, 640, 480))
        assertNull(QrDecoder.decode(ByteArray(10), 640, 480, 640, 480).also { })      // too short a buffer: no crash
        assertNull(QrDecoder.decode(ByteArray(100), 10, 10, 20, 10))                  // the picture cannot be wider than the data
    }
}
