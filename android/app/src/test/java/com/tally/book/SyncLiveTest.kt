package com.tally.book

import com.tally.book.core.*
import com.tally.book.sync.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Opt-in check against the deployed relay: runs only with TALLY_LIVE=1. The URL and publishable key come from the
 * environment (TALLY_SUPABASE_URL / TALLY_SUPABASE_ANON_KEY) or ~/.gradle/gradle.properties. What it saw is written
 * to app/build/tally-live-check.txt (no secrets), including the throwaway family ids it created.
 */
class SyncLiveTest {
    private val log = StringBuilder()
    private fun note(s: String) { log.append(s).append('\n'); println(s) }

    private fun prop(name: String): String {
        System.getenv(name)?.takeIf { it.isNotBlank() }?.let { return it }
        val f = File(System.getProperty("user.home"), ".gradle/gradle.properties")
        return f.takeIf { it.exists() }?.readLines()?.firstOrNull { it.startsWith("$name=") }?.substringAfter('=')?.trim() ?: ""
    }

    private fun raw(method: String, url: String, key: String, body: String? = null): Pair<Int, String> {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method; c.connectTimeout = 15000; c.readTimeout = 30000
        c.setRequestProperty("apikey", key); c.setRequestProperty("Authorization", "Bearer $key")
        if (body != null) { c.doOutput = true; c.setRequestProperty("Content-Type", "application/json"); c.outputStream.use { it.write(body.toByteArray()) } }
        val code = c.responseCode
        val text = (if (code >= 400) c.errorStream else c.inputStream)?.use { String(it.readBytes()) } ?: ""
        return code to text.take(300)
    }

    @Test fun liveRelay() {
        assumeTrue("set TALLY_LIVE=1 to run against the deployed relay", System.getenv("TALLY_LIVE") == "1")
        val url = prop("TALLY_SUPABASE_URL"); val key = prop("TALLY_SUPABASE_ANON_KEY")
        assumeTrue(url.isNotBlank() && key.isNotBlank())
        val relay = HttpRelay(url, key)
        try { runBlocking { check(relay, url, key) } } finally {
            File("build/tally-live-check.txt").apply { parentFile?.mkdirs() }.writeText(log.toString())
        }
    }

    private suspend fun check(relay: HttpRelay, url: String, key: String) {
        val fam = Invite.newFamilyId()
        val famKey = Invite.newKey()
        val token = SyncCrypto.authToken(famKey)
        note("throwaway family: $fam")

        val created = relay.createFamily(fam, SyncCrypto.tokenHash(token)); note("create -> $created")
        assertEquals(RelayResult.Ok(Unit), created)
        val again = relay.createFamily(fam, SyncCrypto.tokenHash(token)); note("create again, same hash -> $again")
        assertEquals(RelayResult.Ok(Unit), again)
        val hijack = relay.createFamily(fam, SyncCrypto.tokenHash("attacker")); note("create again, other hash -> $hijack")
        assertEquals(RelayResult.Err(SyncError.Unauthorised), hijack)

        val enc = SyncCrypto.encKey(famKey)
        val rows = listOf("r1", "r2", "r3").map { RelayRow(it, "payment", SyncCrypto.sealRecord(enc, fam, it, """{"k":"payment","r":{"id":"$it"}}""")) }
        val pushed = relay.push(fam, token, rows); note("push 3 -> $pushed")
        assertEquals(RelayResult.Ok(3), pushed)
        val all = (relay.pull(fam, token, 0, 500) as RelayResult.Ok).value
        note("pull since 0 -> ${all.map { it.id to it.seq }}")
        assertEquals(listOf("r1", "r2", "r3"), all.map { it.id }.sorted())
        assertEquals(all.map { it.seq }.sorted(), all.map { it.seq })
        assertEquals("""{"k":"payment","r":{"id":"r2"}}""", SyncCrypto.openRecord(enc, fam, "r2", all.first { it.id == "r2" }.blob))
        val page = (relay.pull(fam, token, all[0].seq, 1) as RelayResult.Ok).value
        note("pull since ${all[0].seq} max 1 -> ${page.map { it.id to it.seq }}")
        assertEquals(listOf(all[1].id), page.map { it.id })
        // an upsert takes a new, higher seq
        relay.push(fam, token, listOf(rows[0]))
        val after = (relay.pull(fam, token, all.last().seq, 10) as RelayResult.Ok).value
        note("re-push r1, pull since ${all.last().seq} -> ${after.map { it.id to it.seq }}")
        assertEquals(listOf("r1"), after.map { it.id })

        val wrongTok = SyncCrypto.authToken(Invite.newKey())
        note("pull, wrong token -> ${relay.pull(fam, wrongTok, 0, 10)}")
        assertEquals(RelayResult.Err(SyncError.Unauthorised), relay.pull(fam, wrongTok, 0, 10))
        assertEquals(RelayResult.Err(SyncError.Unauthorised), relay.push(fam, wrongTok, rows))
        assertEquals(RelayResult.Err(SyncError.Unauthorised), relay.blobGet(fam, wrongTok, "b1"))
        note("pull, unknown family -> ${relay.pull(Invite.newFamilyId(), token, 0, 10)}")
        assertEquals(RelayResult.Err(SyncError.Unauthorised), relay.pull(Invite.newFamilyId(), token, 0, 10))

        val badKind = relay.push(fam, token, listOf(RelayRow("r9", "evil", "x"))); note("push bad kind -> $badKind")
        assertTrue(badKind is RelayResult.Err && badKind.error is SyncError.BadData)
        val badId = relay.push(fam, token, listOf(RelayRow("../x", "loan", "x"))); note("push bad id -> $badId")
        assertTrue(badId is RelayResult.Err && badId.error is SyncError.BadData)
        val big = relay.push(fam, token, listOf(RelayRow("r9", "loan", "A".repeat(70_000)))); note("push 70k blob -> $big")
        assertTrue(big is RelayResult.Err && big.error is SyncError.BadData)

        val data = SyncCrypto.sealFile(enc, fam, "b1", ByteArray(1000) { it.toByte() })
        note("blob put -> ${relay.blobPut(fam, token, "b1", data)}")
        val got = relay.blobGet(fam, token, "b1")
        assertEquals(RelayResult.Ok(data), got)
        note("blob get -> ok, ${data.length} chars back")
        assertEquals(RelayResult.Ok(null), relay.blobGet(fam, token, "nope"))

        // the anon key reaches no table directly
        for (t in listOf("tally_records", "tally_families", "tally_blobs")) {
            val (code, text) = raw("GET", "$url/rest/v1/$t?select=*", key)
            note("GET /rest/v1/$t -> $code $text")
            assertTrue(code == 401 || code == 403 || text.trim() == "[]")
        }
        val (icode, itext) = raw("POST", "$url/rest/v1/tally_records", key, """{"family_id":"$fam","id":"x","kind":"loan","blob":"x","seq":1}""")
        note("POST /rest/v1/tally_records -> $icode $itext")
        assertTrue(icode == 401 || icode == 403)
        val (acode, atext) = raw("POST", "$url/rest/v1/rpc/tally_auth", key, """{"p_family":"$fam","p_token":"$token"}""")
        note("POST /rest/v1/rpc/tally_auth (private helper) -> $acode $atext")
        assertTrue(acode >= 400)
        val (pcode, ptext) = raw("POST", "$url/rest/v1/rpc/tally_ping", key, "{}")
        note("POST /rest/v1/rpc/tally_ping -> $pcode $ptext")
        assertEquals(200, pcode)

        // two phones through the real relay
        TestClock.start()
        try {
            val a = Phone("A", relay); val b = Phone("B", relay)
            a.loan("l1", "Live loan"); a.payment("p1", "l1", 1234); a.file("f1", "loan", "l1", byteArrayOf(5, 6, 7))
            val ra = a.engine.createFamily("Live test"); note("engine create -> $ra")
            note("engine family: ${a.db.o("family").s("id")}")
            val rb = b.engine.joinFamily(a.engine.inviteText(a.db)!!); note("engine join -> $rb")
            b.payment("p2", "l1", 99)
            note("engine B sync -> ${b.engine.sync()}"); note("engine A sync -> ${a.engine.sync()}")
            assertEquals(a.shared(), b.shared())
            assertArrayEquals(byteArrayOf(5, 6, 7), b.files.bytes["f1"])
        } finally { TestClock.stop() }
    }
}
