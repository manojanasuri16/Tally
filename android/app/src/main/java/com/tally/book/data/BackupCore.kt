package com.tally.book.data

import com.tally.book.core.*
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.time.LocalDate
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/*
 * Backups, the plain JVM part (docs/DESIGN.md, "Backups"): making a backup file from the book file and the files directory,
 * checking one, and putting one in place. No Android here (java.io and java.util.zip only), so all of it runs in JVM tests;
 * data/Backups.kt adds the folder, the schedule, the share sheet and the app's locks.
 *
 * The file, Tally-YYYY-MM-DD.tally-backup, is a zip with every entry stored (not compressed):
 *   book.json          the book exactly as stored on disk: plain JSON, or the lock's sealed envelope { enc, s, i, d, t, l }
 *   files/<id>.bin     each stored file exactly as on disk (the original bytes, or AES-GCM under DB.filesKey while a lock is set)
 *   files/<id>.json    its record ({ id, enc, iv, ts, mime, size }), as on disk
 *   manifest.json      written last: { format: "tally-backup", version: 1, app, createdAt, date, sealed, counts?, entries: [ { name, size, sha256 } ] }
 * counts ({ loans, payments, files }) are there only when the book is plain. A sealed book's files key is inside the seal, so a
 * sealed backup opens on a new phone with the PIN alone.
 *
 * Reading follows android.util.AtomicFile: a "<name>.bak" left by an interrupted write is the good copy; "<name>.new" is a
 * write that never finished and is ignored.
 */

/** Why a backup could not be checked or restored. code: notBackup | newer | damaged | tooBig | space | failed. */
class BackupError(val code: String) : Exception(code)

object BackupFormat {
    const val FORMAT = "tally-backup"
    const val VERSION = 1
    const val EXT = ".tally-backup"
    const val MANIFEST = "manifest.json"
    const val BOOK = "book.json"
    const val FILES = "files/"
    /** How many daily files the folder keeps. */
    const val KEEP = 14

    const val MAX_MANIFEST = 8L shl 20
    const val MAX_BOOK = 64L shl 20
    /** A stored file is at most 4 MB plus a little (a sealed file, or one a family member's phone stored); 16 MB leaves room. */
    const val MAX_FILE = 16L shl 20
    const val MAX_ENTRIES = 20_000
    const val MAX_TOTAL = 2L shl 30

    private val NAME = Regex("^Tally-(\\d{4}-\\d{2}-\\d{2})\\.tally-backup$")
    private val PARTIAL = Regex("^Tally-\\d{4}-\\d{2}-\\d{2}\\.tally-backup\\.partial$")
    /** A stored file's name in files/: the FileStore's safe id and .bin or .json. */
    val STORED = Regex("^[A-Za-z0-9_-]{1,120}\\.(bin|json)$")

    fun fileName(day: LocalDate): String = "Tally-$day$EXT"
    /** The name a backup is written under before it is complete. */
    fun partialName(day: LocalDate): String = fileName(day) + ".partial"
    fun isPartial(name: String): Boolean = PARTIAL.matches(name)

    /** The day in one of Tally's own backup names, or null for any other name. */
    fun dayOf(name: String): LocalDate? = NAME.matchEntire(name)?.let { runCatching { LocalDate.parse(it.groupValues[1]) }.getOrNull() }

    /** Which of [names] to delete so that only the newest [keep] of Tally's own backups stay. Other names are never touched. */
    fun toPrune(names: List<String>, keep: Int = KEEP): List<String> =
        names.mapNotNull { n -> dayOf(n)?.let { n to it } }.sortedByDescending { it.second }.drop(keep).map { it.first }

    /** Of the names in the folder, the one a new backup made on [day] replaces (today's earlier one): one backup a day. */
    fun replacedBy(names: List<String>, day: LocalDate): List<String> = names.filter { it == fileName(day) }

    /** Whether the daily job still has to run today, given the day the last backup worked ("YYYY-MM-DD", or null for never). */
    fun dueToday(lastDay: String?, today: LocalDate): Boolean = lastDay != today.toString()

    fun entryNameOk(name: String): Boolean =
        name == MANIFEST || name == BOOK || (name.startsWith(FILES) && STORED.matches(name.removePrefix(FILES)))
}

/** A book file and a files directory: where the app keeps them, or a test's temporary folder. [work] holds restore staging and the safety copy. */
class BackupPlace(val book: File, val filesDir: File, val work: File) {
    val safety: File get() = File(work, "before-restore" + BackupFormat.EXT)
    internal val staging: File get() = File(work, "staging")
    internal val oldFiles: File get() = File(work, "old-files")
    internal val journal: File get() = File(work, "commit")
}

object BackupCore {
    private const val BUF = 64 * 1024

    fun sha256Hex(b: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(b))
    private fun hex(d: ByteArray) = d.joinToString("") { "%02x".format(it) }

    /** A file's bytes as AtomicFile would read them (its .bak when an interrupted write left one), or null when there is none. */
    fun storedBytes(f: File): ByteArray? {
        val bak = File(f.path + ".bak")
        val src = if (bak.exists()) bak else f
        return if (src.isFile) src.readBytes() else null
    }

    /** The record ids in a files directory (from <id>.bin / <id>.json and their .bak copies), sorted. */
    fun storedIds(filesDir: File): List<String> {
        val names = filesDir.list() ?: return emptyList()
        return names.map { it.removeSuffix(".bak") }.filter { BackupFormat.STORED.matches(it) }
            .map { it.substringBeforeLast('.') }.distinct().sorted()
    }

    /** What a plain book holds, for the manifest and the restore question (tombstones not counted). */
    fun counts(book: Map<String, Any?>): Obj {
        fun live(k: String) = book.objs(k).count { !it.b("deleted") }
        return obj("loans" to live("loans"), "payments" to live("payments"), "files" to live("files"))
    }

    /** A plain Tally book: a JSON object with a number `v`, and lists where the lists belong. */
    fun isBook(o: Map<String, Any?>?): Boolean {
        if (o == null || o["v"] !is Double || o.b("enc")) return false
        return listOf("loans", "payments", "files", "members").all { o[it] == null || o[it] is List<*> }
    }

    // ---------- making ----------

    /**
     * Writes a backup of [book] (the stored book's bytes) and every stored file in [filesDir] to [out]. Each record's two files are
     * read together under [fileLock] (the FileStore's), one record at a time, so memory holds one file at most. A stored file
     * bigger than [BackupFormat.MAX_FILE] (none should be) is left out and counted in the manifest's `skipped`.
     * Returns the manifest. [out] is not closed.
     */
    fun write(out: OutputStream, book: ByteArray, filesDir: File, fileLock: Any, app: String, createdAt: Long, day: LocalDate): Obj {
        val entries = arr()
        val zip = ZipOutputStream(out)
        fun put(name: String, data: ByteArray) {
            val crc = CRC32().apply { update(data) }
            val e = ZipEntry(name).apply { method = ZipEntry.STORED; size = data.size.toLong(); compressedSize = data.size.toLong(); this.crc = crc.value; time = createdAt }
            zip.putNextEntry(e); zip.write(data); zip.closeEntry()
            entries.add(obj("name" to name, "size" to data.size, "sha256" to sha256Hex(data)))
        }
        put(BackupFormat.BOOK, book)
        var skipped = 0
        for (id in storedIds(filesDir)) {
            val pair = synchronized(fileLock) { storedBytes(File(filesDir, "$id.bin")) to storedBytes(File(filesDir, "$id.json")) }
            for ((ext, data) in listOf("bin" to pair.first, "json" to pair.second)) {
                if (data == null) continue
                if (data.size > BackupFormat.MAX_FILE) { skipped++; continue }
                put("${BackupFormat.FILES}$id.$ext", data)
            }
        }
        val parsed = runCatching { JsonCodec.parseObj(String(book, Charsets.UTF_8)) }.getOrNull()
        val sealed = parsed?.b("enc") == true
        val manifest = obj(
            "format" to BackupFormat.FORMAT, "version" to BackupFormat.VERSION, "app" to app,
            "createdAt" to createdAt.toDouble(), "date" to day.toString(), "sealed" to sealed,
        )
        if (!sealed && parsed != null) manifest["counts"] = counts(parsed)
        if (skipped > 0) manifest["skipped"] = skipped
        manifest["entries"] = entries
        val m = JsonCodec.stringify(manifest).toByteArray(Charsets.UTF_8)
        val crc = CRC32().apply { update(m) }
        zip.putNextEntry(ZipEntry(BackupFormat.MANIFEST).apply { method = ZipEntry.STORED; size = m.size.toLong(); compressedSize = m.size.toLong(); this.crc = crc.value; time = createdAt })
        zip.write(m); zip.closeEntry()
        zip.finish(); zip.flush()
        return manifest
    }

    /** [write] into [dest] through a temporary file next to it, so [dest] is either the old file or a complete new one. */
    fun writeFile(dest: File, book: ByteArray, filesDir: File, fileLock: Any, app: String, createdAt: Long, day: LocalDate): Obj {
        dest.parentFile?.mkdirs()
        val tmp = File(dest.path + ".tmp")
        try {
            val m = FileOutputStream(tmp).use { fo -> write(fo.buffered(BUF), book, filesDir, fileLock, app, createdAt, day).also { fo.flush(); fo.fd.sync() } }
            if (!tmp.renameTo(dest)) throw BackupError("failed")
            return m
        } finally { tmp.delete() }
    }

    // ---------- sealing an older backup again (L3) ----------

    /**
     * The book of an older backup as plain JSON, when it should be sealed again under the current lock: a plain backup, or one
     * sealed by an earlier lock of this book that [openKnown] can still open (a version 1 seal, an earlier PIN). Null when it is
     * already sealed by the current lock ([currentId], the envelope's `s`) or by a lock this phone does not know.
     */
    fun resealText(checked: Checked, currentId: String?, openKnown: (Obj) -> String?): String? {
        if (!checked.sealed) return String(checked.book, Charsets.UTF_8)
        val env = checked.envelope ?: return null
        if (Crypto.version(env) >= Crypto.VERSION && currentId != null && env.s("s") == currentId) return null
        return openKnown(env)?.takeIf { isBook(runCatching { JsonCodec.parseObj(it) }.getOrNull()) }
    }

    /**
     * Writes the checked backup [checked] (from [file]) again to [out] with [bookText] (its book as plain JSON) sealed by
     * [sealBook]. Files stored plain are sealed under the book's files key (made, and put in the book, when it has none), as
     * FileStore seals them (AES-GCM, the file id as additional data); files already sealed are copied byte for byte. A file's
     * bytes without their record are left out. The manifest keeps the backup's app, createdAt and date, so it is the same backup,
     * now sealed. Returns the new manifest. [out] is not closed.
     */
    fun reseal(file: File, checked: Checked, bookText: String, sealBook: (String) -> ByteArray, out: OutputStream): Obj {
        val book = JsonCodec.parseObj(bookText)
        val names = checked.fileEntries.toSet()
        val ids = names.map { it.removePrefix(BackupFormat.FILES).substringBeforeLast('.') }.distinct().sorted()
        ZipFile(file).use { z ->
            val metas = LinkedHashMap<String, Obj>()
            for (id in ids) {
                val m = "${BackupFormat.FILES}$id.json"
                if (m in names) metas[id] = runCatching { JsonCodec.parseObj(String(read(z, m, BackupFormat.MAX_FILE), Charsets.UTF_8)) }.getOrElse { throw BackupError("damaged") }
            }
            var fk = book.s("filesKey").takeIf { it.isNotEmpty() }?.let { Crypto.unb64(it) }
            if (fk == null && metas.values.any { !it.b("enc") }) {
                fk = Crypto.randomBytes(32); book["filesKey"] = Crypto.b64(fk)
            }
            // the book's own file rows say which are sealed (FileStore keeps them in step too)
            val nowSealed = metas.values.filter { !it.b("enc") }.map { it.s("id") }.toSet()
            book.objs("files").forEach { if (it.s("id") in nowSealed) it["enc"] = true }

            val entries = arr()
            val zip = ZipOutputStream(out)
            fun put(name: String, data: ByteArray) {
                val crc = CRC32().apply { update(data) }
                zip.putNextEntry(ZipEntry(name).apply { method = ZipEntry.STORED; size = data.size.toLong(); compressedSize = data.size.toLong(); this.crc = crc.value; time = checked.createdAt })
                zip.write(data); zip.closeEntry()
                entries.add(obj("name" to name, "size" to data.size, "sha256" to sha256Hex(data)))
            }
            put(BackupFormat.BOOK, sealBook(JsonCodec.stringify(book)))
            for (id in ids) {
                val meta = metas[id] ?: continue      // bytes without a record: not usable, and maybe plain: left out
                val binName = "${BackupFormat.FILES}$id.bin"
                val bin = if (binName in names) read(z, binName, BackupFormat.MAX_FILE) else null
                if (meta.b("enc") || bin == null) {
                    if (bin != null) put(binName, bin)
                    put("${BackupFormat.FILES}$id.json", JsonCodec.stringify(meta).toByteArray(Charsets.UTF_8))
                    continue
                }
                val iv = Crypto.randomBytes(12)
                val ct = Crypto.encrypt(fk!!, iv, bin, "tally-file:${meta.s("id").ifEmpty { id }}".toByteArray(Charsets.UTF_8))
                val m2 = meta.copyObj().apply { this["enc"] = 1; this["iv"] = Crypto.b64(iv); this["mime"] = ""; this["size"] = ct.size }
                put(binName, ct)
                put("${BackupFormat.FILES}$id.json", JsonCodec.stringify(m2).toByteArray(Charsets.UTF_8))
            }
            val manifest = obj(
                "format" to BackupFormat.FORMAT, "version" to BackupFormat.VERSION, "app" to checked.manifest.s("app"),
                "createdAt" to checked.manifest.num("createdAt"), "date" to checked.date, "sealed" to true,
            )
            checked.manifest["skipped"]?.let { manifest["skipped"] = it }
            manifest["entries"] = entries
            val mb = JsonCodec.stringify(manifest).toByteArray(Charsets.UTF_8)
            val crc = CRC32().apply { update(mb) }
            zip.putNextEntry(ZipEntry(BackupFormat.MANIFEST).apply { method = ZipEntry.STORED; size = mb.size.toLong(); compressedSize = mb.size.toLong(); this.crc = crc.value; time = checked.createdAt })
            zip.write(mb); zip.closeEntry()
            zip.finish(); zip.flush()
            return manifest
        }
    }

    // ---------- checking ----------

    /** A backup that passed every check. [book] is book.json's bytes; [plain] the parsed book when not sealed, [envelope] when sealed. */
    class Checked(val manifest: Obj, val book: ByteArray, val sealed: Boolean, val plain: Obj?, val envelope: Obj?, val fileEntries: List<String>) {
        val createdAt: Long get() = manifest.num("createdAt").toLong()
        val date: String get() = manifest.s("date")
    }

    /**
     * Checks everything before anything is touched: a zip; only known entry names (no paths, no traversal, no directories, no
     * duplicates); a manifest of this format and version; every entry listed with its size and SHA-256, and every listed entry
     * present and matching; sizes within the caps; a book that is a sealed envelope or a Tally book, as the manifest says.
     */
    fun check(file: File): Checked {
        if (!file.isFile) throw BackupError("notBackup")
        if (file.length() > BackupFormat.MAX_TOTAL) throw BackupError("tooBig")
        val zf = try { ZipFile(file) } catch (e: Exception) { throw BackupError("notBackup") }
        zf.use { z ->
            val names = LinkedHashSet<String>()
            try {
                val all = z.entries()
                while (all.hasMoreElements()) {
                    val e = all.nextElement()
                    if (names.size >= BackupFormat.MAX_ENTRIES) throw BackupError("tooBig")
                    if (e.isDirectory || !BackupFormat.entryNameOk(e.name) || !names.add(e.name)) throw BackupError("damaged")
                }
            } catch (e: BackupError) { throw e } catch (e: Exception) { throw BackupError("damaged") }
            if (BackupFormat.MANIFEST !in names) throw BackupError("notBackup")
            val manifest = runCatching { JsonCodec.parseObj(String(read(z, BackupFormat.MANIFEST, BackupFormat.MAX_MANIFEST), Charsets.UTF_8)) }
                .getOrElse { throw if (it is BackupError && it.code == "tooBig") it else BackupError("notBackup") }
            if (manifest["format"] != BackupFormat.FORMAT) throw BackupError("notBackup")
            val version = manifest["version"] as? Double ?: throw BackupError("notBackup")
            if (version > BackupFormat.VERSION) throw BackupError("newer")
            if (version != BackupFormat.VERSION.toDouble()) throw BackupError("notBackup")
            if (manifest["createdAt"] !is Double || manifest["sealed"] !is Boolean) throw BackupError("damaged")

            val listed = LinkedHashMap<String, Obj>()
            for (e in manifest.a("entries") ?: throw BackupError("damaged")) {
                val o = e.asObj() ?: throw BackupError("damaged")
                val name = o["name"] as? String ?: throw BackupError("damaged")
                if (name == BackupFormat.MANIFEST || !BackupFormat.entryNameOk(name) || listed.put(name, o) != null) throw BackupError("damaged")
                if (o["sha256"] !is String || o["size"] !is Double) throw BackupError("damaged")
            }
            if (listed.keys != names - BackupFormat.MANIFEST) throw BackupError("damaged")
            if (BackupFormat.BOOK !in listed) throw BackupError("damaged")

            var book: ByteArray? = null
            for ((name, want) in listed) {
                val cap = if (name == BackupFormat.BOOK) BackupFormat.MAX_BOOK else BackupFormat.MAX_FILE
                if (want.num("size") > cap) throw BackupError("tooBig")
                if (name == BackupFormat.BOOK) {
                    val b = read(z, name, cap)
                    if (b.size.toDouble() != want.num("size") || sha256Hex(b) != want.s("sha256")) throw BackupError("damaged")
                    book = b
                } else {
                    val (size, sha) = digest(z, name, cap)
                    if (size.toDouble() != want.num("size") || sha != want.s("sha256")) throw BackupError("damaged")
                }
            }
            val bookBytes = book!!
            val parsed = runCatching { JsonCodec.parseObj(String(bookBytes, Charsets.UTF_8)) }.getOrElse { throw BackupError("damaged") }
            val sealed = parsed.b("enc")
            if (sealed != manifest.b("sealed")) throw BackupError("damaged")
            if (sealed) {
                if (listOf("s", "i", "d").any { parsed[it] !is String }) throw BackupError("damaged")
            } else if (!isBook(parsed)) throw BackupError("notBackup")
            return Checked(manifest, bookBytes, sealed, if (sealed) null else parsed, if (sealed) parsed else null,
                listed.keys.filter { it.startsWith(BackupFormat.FILES) })
        }
    }

    private fun stream(z: ZipFile, name: String): InputStream =
        z.getInputStream(z.getEntry(name) ?: throw BackupError("damaged")) ?: throw BackupError("damaged")

    /** An entry's bytes, refusing more than [cap] whatever its header says. */
    private fun read(z: ZipFile, name: String, cap: Long): ByteArray = try {
        stream(z, name).use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(BUF); var total = 0L
            while (true) {
                val r = input.read(buf); if (r < 0) break
                total += r; if (total > cap) throw BackupError("tooBig")
                out.write(buf, 0, r)
            }
            out.toByteArray()
        }
    } catch (e: BackupError) { throw e } catch (e: Exception) { throw BackupError("damaged") }

    /** An entry's size and SHA-256, streamed (never held whole), refusing more than [cap]. */
    private fun digest(z: ZipFile, name: String, cap: Long, copyTo: OutputStream? = null): Pair<Long, String> = try {
        stream(z, name).use { input ->
            val md = MessageDigest.getInstance("SHA-256")
            val buf = ByteArray(BUF); var total = 0L
            while (true) {
                val r = input.read(buf); if (r < 0) break
                total += r; if (total > cap) throw BackupError("tooBig")
                md.update(buf, 0, r); copyTo?.write(buf, 0, r)
            }
            total to hex(md.digest())
        }
    } catch (e: BackupError) { throw e } catch (e: Exception) { throw BackupError("damaged") }

    // ---------- putting it in place ----------

    /**
     * Puts the checked backup [checked] (from [file]) in place at [place], with [book] as the book's bytes (normally checked.book;
     * a plain backup restored onto a locked phone passes it already sealed with that phone's lock).
     *  1. With [keepSafety], the current book and files are written as one backup file, [BackupPlace.safety] (replacing the last one).
     *  2. The files are unpacked into a staging folder (each checked against its SHA-256 again) with the book next to them.
     *  3. A journal file marks the staging as complete, and the files folder and the book are swapped in ([commit]).
     * Any failure before the swap leaves the book and files as they were; a process that dies during the swap finishes it on
     * the next start ([recover]). The caller holds the app's locks so nothing writes meanwhile.
     */
    fun install(file: File, checked: Checked, book: ByteArray, place: BackupPlace, keepSafety: Boolean, app: String, now: Long, day: LocalDate, fileLock: Any = Any()) {
        place.work.mkdirs()
        val need = file.length() + book.size + (8L shl 20) + (if (keepSafety) dirSize(place.filesDir) + place.book.length() else 0L)
        if (place.work.usableSpace in 1 until need) throw BackupError("space")
        // the safety copy is made first but takes the place of the last one only once the new book is staged
        val nextSafety = File(place.work, "before-restore.next")
        try {
            nextSafety.delete()
            val current = if (keepSafety) storedBytes(place.book) else null
            if (current != null) writeFile(nextSafety, current, place.filesDir, fileLock, app, now, day)
            val st = place.staging
            st.deleteRecursively()
            val stFiles = File(st, "files")
            if (!stFiles.mkdirs()) throw BackupError("failed")
            val wanted = checked.manifest.objs("entries").associateBy { it.s("name") }
            ZipFile(file).use { z ->
                for (name in checked.fileEntries) {
                    val want = wanted.getValue(name)
                    val dest = File(stFiles, name.removePrefix(BackupFormat.FILES))
                    val (size, sha) = FileOutputStream(dest).use { fo -> digest(z, name, BackupFormat.MAX_FILE, fo).also { fo.flush(); fo.fd.sync() } }
                    if (size.toDouble() != want.num("size") || sha != want.s("sha256")) throw BackupError("damaged")
                }
            }
            FileOutputStream(File(st, BackupFormat.BOOK)).use { it.write(book); it.flush(); it.fd.sync() }
            if (keepSafety) {
                if (current == null) place.safety.delete()
                else if (!nextSafety.renameTo(place.safety)) throw BackupError("failed")
            }
            FileOutputStream(place.journal).use { it.write("commit".toByteArray()); it.flush(); it.fd.sync() }
        } catch (e: Exception) {
            nextSafety.delete(); place.journal.delete(); place.staging.deleteRecursively()
            throw e as? BackupError ?: BackupError(if (place.work.usableSpace in 1 until need) "space" else "failed")
        }
        commit(place)
    }

    /** Finishes an install whose journal is in place. Each step can be repeated, so a commit cut short is finished by running it again. */
    private fun commit(place: BackupPlace) {
        val stFiles = File(place.staging, "files")
        val stBook = File(place.staging, BackupFormat.BOOK)
        if (stFiles.isDirectory) {
            if (place.filesDir.exists()) {
                place.oldFiles.deleteRecursively()
                if (!place.filesDir.renameTo(place.oldFiles)) { abandon(place); throw BackupError("failed") }
            }
            if (!stFiles.renameTo(place.filesDir)) {
                // put the old folder back (over an empty one something may have made meanwhile) and give up: nothing changed
                if (place.filesDir.list()?.isEmpty() == true) place.filesDir.delete()
                if (!place.filesDir.exists()) place.oldFiles.renameTo(place.filesDir)
                abandon(place); throw BackupError("failed")
            }
        }
        if (stBook.isFile) {
            // a leftover AtomicFile backup or half-written new file would be read instead of (or after) the restored book
            File(place.book.path + ".bak").delete(); File(place.book.path + ".new").delete()
            place.book.parentFile?.mkdirs()
            if (!stBook.renameTo(place.book)) {
                // the files are already swapped: the book must follow (the journal stays until it has, and the next start retries)
                place.book.writeBytes(stBook.readBytes())
                stBook.delete()
            }
        }
        place.journal.delete()
        place.oldFiles.deleteRecursively()
        place.staging.deleteRecursively()
    }

    private fun abandon(place: BackupPlace) { place.journal.delete(); place.staging.deleteRecursively() }

    /** At start, before the book or files are read: finishes a restore that was cut short after its journal was written, or tidies up. */
    fun recover(place: BackupPlace) {
        if (place.journal.exists()) runCatching { commit(place) }
        else { place.staging.deleteRecursively(); place.oldFiles.deleteRecursively() }
    }

    private fun dirSize(d: File): Long = d.listFiles()?.sumOf { if (it.isFile) it.length() else 0L } ?: 0L
}
