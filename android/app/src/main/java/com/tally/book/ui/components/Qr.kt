package com.tally.book.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader

/*
 * QR code encoder (ported from Balance's app/js/lib/qr.js) (no state, no network). qrMatrix(text) returns the module grid
 * (rows of 0/1, 21..177 on a side): byte mode (UTF-8), error correction level M, the smallest version that fits,
 * the mask with the lowest penalty. Throws IllegalArgumentException when the text is too long for version 40.
 */

private val QR_ECC_PER_BLOCK = intArrayOf(0, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26, 30, 22, 22, 24, 24, 28, 28, 26, 26, 26, 26, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28)
private val QR_ECC_BLOCKS = intArrayOf(0, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5, 5, 8, 9, 9, 10, 10, 11, 13, 14, 16, 17, 17, 18, 20, 21, 23, 25, 26, 28, 29, 31, 33, 35, 37, 38, 40, 43, 45, 47, 49)

private fun qrRawModules(ver: Int): Int {
    var r = (16 * ver + 128) * ver + 64
    if (ver >= 2) {
        val na = ver / 7 + 2
        r -= (25 * na - 10) * na - 55
        if (ver >= 7) r -= 36
    }
    return r
}

private fun qrDataCodewords(ver: Int): Int = qrRawModules(ver) / 8 - QR_ECC_PER_BLOCK[ver] * QR_ECC_BLOCKS[ver]

private fun qrAlignPositions(ver: Int): List<Int> {
    if (ver == 1) return emptyList()
    val size = ver * 4 + 17
    val na = ver / 7 + 2
    val step = (ver * 8 + na * 3 + 5) / (na * 4 - 4) * 2
    val out = arrayListOf(6)
    var pos = size - 7
    while (out.size < na) { out.add(1, pos); pos -= step }
    return out
}

/** GF(256) with the QR polynomial x^8+x^4+x^3+x^2+1 */
private fun qrMul(x: Int, y: Int): Int {
    var z = 0
    for (i in 7 downTo 0) { z = (z shl 1) xor ((z ushr 7) * 0x11D); z = z xor (((y ushr i) and 1) * x) }
    return z
}

private fun qrDivisor(degree: Int): IntArray {
    val r = IntArray(degree); r[degree - 1] = 1
    var root = 1
    for (i in 0 until degree) {
        for (j in r.indices) { r[j] = qrMul(r[j], root); if (j + 1 < r.size) r[j] = r[j] xor r[j + 1] }
        root = qrMul(root, 0x02)
    }
    return r
}

private fun qrRemainder(data: List<Int>, div: IntArray): IntArray {
    val r = IntArray(div.size)
    data.forEach { b ->
        val f = b xor r[0]
        for (i in 0 until r.size - 1) r[i] = r[i + 1]
        r[r.size - 1] = 0
        div.forEachIndexed { i, c -> r[i] = r[i] xor qrMul(c, f) }
    }
    return r
}

private fun qrInterleave(data: List<Int>, ver: Int): List<Int> {
    val nb = QR_ECC_BLOCKS[ver]; val eccLen = QR_ECC_PER_BLOCK[ver]
    val raw = qrRawModules(ver) / 8
    val nShort = nb - raw % nb; val shortLen = raw / nb
    val div = qrDivisor(eccLen)
    val blocks = ArrayList<List<Int>>()
    var k = 0
    for (i in 0 until nb) {
        val len = shortLen - eccLen + (if (i < nShort) 0 else 1)
        val dat = ArrayList(data.subList(k, minOf(k + len, data.size)))
        k += dat.size
        val ecc = qrRemainder(dat, div)
        if (i < nShort) dat.add(0)
        blocks.add(dat + ecc.toList())
    }
    val out = ArrayList<Int>()
    for (i in 0 until blocks[0].size) blocks.forEachIndexed { j, b -> if (i != shortLen - eccLen || j >= nShort) out.add(b[i]) }
    return out
}

private fun qrMaskBit(mask: Int, x: Int, y: Int): Boolean = when (mask) {
    0 -> (x + y) % 2 == 0
    1 -> y % 2 == 0
    2 -> x % 3 == 0
    3 -> (x + y) % 3 == 0
    4 -> (x / 3 + y / 2) % 2 == 0
    5 -> x * y % 2 + x * y % 3 == 0
    6 -> (x * y % 2 + x * y % 3) % 2 == 0
    else -> ((x + y) % 2 + x * y % 3) % 2 == 0
}

private fun qrPenalty(m: Array<IntArray>): Int {
    val size = m.size
    var p = 0; var dark = 0
    fun line(get: (Int) -> Boolean) {
        var run = 1
        for (i in 1..size) {
            if (i < size && get(i) == get(i - 1)) run++
            else { if (run >= 5) p += run - 2; run = 1 }
        }
        var i = 0
        while (i + 7 <= size) {
            if (get(i) && !get(i + 1) && get(i + 2) && get(i + 3) && get(i + 4) && !get(i + 5) && get(i + 6)) {
                val lightBefore = (1..4).all { k -> i - k < 0 || !get(i - k) }
                val lightAfter = (7..10).all { k -> i + k >= size || !get(i + k) }
                if (lightBefore || lightAfter) p += 40
            }
            i++
        }
    }
    for (y in 0 until size) line { x -> m[y][x] == 1 }
    for (x in 0 until size) line { y -> m[y][x] == 1 }
    for (y in 0 until size - 1) for (x in 0 until size - 1) {
        val c = m[y][x]
        if (c == m[y][x + 1] && c == m[y + 1][x] && c == m[y + 1][x + 1]) p += 3
    }
    m.forEach { r -> r.forEach { c -> dark += c } }
    val total = size * size
    p += Math.abs(dark * 20 - total * 10) / total * 10
    return p
}

/**
 * True when the scanner's own decoder (ZXing, the one QrDecoder uses) reads [m] back as [text]. The encoder is exact
 * (bit for bit what ZXing's encoder makes for the same mask), but the standard mask penalty does not look at what a
 * detector does: in about 1 code in 30 a mask leaves a stray finder-pattern lookalike in the data area that ZXing takes
 * for a corner, and that mask is not found at all, even in a perfect picture. Another mask of the same code reads fine.
 */
private fun qrReadsBack(m: Array<IntArray>, text: String): Boolean =
    (3..7).all { qrReadsBackAt(m, text, it) }       // a camera sees the code at any size, and a few sizes see different things

private fun qrReadsBackAt(m: Array<IntArray>, text: String, px: Int): Boolean {
    val quiet = 4; val side = (m.size + 2 * quiet) * px
    val pixels = IntArray(side * side) { i ->
        val x = (i % side) / px - quiet; val y = (i / side) / px - quiet
        if (x in m.indices && y in m.indices && m[y][x] == 1) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
    }
    return try {
        val hints = mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE), DecodeHintType.TRY_HARDER to true)
        QRCodeReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(side, side, pixels))), hints).text == text
    } catch (e: Exception) { false }
}

fun qrMatrix(text: String): Array<IntArray> {
    val bytes = text.toByteArray(Charsets.UTF_8).map { it.toInt() and 0xff }
    var ver = 1
    while (ver <= 40) {
        val ccBits = if (ver < 10) 8 else 16
        if (4 + ccBits + bytes.size * 8 <= qrDataCodewords(ver) * 8) break
        ver++
    }
    require(ver <= 40) { "Too long for a QR code" }
    val cap = qrDataCodewords(ver) * 8
    val bits = ArrayList<Int>()
    fun put(v: Int, len: Int) { for (i in len - 1 downTo 0) bits.add((v ushr i) and 1) }
    put(4, 4)
    put(bytes.size, if (ver < 10) 8 else 16)
    bytes.forEach { put(it, 8) }
    put(0, minOf(4, cap - bits.size))
    put(0, (8 - bits.size % 8) % 8)
    var pad = 0xEC
    while (bits.size < cap) { put(pad, 8); pad = pad xor 0xEC xor 0x11 }
    val data = ArrayList<Int>()
    var i0 = 0
    while (i0 < bits.size) { data.add(bits.subList(i0, i0 + 8).fold(0) { a, b -> a * 2 + b }); i0 += 8 }
    val words = qrInterleave(data, ver)

    val size = ver * 4 + 17
    val mods = Array(size) { IntArray(size) }
    val fn = Array(size) { BooleanArray(size) }
    fun setF(x: Int, y: Int, dark: Boolean) { mods[y][x] = if (dark) 1 else 0; fn[y][x] = true }
    for (i in 0 until size) { setF(6, i, i % 2 == 0); setF(i, 6, i % 2 == 0) }
    listOf(3 to 3, size - 4 to 3, 3 to size - 4).forEach { (cx, cy) ->
        for (dy in -4..4) for (dx in -4..4) {
            val d = maxOf(Math.abs(dx), Math.abs(dy)); val x = cx + dx; val y = cy + dy
            if (x in 0 until size && y in 0 until size) setF(x, y, d != 2 && d != 4)
        }
    }
    val al = qrAlignPositions(ver); val last = al.size - 1
    al.forEachIndexed { i, ay ->
        al.forEachIndexed { j, ax ->
            if ((i == 0 && j == 0) || (i == 0 && j == last) || (i == last && j == 0)) return@forEachIndexed
            for (dy in -2..2) for (dx in -2..2) setF(ax + dx, ay + dy, maxOf(Math.abs(dx), Math.abs(dy)) != 1)
        }
    }
    fun drawFormat(mask: Int) {
        val d = mask
        var rem = d
        for (i in 0 until 10) rem = (rem shl 1) xor ((rem ushr 9) * 0x537)
        val b = ((d shl 10) or rem) xor 0x5412
        fun bit(i: Int) = ((b ushr i) and 1) == 1
        for (i in 0..5) setF(8, i, bit(i))
        setF(8, 7, bit(6)); setF(8, 8, bit(7)); setF(7, 8, bit(8))
        for (i in 9 until 15) setF(14 - i, 8, bit(i))
        for (i in 0 until 8) setF(size - 1 - i, 8, bit(i))
        for (i in 8 until 15) setF(8, size - 15 + i, bit(i))
        setF(8, size - 8, true)
    }
    drawFormat(0)
    if (ver >= 7) {
        var rem = ver
        for (i in 0 until 12) rem = (rem shl 1) xor ((rem ushr 11) * 0x1F25)
        val b = (ver shl 12) or rem
        for (i in 0 until 18) {
            val dark = ((b ushr i) and 1) == 1; val a = size - 11 + i % 3; val c = i / 3
            setF(a, c, dark); setF(c, a, dark)
        }
    }
    var i = 0
    var right = size - 1
    while (right >= 1) {
        if (right == 6) right = 5
        for (v in 0 until size) for (j in 0..1) {
            val x = right - j; val up = ((right + 1) and 2) == 0; val y = if (up) size - 1 - v else v
            if (!fn[y][x] && i < words.size * 8) { mods[y][x] = (words[i ushr 3] ushr (7 - (i and 7))) and 1; i++ }
        }
        right -= 2
    }
    fun applyMask(mask: Int) {
        for (y in 0 until size) for (x in 0 until size) if (!fn[y][x] && qrMaskBit(mask, x, y)) mods[y][x] = mods[y][x] xor 1
    }
    // masks from the lowest penalty up; the first one the decoder reads back wins (the lowest penalty nearly always does)
    val penalty = IntArray(8)
    for (mask in 0 until 8) { applyMask(mask); drawFormat(mask); penalty[mask] = qrPenalty(mods); applyMask(mask) }
    val order = (0 until 8).sortedBy { penalty[it] }
    var best = order[0]
    for (mask in order) {
        applyMask(mask); drawFormat(mask)
        val ok = qrReadsBack(mods, text)
        applyMask(mask)
        if (ok) { best = mask; break }
    }
    applyMask(best); drawFormat(best)
    return mods
}

/** Draws [text] as a QR code, dark modules on a white square (always white, so scanners read it in dark mode too). Text too long for version 40 draws nothing. */
@Composable
fun QrCode(text: String, modifier: Modifier = Modifier, size: Dp = 220.dp) {
    // Choosing a mask now reads each candidate back with ZXing (tens to hundreds of ms on a phone), so build it off the main thread;
    // the white square keeps its place meanwhile, so nothing jumps when the code appears.
    var m by remember(text) { mutableStateOf<Array<IntArray>?>(null) }
    LaunchedEffect(text) { m = withContext(Dispatchers.Default) { runCatching { qrMatrix(text) }.getOrNull() } }
    Canvas(modifier.size(size).background(Color.White).padding(8.dp)) {
        val m = m ?: return@Canvas
        val cell = this.size.width / m.size
        for (y in m.indices) for (x in m[y].indices) if (m[y][x] == 1)
            drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
    }
}
