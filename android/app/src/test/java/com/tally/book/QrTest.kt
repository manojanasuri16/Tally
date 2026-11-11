package com.tally.book

import com.tally.book.ui.components.qrMatrix
import org.junit.Assert.assertEquals
import org.junit.Test
import java.security.MessageDigest

/** The Kotlin QR encoder against hashes of the web app's own js/lib/qr.js output (see tools in the report). */
class QrTest {
    private fun sha1(text: String): Pair<Int, String> {
        val m = qrMatrix(text)
        val s = m.joinToString("") { r -> r.joinToString("") }
        return m.size to MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    @Test fun matchesTheWebEncoder() {
        assertEquals(21 to "0217d03435c72a05d611c3deb2b4fabcf814ec6f", sha1("hello"))
        assertEquals(33 to "544d4170ce300cc7706c2e55111913e67b932d6a", sha1("upi://pay?pa=name@bank&pn=Asha%20K&am=1250.00&cu=INR&tn=Dinner"))
        assertEquals(57 to "f974b75820c4fea41c5e7adfb95e4790be870ba9", sha1("x".repeat(200)))
        assertEquals(89 to "33a0c3a094ecb67e215851de1b4953a088cfd552", sha1("₹ " + "long text é ".repeat(40)))
    }
}
