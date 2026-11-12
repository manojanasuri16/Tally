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
 * to app/build/tally-live-check.txt (no secrets). Every family it makes is deleted at the end by its members leaving
 * (the relay deletes a family when its last member leaves); any id it could not clean up is listed there.
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
        val cleanup = ArrayList<Cred>()
        try { runBlocking { check(relay, url, key, cleanup) } } finally {
            runBlocking {
                for (c in cleanup) {
                    val r = relay.leave(c)
                    if (r !is RelayResult.Ok && r != RelayResult.Err(SyncError.Unauthorised) && r != RelayResult.Err(SyncError.Removed)) note("cleanup ${c.familyId} ${c.memberId} -> $r (NOT cleaned)")
                    else note("cleanup ${c.familyId} ${c.memberId} -> $r")
                }
            }
            File("build/tally-live-check.txt").apply { parentFile?.mkdirs() }.writeText(log.toString())
        }
    }

    /** Back on a new phone, through the deployed relay: tally_member_reclaim, tally_join_check and the 'replaced' answer (TA007). */
    @Test fun liveRejoin() {
        assumeTrue("set TALLY_LIVE=1 to run against the deployed relay", System.getenv("TALLY_LIVE") == "1")
        val url = prop("TALLY_SUPABASE_URL"); val key = prop("TALLY_SUPABASE_ANON_KEY")
        assumeTrue(url.isNotBlank() && key.isNotBlank())
        val relay = HttpRelay(url, key)
        val cleanup = ArrayList<Cred>()
        TestClock.start()
        try { runBlocking { rejoin(relay, url, key, cleanup) } } finally {
            TestClock.stop()
            runBlocking {
                for (c in cleanup) {
                    val r = relay.leave(c)
                    if (r !is RelayResult.Ok && r != RelayResult.Err(SyncError.Unauthorised) && r != RelayResult.Err(SyncError.Removed)) note("cleanup ${c.familyId} ${c.memberId} -> $r (NOT cleaned)")
                    else note("cleanup ${c.familyId} ${c.memberId} -> $r")
                }
            }
            File("build/tally-live-rejoin.txt").apply { parentFile?.mkdirs() }.writeText(log.toString())
        }
    }

    /** Files in transit through the deployed relay: owed lists, acknowledgements, deletion, removal, "Ask for this file", the usage check. */
    @Test fun liveFiles() {
        assumeTrue("set TALLY_LIVE=1 to run against the deployed relay", System.getenv("TALLY_LIVE") == "1")
        val url = prop("TALLY_SUPABASE_URL"); val key = prop("TALLY_SUPABASE_ANON_KEY")
        assumeTrue(url.isNotBlank() && key.isNotBlank())
        val relay = HttpRelay(url, key)
        val cleanup = ArrayList<Cred>()
        TestClock.start()
        try { runBlocking { files(relay, url, key, cleanup) } } finally {
            TestClock.stop()
            runBlocking {
                for (c in cleanup) {
                    val r = relay.leave(c)
                    if (r !is RelayResult.Ok && r != RelayResult.Err(SyncError.Unauthorised) && r != RelayResult.Err(SyncError.Removed)) note("cleanup ${c.familyId} ${c.memberId} -> $r (NOT cleaned)")
                    else note("cleanup ${c.familyId} ${c.memberId} -> $r")
                }
            }
            File("build/tally-live-files.txt").apply { parentFile?.mkdirs() }.writeText(log.toString())
        }
    }

    private suspend fun files(relay: HttpRelay, url: String, key: String, cleanup: MutableList<Cred>) {
        fun credOf(p: Phone) = Cred(p.db.o("family").s("id"), p.me, p.db.o("family").o("me").s("secret"))
        suspend fun owed(p: Phone) = (relay.files(credOf(p)) as RelayResult.Ok).value.owed
        val photo = ByteArray(2000) { (it * 7).toByte() }
        val a = Phone("A", relay); val b = Phone("B", relay); val c = Phone("C", relay); val d = Phone("D", relay)
        a.loan("l1", "Live loan"); a.payment("p1", "l1", 10)
        note("A create -> ${a.engine.createFamily("Live files")}")
        cleanup.add(credOf(a))
        note("B join -> ${b.joinVia(a)}"); cleanup.add(credOf(b))
        note("C join -> ${c.joinVia(a)}"); cleanup.add(credOf(c))
        a.file("f1", "payment", "p1", photo)
        note("A sync (uploads f1) -> ${a.sync()}")
        note("owed to B ${owed(b)}, to C ${owed(c)}, to A ${owed(a)}")
        assertEquals(listOf("f1"), owed(b)); assertEquals(listOf("f1"), owed(c)); assertEquals(emptyList<String>(), owed(a))
        note("B sync -> ${b.sync()}")
        assertArrayEquals(photo, b.files.bytes["f1"])
        assertEquals(emptyList<String>(), owed(b))
        assertNotNull((relay.blobGet(credOf(a), "f1") as RelayResult.Ok).value)
        note("C sync -> ${c.sync()}")
        assertArrayEquals(photo, c.files.bytes["f1"])
        val after = relay.blobGet(credOf(a), "f1"); note("blob get after B and C collected it -> $after")
        assertEquals(RelayResult.Ok(null), after)

        // a member who joins later is not owed it, asks, and a holder (not the one who added it) sends it again
        note("D join -> ${d.joinVia(a)}"); cleanup.add(credOf(d))
        assertNull(d.files.bytes["f1"])
        assertFalse(d.engine.fetchFile("f1"))
        val asked = d.engine.askForFile("f1"); note("D asks for f1 -> $asked")
        assertEquals(FileAsk.Asked, asked)
        note("files as B -> ${relay.files(credOf(b))}")
        assertEquals(listOf("f1"), (relay.files(credOf(b)) as RelayResult.Ok).value.asks)
        assertEquals(emptyList<String>(), (relay.files(credOf(d)) as RelayResult.Ok).value.asks)   // not one's own ask
        note("B sync (offers f1 again) -> ${b.sync()}")
        assertEquals(listOf("f1"), owed(d)); assertEquals(emptyList<String>(), owed(c))
        note("D sync -> ${d.sync()}")
        assertArrayEquals(photo, d.files.bytes["f1"])
        assertEquals(RelayResult.Ok(null), relay.blobGet(credOf(a), "f1"))

        // removing a member drops what it was owed
        a.file("f2", "payment", "p1", byteArrayOf(4, 4, 4))
        note("A sync (uploads f2) -> ${a.sync()}")
        val credC = credOf(c)
        note("A removes C -> ${a.engine.removeMember(c.me)}")
        cleanup.remove(credC)
        note("B, D sync -> ${b.sync()}, ${d.sync()}")
        val gone = relay.blobGet(credOf(a), "f2"); note("f2 after B and D (C removed) -> $gone")
        assertEquals(RelayResult.Ok(null), gone)

        // the calls need a member's credential; an ask needs a file the family has
        val wrong = Cred(credOf(a).familyId, a.me, Membership.newCredential())
        assertEquals(RelayResult.Err(SyncError.Unauthorised), relay.files(wrong).also { note("files, wrong secret -> $it") })
        assertEquals(RelayResult.Err(SyncError.Unauthorised), relay.blobAck(wrong, "f1"))
        assertEquals(RelayResult.Err(SyncError.Unauthorised), relay.fileAsk(wrong, "f1"))
        assertEquals(RelayResult.Err(SyncError.Removed), relay.files(credC).also { note("files, removed C -> $it") })
        assertEquals(RelayResult.Err(SyncError.Refused), relay.fileAsk(credOf(a), "nope").also { note("ask for an unknown file -> $it") })

        // the storage check: no credentials, totals only
        val (ucode, utext) = raw("POST", "$url/rest/v1/rpc/tally_relay_usage", key, "{}")
        note("POST /rest/v1/rpc/tally_relay_usage -> $ucode $utext")
        assertEquals(200, ucode)
        assertEquals(setOf("db_bytes", "limit_bytes", "percent", "blob_bytes"), JsonCodec.parseObj(utext).keys)
        val (pcode, ptext) = raw("POST", "$url/rest/v1/rpc/tally_ping", key, "{}")
        note("POST /rest/v1/rpc/tally_ping -> $pcode $ptext")
        assertEquals(200, pcode)
    }

    private suspend fun rejoin(relay: HttpRelay, url: String, key: String, cleanup: MutableList<Cred>) {
        fun credOf(p: Phone) = Cred(p.db.o("family").s("id"), p.me, p.db.o("family").o("me").s("secret"))
        val a = Phone("A", relay); val b = Phone("B", relay)
        a.loan("l1", "Live loan")
        note("A create -> ${a.engine.createFamily("Live rejoin")}")
        cleanup.add(credOf(a))
        note("B join -> ${b.joinVia(a)}")
        val credB = credOf(b)
        b.payment("pb", "l1", 7); note("B sync -> ${b.sync()}")
        TestClock.advance(1)

        val b2 = Phone("B2", relay); b2.db.o("me")!!["name"] = "B"
        note("B2 asks, back on a new phone -> ${b2.engine.requestJoin(a.invite(), back = true)}")
        note("A sync -> ${a.sync()}; requests: ${a.engine.requests.value.map { it.name + " back=" + it.back }}")
        val req = a.engine.requests.value.single()
        assertTrue(req.back)
        note("A allows as B -> ${a.engine.approveAs(req.id, b.me)}")
        val r2 = b2.sync(); note("B2 sync -> $r2; B2 is ${b2.me}")
        assertTrue(r2 is SyncResult.Done)
        assertEquals("m-B", b2.me)
        cleanup.add(credOf(b2))
        val fam = b2.db.o("family").s("id")
        assertEquals(setOf("1", "2"), b2.db.o("family").o("keys")!!.keys)
        val oldPull = relay.pull(credB, 0, 1); note("old B phone pulls -> $oldPull")
        assertEquals(RelayResult.Err(SyncError.Replaced), oldPull)
        val oldSync = b.sync(); note("old B phone syncs -> $oldSync; gone: ${b.view.gone}")
        assertEquals(SyncResult.Replaced, oldSync)
        val st = (relay.state(credOf(b2), 0) as RelayResult.Ok).value
        note("state as B2 -> epoch ${st.epoch}, role ${st.myRole}, members ${st.members.map { it.id }}")
        assertEquals(2, st.epoch)
        b2.payment("p2", "l1", 8); note("B2 sync -> ${b2.sync()}; A sync -> ${a.sync()}")
        assertEquals("m-B", a.rec("payments", "p2")!!.s("by"))
        assertEquals(a.shared(), b2.shared())

        // a member cannot let anyone back; an unknown request says gone
        val c = Phone("C", relay); c.db.o("me")!!["name"] = "C"
        note("C asks back -> ${c.engine.requestJoin(b2.invite(), back = true)}")
        val inviteId = c.db.o("joining").s("invite")
        val asMember = relay.reclaim(credOf(b2), inviteId, a.me, 3, listOf(BoxFor(a.me, "x"), BoxFor(b2.me, "x"))); note("member B reclaims -> $asMember")
        assertEquals(RelayResult.Err(SyncError.NotAdmin), asMember)
        val noBox = relay.reclaim(credOf(a), inviteId, b2.me, 3, listOf(BoxFor(a.me, "x"))); note("reclaim without a box for the member -> $noBox")
        assertTrue(noBox is RelayResult.Err && noBox.error is SyncError.BadData)
        val ghost = relay.joinCheck(fam, Invite.idOf(Invite.newSecret()), "m-ghost", Membership.newCredential()); note("join check, unknown -> $ghost")
        assertEquals(RelayResult.Err(SyncError.InviteGone), ghost)
        val pending = relay.joinCheck(fam, inviteId, c.db.o("joining").s("as"), c.db.o("joining").o("me").s("secret")); note("join check, C waiting -> $pending")
        assertEquals("pending", (pending as RelayResult.Ok).value.status)
        note("A sync -> ${a.sync()}; deny C -> ${a.engine.deny(a.engine.requests.value.single().id)}")
        val (pcode, ptext) = raw("POST", "$url/rest/v1/rpc/tally_ping", key, "{}")
        note("POST /rest/v1/rpc/tally_ping -> $pcode $ptext")
        assertEquals(200, pcode)
        val (tcode, ttext) = raw("GET", "$url/rest/v1/tally_replaced?select=*", key)
        note("GET /rest/v1/tally_replaced -> $tcode $ttext")
        assertTrue(tcode == 401 || tcode == 403 || tcode == 404 || ttext.trim() == "[]")
    }

    private suspend fun check(relay: HttpRelay, url: String, key: String, cleanup: MutableList<Cred>) {
        // ---- the relay alone
        val fam = Invite.newFamilyId()
        val secret = Membership.newCredential(); val notice = Membership.newCredential()
        val me = Cred(fam, "m-live-a", secret)
        note("throwaway family: $fam")
        val created = relay.createFamily(fam, me.memberId, SyncCrypto.tokenHash(secret), SyncCrypto.tokenHash(notice)); note("create -> $created")
        assertEquals(RelayResult.Ok(Unit), created)
        cleanup.add(me)
        assertEquals(RelayResult.Ok(Unit), relay.createFamily(fam, me.memberId, SyncCrypto.tokenHash(secret), null).also { note("create again, same creds -> $it") })
        val hijack = relay.createFamily(fam, "m-evil", SyncCrypto.tokenHash("attacker"), null); note("create again, other member -> $hijack")
        assertEquals(RelayResult.Err(SyncError.Unauthorised), hijack)

        val st = (relay.state(me, 0) as RelayResult.Ok).value
        note("state -> epoch ${st.epoch}, role ${st.myRole}, creator ${st.creator}, members ${st.members.map { it.id to it.role }}")
        assertEquals(1, st.epoch); assertEquals("admin", st.myRole)

        val enc = SyncCrypto.encKey(SyncCrypto.randomBytes(32))
        val rows = listOf("r1", "r2", "r3").map { RelayRow(it, "payment", SyncCrypto.sealE(enc, 1, """{"k":"payment","r":{"id":"$it"}}""".toByteArray(), SyncCrypto.recordAad(fam, it))) }
        assertEquals(RelayResult.Ok(3), relay.push(me, 1, rows).also { note("push 3 at epoch 1 -> $it") })
        val stale = relay.push(me, 2, rows); note("push at epoch 2 (not the family's) -> $stale")
        assertEquals(RelayResult.Err(SyncError.Stale), stale)
        val all = (relay.pull(me, 0, 500) as RelayResult.Ok).value
        note("pull since 0 -> ${all.map { it.id to it.seq }}")
        assertEquals(listOf("r1", "r2", "r3"), all.map { it.id }.sorted())
        assertEquals(all.map { it.seq }.sorted(), all.map { it.seq })
        val wrong = Cred(fam, me.memberId, Membership.newCredential())
        note("pull, wrong secret -> ${relay.pull(wrong, 0, 10)}")
        assertEquals(RelayResult.Err(SyncError.Unauthorised), relay.pull(wrong, 0, 10))
        assertEquals(RelayResult.Err(SyncError.Unauthorised), relay.pull(Cred(Invite.newFamilyId(), me.memberId, secret), 0, 10))
        val badKind = relay.push(me, 1, listOf(RelayRow("r9", "evil", "x"))); note("push bad kind -> $badKind")
        assertTrue(badKind is RelayResult.Err && badKind.error is SyncError.BadData)
        val big = relay.push(me, 1, listOf(RelayRow("r9", "loan", "A".repeat(70_000)))); note("push 70k blob -> $big")
        assertTrue(big is RelayResult.Err && big.error is SyncError.BadData)
        val data = SyncCrypto.sealE(enc, 1, ByteArray(1000) { it.toByte() }, SyncCrypto.fileAad(fam, "b1"))
        note("blob put -> ${relay.blobPut(me, 1, "b1", data)}")
        assertEquals(RelayResult.Ok(null), relay.blobGet(me, "b1").also { note("blob get, family of one (nobody owed it, not kept) -> $it") })
        assertEquals(RelayResult.Ok(null), relay.blobGet(me, "nope"))

        // an unknown invite, and a request without one
        val noInvite = relay.joinRequest(fam, Invite.idOf(Invite.newSecret()), "m-x", SyncCrypto.tokenHash("x"), null, "blob"); note("join request, unknown invite -> $noInvite")
        assertEquals(RelayResult.Err(SyncError.InviteGone), noInvite)

        // ---- the anon key reaches no table directly, and no private helper
        for (t in listOf("tally_family", "tally_member", "tally_invite", "tally_request", "tally_box", "tally_record", "tally_blob",
                         "tally_blob_owed", "tally_file_ask",
                         "tally_families", "tally_records", "tally_blobs")) {
            val (code, text) = raw("GET", "$url/rest/v1/$t?select=*", key)
            note("GET /rest/v1/$t -> $code $text")
            assertTrue(code == 401 || code == 403 || code == 404 || text.trim() == "[]")
        }
        val (icode, itext) = raw("POST", "$url/rest/v1/tally_member", key, """{"family_id":"$fam","id":"m-evil","secret_hash":"${"0".repeat(64)}","role":"admin"}""")
        note("POST /rest/v1/tally_member -> $icode $itext")
        assertTrue(icode == 401 || icode == 403 || icode == 404)
        for (fn in listOf("tally_member_auth", "tally_admin_auth", "tally_put_boxes", "tally_new_epoch", "tally_blob_sweep")) {
            val (acode, atext) = raw("POST", "$url/rest/v1/rpc/$fn", key, """{"p_family":"$fam","p_member":"m-live-a","p_secret":"$secret"}""")
            note("POST /rest/v1/rpc/$fn (private helper) -> $acode ${atext.take(120)}")
            assertTrue(acode >= 400)
        }
        val (ocode, otext) = raw("POST", "$url/rest/v1/rpc/tally_pull", key, """{"family_id":"$fam","token":"x","since":0,"max":1}""")
        note("POST /rest/v1/rpc/tally_pull with the version-1 arguments -> $ocode ${otext.take(120)}")
        assertTrue(ocode >= 400)
        val (pcode, ptext) = raw("POST", "$url/rest/v1/rpc/tally_ping", key, "{}")
        note("POST /rest/v1/rpc/tally_ping -> $pcode $ptext")
        assertEquals(200, pcode)

        // ---- phones through the real relay
        TestClock.start()
        try {
            val a = Phone("A", relay); val b = Phone("B", relay); val c = Phone("C", relay)
            a.loan("l1", "Live loan"); a.payment("p1", "l1", 1234); a.file("f1", "loan", "l1", byteArrayOf(5, 6, 7))
            val ra = a.engine.createFamily("Live test"); note("engine create -> $ra")
            val fa = a.db.o("family").s("id")
            note("engine family: $fa")
            val credA = Cred(fa, a.me, a.db.o("family").o("me").s("secret"))
            cleanup.add(credA)
            val inviteText = a.invite()
            note("B request -> ${b.engine.requestJoin(inviteText)}")
            val n = a.notices.cred!!
            note("pending (notice credential) -> ${relay.pending(n.familyId, n.memberId, n.notice)}")
            assertEquals(RelayResult.Ok(1), relay.pending(n.familyId, n.memberId, n.notice))
            note("C request with the same (used) invite -> ${c.engine.requestJoin(inviteText)}")
            note("A sync -> ${a.sync()}; requests: ${a.engine.requests.value.map { it.name + " invited by " + it.invitedBy }}")
            note("A approve B -> ${a.engine.approve(a.engine.requests.value.single().id)}")
            note("B sync -> ${b.sync()}")
            val credB = Cred(fa, b.me, b.db.o("family").o("me").s("secret"))
            cleanup.add(credB)
            note("C join -> ${c.joinVia(a)}")
            val credC = Cred(fa, c.me, c.db.o("family").o("me").s("secret"))
            cleanup.add(credC)
            assertNull(b.files.bytes["f1"])                 // added before B joined: B is not owed it (files are in transit only)

            // role checks on the relay itself
            val (rc, rt) = raw("POST", "$url/rest/v1/rpc/tally_member_role", key,
                """{"p_family":"$fa","p_member":"${b.me}","p_secret":"${credB.secret}","p_target":"${b.me}","p_admin":true}""")
            note("member B tally_member_role, raw -> $rc ${rt.take(160)}")
            val asMember = listOf(
                "setRole" to relay.setRole(credB, b.me, true),
                "remove" to relay.remove(credB, a.me, 2, listOf(BoxFor(b.me, "x"))),
                "rotate" to relay.rotate(credB, 2, listOf(BoxFor(b.me, "x"))),
                "decide" to relay.decide(credB, Invite.idOf(Invite.newSecret()), true, 1, "x"),
                "boxPut" to relay.boxPut(credB, 1, listOf(BoxFor(c.me, "x"))),
                "setMeta" to relay.setMeta(credB, 1, Membership.sealMeta(Membership.famOf(b.db)!!, "Mine", 9, 0)),
            )
            asMember.forEach { (what, r) -> note("member B $what -> $r"); assertEquals(RelayResult.Err(SyncError.NotAdmin), r) }
            note("pending for member B -> ${relay.pending(fa, b.me, b.db.o("family").o("me").s("notice"))}")
            val renamed = a.engine.renameFamily("Live test renamed"); note("A renames the family -> $renamed")
            assertTrue(renamed is SyncResult.Done)
            note("B sync after rename -> ${b.sync()}; B sees: ${b.view.familyName}")
            assertEquals("Live test renamed", b.view.familyName)

            note("A makes B admin -> ${a.engine.setAdmin(b.me, true)}")
            val creatorRemove = relay.remove(credB, a.me, 2, listOf(BoxFor(b.me, "x"))); note("admin B removes the creator -> $creatorRemove")
            assertEquals(RelayResult.Err(SyncError.Refused), creatorRemove)
            val selfRemove = relay.remove(credA, a.me, 2, listOf(BoxFor(a.me, "x"))); note("A removes itself -> $selfRemove")
            assertEquals(RelayResult.Err(SyncError.Refused), selfRemove)
            val boxToRemoved = relay.remove(credA, c.me, 2, listOf(BoxFor(a.me, "x"), BoxFor(c.me, "x"))); note("remove C with a box for C -> $boxToRemoved")
            assertEquals(RelayResult.Err(SyncError.Stale), boxToRemoved)

            note("A removes C -> ${a.engine.removeMember(c.me)}")
            note("C pull after removal -> ${relay.pull(credC, 0, 1)}")
            assertEquals(RelayResult.Err(SyncError.Removed), relay.pull(credC, 0, 1))
            assertEquals(RelayResult.Err(SyncError.Removed), relay.push(credC, 2, emptyList()))
            note("C sync -> ${c.sync()}; C family: ${c.db["family"]}; gone: ${c.view.gone}")
            assertNull(c.db["family"])
            cleanup.remove(credC)
            note("B sync -> ${b.sync()}; B epochs: ${b.db.o("family").o("keys")!!.keys}")
            assertEquals(setOf("1", "2"), b.db.o("family").o("keys")!!.keys)
            b.payment("p2", "l1", 99)
            note("B sync -> ${b.sync()}; A sync -> ${a.sync()}")
            assertEquals(a.shared(), b.shared())
            assertEquals(RelayResult.Err(SyncError.Stale), relay.push(credA, 1, emptyList()).also { note("push at the old epoch -> $it") })

            // the creator (sole original admin) leaves; B is already admin. B then leaves: nobody left -> the family is deleted
            note("B steps down while A is admin -> ${relay.setRole(credB, b.me, false)}")
            note("A leaves -> ${a.engine.leaveFamily()}")
            cleanup.remove(credA)
            val stB = (relay.state(credB, 0) as RelayResult.Ok).value
            note("after A left: B role ${stB.myRole}, creator ${stB.creator}, rotate ${stB.rotate}")
            assertEquals("admin", stB.myRole)
            note("B sync (rotates) -> ${b.sync()}; epoch now ${(relay.state(credB, 0) as RelayResult.Ok).value.epoch}")
            val leftB = relay.leave(credB); note("B leaves (last member) -> $leftB")
            assertEquals(RelayResult.Ok("deleted"), leftB)
            cleanup.remove(credB)
            assertEquals(RelayResult.Err(SyncError.Unauthorised), relay.state(credB, 0))
        } finally { TestClock.stop() }
    }
}
