package com.tally.book

import com.tally.book.core.*
import com.tally.book.data.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDate
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.random.Random

class BackupCoreTest {
    @get:Rule val tmp = TemporaryFolder()
    private val day = LocalDate.of(2026, 11, 11)
    private val now = 1_794_380_400_000L

    @Before fun setUp() { TestClock.start(); Crypto.clearLock() }
    @After fun tearDown() { TestClock.stop(); Crypto.clearLock() }

    private fun place(name: String): BackupPlace {
        val root = tmp.newFolder(name)
        return BackupPlace(File(root, "ledger.json"), File(root, "files").apply { mkdirs() }, File(root, "restore"))
    }

    private fun book(vararg loans: String): Obj = obj(
        "v" to 1, "me" to obj("id" to "m1", "name" to "Ravi"),
        "loans" to arr(*loans.map { obj("id" to it, "name" to "Loan $it", "kind" to "bank") }.toTypedArray()),
        "payments" to arr(obj("id" to "p1", "loanId" to "l1", "amount" to 500), obj("id" to "p2", "loanId" to "l1", "deleted" to true)),
        "files" to arr(), "members" to arr(),
    )

    private fun putFile(p: BackupPlace, id: String, data: ByteArray, enc: Boolean = false) {
        File(p.filesDir, "$id.bin").writeBytes(data)
        File(p.filesDir, "$id.json").writeText(JsonCodec.stringify(obj("id" to id, "enc" to if (enc) 1 else 0, "iv" to "", "ts" to 1, "mime" to "image/jpeg", "size" to data.size)))
    }

    private fun backup(p: BackupPlace, into: File = File(tmp.root, "b-" + Random.nextInt(1 shl 30) + BackupFormat.EXT)): File {
        BackupCore.writeFile(into, BackupCore.storedBytes(p.book)!!, p.filesDir, Any(), "test", now, day)
        return into
    }

    private fun filesOf(p: BackupPlace): Map<String, String> =
        (p.filesDir.listFiles() ?: emptyArray()).associate { it.name to BackupCore.sha256Hex(it.readBytes()) }

    // ---------- the zip ----------

    @Test fun roundTripKeepsEveryByteIncludingEmptyAndLargeFiles() {
        val a = place("a")
        a.book.writeText(JsonCodec.stringify(book("l1", "l2")))
        val big = Random(7).nextBytes(5 * 1048576 + 3)
        putFile(a, "f-big", big)
        putFile(a, "f-empty", ByteArray(0))
        File(a.filesDir, "stray.txt").writeText("not a record")          // never copied
        File(a.filesDir, "f-new.bin.new").writeText("half a write")      // an unfinished AtomicFile write: ignored

        val file = backup(a)
        val c = BackupCore.check(file)
        assertFalse(c.sealed)
        assertEquals(now, c.createdAt)
        assertEquals("2026-11-11", c.date)
        assertEquals(obj("loans" to 2, "payments" to 1, "files" to 0), c.manifest.o("counts"))
        assertEquals(setOf("files/f-big.bin", "files/f-big.json", "files/f-empty.bin", "files/f-empty.json"), c.fileEntries.toSet())

        val b = place("b")
        b.book.writeText(JsonCodec.stringify(book("x")))
        putFile(b, "f-old", byteArrayOf(9))
        BackupCore.install(file, c, c.book, b, keepSafety = false, app = "test", now = now, day = day)
        assertArrayEquals(a.book.readBytes(), b.book.readBytes())
        assertArrayEquals(big, File(b.filesDir, "f-big.bin").readBytes())
        assertEquals(0L, File(b.filesDir, "f-empty.bin").length())
        assertFalse(File(b.filesDir, "f-old.bin").exists())                // the whole set is replaced
        assertEquals(filesOf(a) - "stray.txt" - "f-new.bin.new", filesOf(b))
        assertFalse(b.staging.exists()); assertFalse(b.journal.exists()); assertFalse(b.oldFiles.exists())
    }

    @Test fun anInterruptedWriteIsReadFromItsBak() {
        val a = place("a")
        a.book.writeText("{\"v\":1,\"lo")                                  // half written
        File(a.book.path + ".bak").writeText(JsonCodec.stringify(book("l1")))
        putFile(a, "f1", byteArrayOf(1, 2))
        File(a.filesDir, "f1.bin.bak").writeBytes(byteArrayOf(3, 4))
        val c = BackupCore.check(backup(a))
        assertEquals(1, c.plain!!.objs("loans").size)
        val b = place("b")
        BackupCore.install(backup(a), c, c.book, b, false, "test", now, day)
        assertArrayEquals(byteArrayOf(3, 4), File(b.filesDir, "f1.bin").readBytes())
        assertEquals(listOf("f1"), BackupCore.storedIds(a.filesDir))
    }

    // ---------- checking ----------

    private fun zip(entries: List<Pair<String, ByteArray>>): File {
        val bo = ByteArrayOutputStream()
        ZipOutputStream(bo).use { z ->
            for ((name, data) in entries) {
                val crc = CRC32().apply { update(data) }
                z.putNextEntry(ZipEntry(name).apply { method = ZipEntry.STORED; size = data.size.toLong(); compressedSize = size; this.crc = crc.value })
                z.write(data); z.closeEntry()
            }
        }
        return tmp.newFile().apply { writeBytes(bo.toByteArray()) }
    }

    private fun manifest(entries: List<Pair<String, ByteArray>>, change: (Obj) -> Unit = {}): Pair<String, ByteArray> {
        val m = obj("format" to "tally-backup", "version" to 1, "app" to "test", "createdAt" to now.toDouble(), "date" to "2026-11-11", "sealed" to false,
            "entries" to arr(*entries.map { (n, d) -> obj("name" to n, "size" to d.size, "sha256" to BackupCore.sha256Hex(d)) }.toTypedArray()))
        change(m)
        return "manifest.json" to JsonCodec.stringify(m).toByteArray()
    }

    private fun code(f: File): String = try { BackupCore.check(f); "ok" } catch (e: BackupError) { e.code }

    @Test fun everyRejection() {
        val bookBytes = JsonCodec.stringify(book("l1")).toByteArray()
        val good = listOf("book.json" to bookBytes, "files/f1.bin" to byteArrayOf(1, 2, 3), "files/f1.json" to "{}".toByteArray())
        assertEquals("ok", code(zip(good + manifest(good))))

        assertEquals("notBackup", code(tmp.newFile().apply { writeBytes(Random.nextBytes(4000)) }))
        assertEquals("notBackup", code(File(tmp.root, "missing")))
        assertEquals("notBackup", code(zip(good)))                                                         // no manifest
        assertEquals("notBackup", code(zip(good + ("manifest.json" to "not json".toByteArray()))))
        assertEquals("notBackup", code(zip(good + manifest(good) { it["format"] = "balance" })))
        assertEquals("newer", code(zip(good + manifest(good) { it["version"] = 2 })))
        assertEquals("notBackup", code(zip(good + manifest(good) { it["version"] = 0 })))
        assertEquals("notBackup", code(zip(good + manifest(good) { it.remove("version") })))
        // an entry listed but missing, or present but not listed
        assertEquals("damaged", code(zip(good.dropLast(1) + manifest(good))))
        assertEquals("damaged", code(zip(good + ("files/f2.bin" to byteArrayOf(1)) + manifest(good))))
        // a wrong hash or size
        val tampered = good.toMutableList().also { it[1] = "files/f1.bin" to byteArrayOf(1, 2, 4) }
        assertEquals("damaged", code(zip(tampered + manifest(good))))
        assertEquals("damaged", code(zip(good + manifest(good) { m -> m.objs("entries")[1]["size"] = 4 })))
        // the book must be there
        val noBook = good.drop(1)
        assertEquals("damaged", code(zip(noBook + manifest(noBook))))
        // names: traversal, absolute, nested, unknown, directories, duplicates in the manifest
        for (bad in listOf("../evil.bin", "files/../f.bin", "/etc/passwd", "files/sub/f.bin", "files/f 1.bin", "files/f.exe", "notes.txt", "files/", "Book.json")) {
            val e = good + (bad to byteArrayOf(1))
            assertEquals(bad, "damaged", code(zip(e + manifest(e))))
        }
        assertEquals("damaged", code(zip(good + manifest(good) { m -> m.aa("entries").add(m.objs("entries")[1].copyObj()) })))
        // too big: declared or real
        assertEquals("tooBig", code(zip(good + manifest(good) { m -> m.objs("entries")[1]["size"] = BackupFormat.MAX_FILE + 1 })))
        val huge = good + ("files/huge.bin" to ByteArray((BackupFormat.MAX_FILE + 1).toInt()))
        assertEquals("tooBig", code(zip(huge + manifest(huge))))
        // the book: sealed must match the manifest, and a plain one must be a Tally book
        assertEquals("damaged", code(zip(good + manifest(good) { it["sealed"] = true })))
        val notTally = listOf("book.json" to "{\"months\":{}}".toByteArray())
        assertEquals("notBackup", code(zip(notTally + manifest(notTally))))
        val notJson = listOf("book.json" to "hello".toByteArray())
        assertEquals("damaged", code(zip(notJson + manifest(notJson))))
        val badEnvelope = listOf("book.json" to "{\"enc\":1,\"s\":\"x\"}".toByteArray())
        assertEquals("damaged", code(zip(badEnvelope + manifest(badEnvelope) { it["sealed"] = true })))
    }

    // ---------- plain and sealed ----------

    @Test fun sealedBackupRestoresWithThePinHereAndOnlyWithTheRecoveryCodeOnAFreshPhone() {
        // a locked phone: the book sealed under a data key (the PIN's copy bound to this phone, the recovery code's copy anywhere),
        // the files sealed with DB.filesKey, which lives inside the sealed book
        val a = place("a")
        val code = RecoveryCode.make()
        Crypto.setLock("2468", "pin", code)
        val filesKey = Crypto.randomBytes(32)
        val db = book("l1").apply { this["filesKey"] = Crypto.b64(filesKey); this["lockType"] = "pin" }
        a.book.writeText(JsonCodec.stringify(Crypto.sealText(JsonCodec.stringify(db))))
        val photo = Random(3).nextBytes(300_000)
        val iv = Crypto.randomBytes(12)
        File(a.filesDir, "f1.bin").writeBytes(Crypto.encrypt(filesKey, iv, photo, "tally-file:f1".toByteArray()))
        File(a.filesDir, "f1.json").writeText(JsonCodec.stringify(obj("id" to "f1", "enc" to 1, "iv" to Crypto.b64(iv), "ts" to 1, "mime" to "", "size" to 1)))
        val file = backup(a)
        Crypto.clearLock()
        val c = BackupCore.check(file)
        assertNotNull(Crypto.openEnvelope(c.envelope!!, "2468"))           // the phone that made it: its PIN opens it
        val home = Crypto.device
        Crypto.device = MemoryDevice()                                     // a new phone knows nothing
        try {
            assertTrue(c.sealed)
            assertNull(c.manifest["counts"])                                   // nothing readable outside the seal
            assertFalse(String(file.readBytes(), Charsets.ISO_8859_1).contains("Loan l1"))
            assertNull(Crypto.openEnvelope(c.envelope!!, "1357"))             // a wrong PIN opens nothing
            assertNull(Crypto.openEnvelope(c.envelope!!, "2468"))             // and neither does the right one, off that phone
            assertFalse(Crypto.pinFits(c.envelope!!))
            assertFalse(Crypto.locked)                                         // and changes nothing
            checkOpened(file, c, a, photo, Crypto.openEnvelope(c.envelope!!, code, viaCode = true)!!)
        } finally { Crypto.device = home }
    }

    private fun checkOpened(file: File, c: BackupCore.Checked, a: BackupPlace, photo: ByteArray, opened: Crypto.Opened) {
        assertEquals(4, opened.pinLen); assertEquals("pin", opened.type)
        val back = JsonCodec.parseObj(opened.text)
        assertEquals("Loan l1", back.objs("loans")[0].s("name"))

        val b = place("b")
        BackupCore.install(file, c, c.book, b, false, "test", now, day)
        val rec = JsonCodec.parseObj(File(b.filesDir, "f1.json").readText())
        val bytes = Crypto.decrypt(Crypto.unb64(back.s("filesKey")), Crypto.unb64(rec.s("iv")), File(b.filesDir, "f1.bin").readBytes(), "tally-file:f1".toByteArray())
        assertArrayEquals(photo, bytes)
        assertArrayEquals(a.book.readBytes(), b.book.readBytes())          // the envelope exactly as stored
    }

    @Test fun aWrongPinLeavesTheBookUntouched() {
        val a = place("a")
        Crypto.setLock("2468", "pin")
        a.book.writeText(JsonCodec.stringify(Crypto.sealText(JsonCodec.stringify(book("l1")))))
        Crypto.clearLock()
        val c = BackupCore.check(backup(a))
        val b = place("b")
        b.book.writeText(JsonCodec.stringify(book("mine")))
        putFile(b, "f9", byteArrayOf(9))
        val before = b.book.readBytes() to filesOf(b)
        // the flow asks for the PIN before installing; a wrong one stops there
        assertNull(Crypto.openEnvelope(c.envelope!!, "0000"))
        assertArrayEquals(before.first, b.book.readBytes()); assertEquals(before.second, filesOf(b))
    }

    // ---------- safety copy, undo, failures, recovery ----------

    @Test fun safetyCopyAndUndo() {
        val mine = place("mine")
        mine.book.writeText(JsonCodec.stringify(book("mine")))
        putFile(mine, "f-mine", byteArrayOf(1, 1, 1))
        val other = place("other")
        other.book.writeText(JsonCodec.stringify(book("old1", "old2")))
        putFile(other, "f-other", byteArrayOf(2, 2))
        val restoreFile = backup(other)
        val origBook = mine.book.readBytes(); val origFiles = filesOf(mine)

        val c = BackupCore.check(restoreFile)
        BackupCore.install(restoreFile, c, c.book, mine, keepSafety = true, app = "test", now = now, day = day)
        assertArrayEquals(other.book.readBytes(), mine.book.readBytes())
        assertTrue(mine.safety.isFile)

        // undo: the safety copy is an ordinary backup of what was there
        val s = BackupCore.check(mine.safety)
        assertArrayEquals(origBook, s.book)
        BackupCore.install(mine.safety, s, s.book, mine, keepSafety = false, app = "test", now = now, day = day)
        assertArrayEquals(origBook, mine.book.readBytes())
        assertEquals(origFiles, filesOf(mine))

        // the next restore overwrites the one safety copy
        BackupCore.install(restoreFile, c, c.book, mine, keepSafety = true, app = "test", now = now, day = day)
        assertArrayEquals(origBook, BackupCore.check(mine.safety).book)
        assertEquals(listOf("before-restore.tally-backup"), mine.work.list()!!.toList())
    }

    @Test fun aFailureBeforeTheSwapChangesNothing() {
        val mine = place("mine")
        mine.book.writeText(JsonCodec.stringify(book("mine")))
        putFile(mine, "f-mine", byteArrayOf(1))
        val earlier = place("earlier")
        earlier.book.writeText(JsonCodec.stringify(book("e")))
        val first = backup(earlier)
        BackupCore.install(first, BackupCore.check(first), BackupCore.check(first).book, mine, true, "test", now, day)
        val safetyBefore = mine.safety.readBytes()
        val bookBefore = mine.book.readBytes(); val filesBefore = filesOf(mine)

        val other = place("other")
        other.book.writeText(JsonCodec.stringify(book("o")))
        putFile(other, "f-o", Random(1).nextBytes(10_000))
        val f = backup(other)
        val c = BackupCore.check(f)
        // the file changes between the check and the install (one byte of f-o.bin): the unpacking notices
        val bytes = f.readBytes()
        val at = indexOf(bytes, File(other.filesDir, "f-o.bin").readBytes().copyOfRange(0, 64))
        bytes[at + 10] = (bytes[at + 10] + 1).toByte()
        f.writeBytes(bytes)
        val e = assertThrows(BackupError::class.java) { BackupCore.install(f, c, c.book, mine, true, "test", now, day) }
        assertTrue(e.code, e.code == "damaged" || e.code == "failed")
        assertArrayEquals(bookBefore, mine.book.readBytes())
        assertEquals(filesBefore, filesOf(mine))
        assertArrayEquals(safetyBefore, mine.safety.readBytes())           // the last safety copy is still the one
        assertFalse(mine.staging.exists()); assertFalse(mine.journal.exists())
    }

    private fun indexOf(hay: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..hay.size - needle.size) { for (j in needle.indices) if (hay[i + j] != needle[j]) continue@outer; return i }
        error("not found")
    }

    @Test fun aRestoreCutShortIsFinishedAtTheNextStart() {
        val p = place("p")
        p.book.writeText("old book")
        putFile(p, "f-old", byteArrayOf(1))
        // as install leaves it just before the swap, then the process dies after moving the old files aside
        File(p.staging, "files").mkdirs()
        File(p.staging, "files/f-new.bin").writeBytes(byteArrayOf(7))
        File(p.staging, "book.json").writeText("new book")
        p.work.mkdirs(); p.journal.writeText("commit")
        p.filesDir.renameTo(p.oldFiles)
        File(p.book.path + ".bak").writeText("an old interrupted write")
        BackupCore.recover(p)
        assertEquals("new book", p.book.readText())
        assertFalse(File(p.book.path + ".bak").exists())
        assertEquals(listOf("f-new.bin"), p.filesDir.list()!!.toList())
        assertFalse(p.journal.exists()); assertFalse(p.staging.exists()); assertFalse(p.oldFiles.exists())

        // without a journal, staging is only tidied away and nothing else changes
        File(p.staging, "files").mkdirs(); File(p.staging, "book.json").writeText("half")
        BackupCore.recover(p)
        assertEquals("new book", p.book.readText())
        assertFalse(p.staging.exists())
    }

    // ---------- the folder: names, one a day, the newest 14 ----------

    @Test fun pruningKeepsTheNewest14OfTallysOwnFiles() {
        val ours = (0 until 20).map { BackupFormat.fileName(day.minusDays(it.toLong())) }
        val foreign = listOf("notes.txt", "Tally-2026-11-11.tally-backup.partial", "tally-2026-01-01.tally-backup",
            "Tally-2026-13-40.tally-backup", "Tally-2026-11-11 (1).tally-backup", "Tally-2020-01-01.tally-backup.zip", "Photo.jpg")
        val drop = BackupFormat.toPrune((ours + foreign).shuffled(Random(5)))
        assertEquals(ours.drop(14).toSet(), drop.toSet())
        assertTrue(BackupFormat.toPrune(ours.take(14) + foreign).isEmpty())
        assertEquals(day, BackupFormat.dayOf("Tally-2026-11-11.tally-backup"))
        assertNull(BackupFormat.dayOf("Tally-2026-02-30.tally-backup"))
        assertTrue(BackupFormat.isPartial(BackupFormat.partialName(day)))
        assertFalse(BackupFormat.isPartial("Tally-2026-11-11.tally-backup"))
    }

    @Test fun oneBackupADay() {
        assertEquals("Tally-2026-11-11.tally-backup", BackupFormat.fileName(day))
        // a later backup the same day goes under the same name, so it replaces the earlier one; yesterday's is kept
        val folder = listOf(BackupFormat.fileName(day), BackupFormat.fileName(day.minusDays(1)))
        assertEquals(listOf(BackupFormat.fileName(day)), BackupFormat.replacedBy(folder, day))
        assertTrue(BackupFormat.replacedBy(listOf(BackupFormat.fileName(day.minusDays(1))), day).isEmpty())
        assertFalse(BackupFormat.dueToday("2026-11-11", day))
        assertTrue(BackupFormat.dueToday("2026-11-10", day))
        assertTrue(BackupFormat.dueToday(null, day))
    }
}
