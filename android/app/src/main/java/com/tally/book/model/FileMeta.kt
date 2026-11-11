package com.tally.book.model

import com.tally.book.core.*

/*
 * Metadata half of document files (port of app/js/model/filemeta.js in Balance): no DOM, no crypto.
 * A metadata row lives in DB.files ({ id, ownerType, ownerId, name, mime, size, sha256, ... }); the bytes are in the data layer (data/Files.kt). fileSniff takes the first bytes as a ByteArray.
 */

val FILE_DOC_KINDS = listOf("promissory", "agreement", "sanction", "schedule", "notice", "noc", "closure", "receipt", "stamp", "idproof", "other")
val FILE_DOC_KIND_NAME = mapOf("promissory" to "Promissory note", "agreement" to "Loan agreement", "sanction" to "Sanction letter", "schedule" to "Repayment schedule",
    "notice" to "Legal notice", "noc" to "NOC", "closure" to "Closure letter", "receipt" to "Receipt", "stamp" to "Stamp paper", "idproof" to "ID proof", "other" to "Other")

fun fileDocKindL(k: String?): String {
    when (k) {
        "promissory" -> return t("files.kind.promissory", "Promissory note")
        "agreement" -> return t("files.kind.agreement", "Loan agreement")
        "sanction" -> return t("files.kind.sanction", "Sanction letter")
        "schedule" -> return t("files.kind.schedule", "Repayment schedule")
        "notice" -> return t("files.kind.notice", "Legal notice")
        "noc" -> return t("files.kind.noc", "NOC")
        "closure" -> return t("files.kind.closure", "Closure letter")
        "receipt" -> return t("files.kind.receipt", "Receipt")
        "stamp" -> return t("files.kind.stamp", "Stamp paper")
        "idproof" -> return t("files.kind.idproof", "ID proof")
    }
    /* the user types their own kind now; only the old fixed keys above are translated */
    return if (!k.isNullOrEmpty() && k != "other") k else t("files.kind.other", "Other")
}
private fun fileTrim(s: String): String = s.trim { it.isWhitespace() || it == '\u00a0' || it == '\ufeff' }
private val WS_RUN = Regex("[\\s\\u00a0\\u1680\\u2000-\\u200a\\u2028\\u2029\\u202f\\u205f\\u3000\\ufeff]+")
/* what the Kind field may hold: the user's own words, trimmed and capped; blank means "other" */
fun fileKindClean(v: Any?): String {
    val s = fileTrim(WS_RUN.replace(if (v == null) "" else jsString(v), " ")).take(60)
    return s.ifEmpty { "other" }
}

/* What may be stored: the extension decides, the browser's MIME type must agree (or be blank), and the first bytes must look like that format.
   family picks how Open behaves: an image opens in the zoomable viewer, a PDF in a new tab, Word downloads. */
class FileTypeInfo(val family: String, val mime: List<String>)
val FILE_TYPES: Map<String, FileTypeInfo> = linkedMapOf(
    "pdf" to FileTypeInfo("pdf", listOf("application/pdf", "application/x-pdf")),
    "doc" to FileTypeInfo("word", listOf("application/msword")),
    "docx" to FileTypeInfo("word", listOf("application/vnd.openxmlformats-officedocument.wordprocessingml.document", "application/zip", "application/x-zip-compressed")),
    "jpg" to FileTypeInfo("image", listOf("image/jpeg", "image/jpg", "image/pjpeg")),
    "jpeg" to FileTypeInfo("image", listOf("image/jpeg", "image/jpg", "image/pjpeg")),
    "png" to FileTypeInfo("image", listOf("image/png")),
    "webp" to FileTypeInfo("image", listOf("image/webp")),
    "heic" to FileTypeInfo("image", listOf("image/heic", "image/heic-sequence", "image/heif")),
    "heif" to FileTypeInfo("image", listOf("image/heif", "image/heif-sequence", "image/heic")),
    "gif" to FileTypeInfo("image", listOf("image/gif")))
val FILE_LOOSE_MIME = listOf("", "application/octet-stream", "binary/octet-stream")
/* the file picker's accept list, so it offers only what will be taken */
val FILE_ACCEPT: String = FILE_TYPES.keys.joinToString(",") { ".$it" } + "," + FILE_TYPES.values.map { it.mime[0] }.distinct().joinToString(",")
const val FILE_CAP_MB_DEFAULT = 25.0
val FILE_CAP_CHOICES = listOf(5.0, 10.0, 25.0, 50.0)

/** Live file records: tombstones (deleted: true) are hidden, so filesOf/fileCount/fileById never show them. */
fun filesAll(): List<Obj> = if (DB["files"] is List<*>) DB.objs("files").filter { !it.b("deleted") } else emptyList()
fun filesOf(ownerType: String, ownerId: String): List<Obj> =
    filesAll().filter { it["ownerType"] == ownerType && it["ownerId"] == ownerId }.sortedWith { a, b -> val x = a.num("addedAt"); val y = b.num("addedAt"); if (x < y) -1 else if (x > y) 1 else 0 }
fun fileCount(ownerType: String, ownerId: String): Double = filesOf(ownerType, ownerId).size.toDouble()
fun fileById(id: String?): Obj? = filesAll().find { it["id"] == id }
fun fileCapMB(): Double { val v = DB.num("fileCapMB"); return if (v > 0) v else FILE_CAP_MB_DEFAULT }
fun fileNewId(): String = uid("f")
private val FILE_EXT_RE = Regex("\\.([A-Za-z0-9]{1,5})$")
fun fileExt(name: Any?): String {
    val m = FILE_EXT_RE.find(fileTrim(str(name)))
    return m?.groupValues?.get(1)?.lowercase() ?: ""
}
private fun mimeBase(mime: Any?): String = fileTrim(str(mime).lowercase().split(";")[0])
/* the type a document is opened, shared and downloaded with: the row's own MIME only when it is one its extension allows (and not a bare zip type),
   else that extension's usual one, and octet-stream for any other extension. */
fun fileOpenMime(meta: Obj?): String {
    val ty = FILE_TYPES[fileExt(meta?.get("name"))] ?: return "application/octet-stream"
    val m = mimeBase(meta?.get("mime"))
    return if (m in ty.mime && !m.contains("zip")) m else ty.mime[0]
}
fun fileFamily(meta: Obj?): String = FILE_TYPES[fileExt(meta?.get("name"))]?.family ?: "other"

/* { ok, ext, mime, family } or { ok:false, reason:'type'|'mismatch'|'empty' }. A camera photo can come
   with no extension at all; its MIME type then decides and the name gets one. */
fun fileTypeCheck(name: String?, mime: String?, size: Double): Obj {
    if (!(size > 0)) return obj("ok" to false, "reason" to "empty")
    var ext = fileExt(name)
    val m = mimeBase(mime)
    if (ext.isEmpty()) {
        ext = FILE_TYPES.keys.find { FILE_TYPES[it]!!.mime[0] == m } ?: ""
        if (ext.isEmpty()) return obj("ok" to false, "reason" to "type")
    }
    val ty = FILE_TYPES[ext] ?: return obj("ok" to false, "reason" to "type")
    if (m !in FILE_LOOSE_MIME && m !in ty.mime) return obj("ok" to false, "reason" to "mismatch")
    return obj("ok" to true, "ext" to ext, "mime" to (if (m in ty.mime && !m.contains("zip")) m else ty.mime[0]), "family" to ty.family)
}
/* does the start of the file look like what its extension says? (a renamed .exe is turned away) */
fun fileSniff(head: ByteArray?, ext: String): Boolean {
    val b = head ?: ByteArray(0)
    fun at(off: Int, a: IntArray): Boolean = a.withIndex().all { (i, v) -> off + i < b.size && (b[off + i].toInt() and 0xFF) == v }
    fun ascii(off: Int, s: String): Boolean = at(off, s.map { it.code }.toIntArray())
    when (ext) {
        "pdf" -> { var i = 0; while (i + 5 <= minOf(b.size, 1024)) { if (ascii(i, "%PDF-")) return true; i++ }; return false }
        "doc" -> return at(0, intArrayOf(0xD0, 0xCF, 0x11, 0xE0, 0xA1, 0xB1, 0x1A, 0xE1))
        "docx" -> return at(0, intArrayOf(0x50, 0x4B, 0x03, 0x04))
        "jpg", "jpeg" -> return at(0, intArrayOf(0xFF, 0xD8, 0xFF))
        "png" -> return at(0, intArrayOf(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        "gif" -> return ascii(0, "GIF87a") || ascii(0, "GIF89a")
        "webp" -> return ascii(0, "RIFF") && ascii(8, "WEBP")
        "heic", "heif" -> return ascii(4, "ftyp")
    }
    return false
}
private val FILE_BAD_RE = Regex("[\\u0000-\\u001f\\u007f<>:\"/\\\\|?*]+")
private val FILE_EDGE_RE = Regex("^[\\s\\u00a0\\u1680\\u2000-\\u200a\\u2028\\u2029\\u202f\\u205f\\u3000\\ufeff.]+|[\\s\\u00a0\\u1680\\u2000-\\u200a\\u2028\\u2029\\u202f\\u205f\\u3000\\ufeff.]+$")
private val FILE_TAIL_RE = Regex("[\\s\\u00a0\\u1680\\u2000-\\u200a\\u2028\\u2029\\u202f\\u205f\\u3000\\ufeff.]+$")
/* a name that is safe as one zip path segment and as a download name: no folders, no control or reserved characters, at most 120 characters with the extension kept */
fun fileSafeName(name: Any?): String {
    var s = FILE_EDGE_RE.replace(FILE_BAD_RE.replace(str(name), "_"), "")
    if (s.length > 120) {
        val ext = fileExt(s)
        s = FILE_TAIL_RE.replace(s.take(119 - ext.length), "") + (if (ext.isNotEmpty()) ".$ext" else "")
    }
    return s.ifEmpty { "file" }
}
fun fileSizeLabel(bytes: Double): String {
    val v = maxOf(0.0, if (bytes.isNaN()) 0.0 else bytes)
    if (v < 1024) return jsNumber(v) + " B"
    if (v < 1024 * 1024) return jsNumber(jsRound(v / 1024)) + " KB"
    val mb = v / 1048576
    return (if (mb < 10) java.math.BigDecimal(mb).setScale(1, java.math.RoundingMode.HALF_UP).toPlainString() else jsNumber(jsRound(mb))) + " MB"
}
