package com.tally.book

import com.tally.book.sync.FamilyMember
import com.tally.book.sync.JoinRequest
import com.tally.book.sync.MembershipView
import com.tally.book.sync.SyncState
import com.tally.book.ui.family.MemberAction
import com.tally.book.ui.family.MemberText
import com.tally.book.ui.family.SyncText
import org.junit.Assert.*
import org.junit.Test

class MemberTextTest {
    private fun m(id: String, admin: Boolean = false, creator: Boolean = false, me: Boolean = false, since: Long = 1000, name: String = id) =
        FamilyMember(id, name, admin, creator, me, since)

    private fun view(vararg members: FamilyMember) = MembershipView(
        "fam", "Smith family", false, members.firstOrNull { it.me }?.id.orEmpty(), members.any { it.me && it.admin }, members.any { it.me && it.creator },
        members.toList(), emptyList(), null, null, false,
    )

    @Test fun nonAdminsSeeNoActions() {
        for (target in listOf(m("a"), m("b", admin = true), m("c", admin = true, creator = true))) {
            assertEquals(emptyList<MemberAction>(), MemberText.actions(false, target))
        }
    }

    @Test fun adminActionsFollowTheTargetsRole() {
        assertEquals(listOf(MemberAction.MAKE_ADMIN, MemberAction.REMOVE), MemberText.actions(true, m("a")))
        assertEquals(listOf(MemberAction.REMOVE_ADMIN, MemberAction.REMOVE), MemberText.actions(true, m("b", admin = true)))
    }

    @Test fun noActionsOnMyselfOrTheCreator() {
        assertEquals(emptyList<MemberAction>(), MemberText.actions(true, m("me", admin = true, me = true)))
        assertEquals(emptyList<MemberAction>(), MemberText.actions(true, m("c", admin = true, creator = true)))
        // the creator is also out of reach when they are not (or no longer) an admin
        assertEquals(emptyList<MemberAction>(), MemberText.actions(true, m("c", creator = true)))
    }

    @Test fun tagsSayYouAndAdmin() {
        assertNull(MemberText.tag(m("a")))
        assertEquals("Admin", MemberText.tag(m("a", admin = true)))
        assertEquals("You", MemberText.tag(m("a", me = true)))
        assertEquals("You · Admin", MemberText.tag(m("a", admin = true, me = true)))
    }

    @Test fun removeExplainsWhatStays() {
        val q = MemberText.removeQuestion("Asha")
        assertTrue(q.startsWith("Remove Asha from the family?\n\n"))
        assertTrue(q.contains("Asha will stop seeing new entries and payments. What is already on their phone stays there."))
    }

    @Test fun leaveAsOrdinaryMemberKeepsTheOldWords() {
        val v = view(m("me", me = true), m("b", admin = true), m("c"))
        assertTrue(MemberText.leaveQuestion(v).startsWith("Leave this family?\n\nThe entries and payments stay on this phone."))
    }

    @Test fun leaveAsOneOfSeveralAdminsSaysNothingAboutSuccessor() {
        val v = view(m("me", admin = true, me = true), m("b", admin = true), m("c"))
        assertFalse(MemberText.leaveQuestion(v).contains("will become the admin"))
    }

    @Test fun leavingAsTheOnlyAdminNamesTheLongestStandingMember() {
        val v = view(m("me", admin = true, me = true, since = 100), m("late", since = 900, name = "Late"), m("early", since = 300, name = "Early"))
        val q = MemberText.leaveQuestion(v)
        assertTrue(q, q.contains("Early will become the admin."))
        assertFalse(q.contains("Late will"))
        assertEquals("early", MemberText.successor(v)?.id)
    }

    @Test fun successorWithoutKnownDatesIsStillSomeone() {
        val v = view(m("me", admin = true, me = true), m("x", since = 0, name = ""))
        assertTrue(MemberText.leaveQuestion(v).contains("Someone else will become the admin."))
    }

    @Test fun leavingAsTheLastMemberSaysTheFamilyIsDeleted() {
        val v = view(m("me", admin = true, me = true))
        val q = MemberText.leaveQuestion(v)
        assertTrue(q, q.contains("the family will be deleted"))
        assertTrue(q.contains("The entries and payments stay on this phone."))
    }

    @Test fun requestReadsWhoAsksAndWhoInvited() {
        val r = JoinRequest("inv1", "Asha", "m2", "Ravi", 0, 0)
        assertEquals("Asha wants to join Smith family. Ravi invited them.", MemberText.requestLine(r, "Smith family", "m1"))
        assertEquals("Asha wants to join Smith family. You invited them.", MemberText.requestLine(r, "Smith family", "m2"))
        val unknown = JoinRequest("inv1", "Asha", "m9", "", 0, 0)
        assertEquals("Asha wants to join Smith family.", MemberText.requestLine(unknown, "Smith family", "m1"))
    }

    @Test fun inviteTimeLeftRoundsUp() {
        val hour = 3_600_000L
        assertEquals("Less than an hour left", MemberText.invitePeriod(1000 + 59 * 60_000L, 1000))
        assertEquals("1 hour left", MemberText.invitePeriod(1000 + hour, 1000))
        assertEquals("2 hours left", MemberText.invitePeriod(1000 + hour + 1, 1000))
        assertEquals("24 hours left", MemberText.invitePeriod(1000 + 24 * hour, 1000))
    }

    @Test fun statusLineForWaitingAndOldFamilies() {
        val idle = SyncState()
        val waiting = SyncText.status(idle, false, 0, 0, joining = true)
        assertEquals("Waiting for an admin to let you in", waiting.text)
        assertEquals(SyncText.Action.RETRY, waiting.action)
        val old = SyncText.status(idle, false, 0, 0, oldFamily = true)
        assertEquals("Sharing needs to be set up again after this update. Your entries stay on this phone.", old.text)
        assertEquals(SyncText.Action.SET_UP, old.action)
    }
}
