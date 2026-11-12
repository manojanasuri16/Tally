package com.tally.book.ui.family

import com.tally.book.core.t
import com.tally.book.core.tp
import com.tally.book.sync.FamilyMember
import com.tally.book.sync.JoinRequest
import com.tally.book.sync.MembershipView

/*
 * The decisions and words of the membership screens, pure (no Compose, no network) so they can be unit-tested: which actions
 * a member's sheet offers, what Leave and Remove ask, how a join request reads, how long an invite has left. The relay is the
 * one that enforces the rules (docs/DESIGN.md, "Membership"); this only keeps the screen from offering what will be refused.
 */
enum class MemberAction { MAKE_ADMIN, REMOVE_ADMIN, REMOVE }

object MemberText {
    /**
     * What an admin can do with [m]: nothing for oneself or for the person who created the family (they cannot be removed or
     * demoted by others), "Make admin" or "Remove as admin", and "Remove from family". Nothing at all for someone who is not an admin.
     */
    fun actions(iAmAdmin: Boolean, m: FamilyMember): List<MemberAction> = when {
        !iAmAdmin || m.me || m.creator -> emptyList()
        m.admin -> listOf(MemberAction.REMOVE_ADMIN, MemberAction.REMOVE)
        else -> listOf(MemberAction.MAKE_ADMIN, MemberAction.REMOVE)
    }

    /** The label under a member's name and the value at the right: "You", "Admin". */
    fun tag(m: FamilyMember): String? =
        listOfNotNull(if (m.me) t("family.you", "You") else null, if (m.admin) t("family.admin", "Admin") else null)
            .joinToString(" · ").ifEmpty { null }

    fun removeQuestion(name: String): String = t(
        "member.remove.ask",
        "Remove {name} from the family?\n\n{name} will stop seeing new entries and payments. What is already on their phone stays there.",
        "name" to name,
    )

    /** The member the relay makes admin when the only admin leaves: the longest-standing of the others (the earliest "since"). */
    fun successor(v: MembershipView): FamilyMember? {
        val others = v.members.filter { !it.me }
        return others.filter { it.since > 0 }.minByOrNull { it.since } ?: others.firstOrNull()
    }

    /** What Leave asks. The last member deletes the family; the only admin says who takes over. */
    fun leaveQuestion(v: MembershipView): String {
        val last = v.members.none { !it.me }
        val onlyAdmin = v.iAmAdmin && v.members.none { it.admin && !it.me }
        val stays = t("leave.stays", "The entries and payments stay on this phone.")
        return when {
            last -> t("leave.ask.last", "Leave this family?\n\nYou are the last one in it, so the family will be deleted. {stays}", "stays" to stays)
            onlyAdmin -> {
                val next = successor(v)?.name?.takeIf { it.isNotBlank() }
                val who = if (next != null) t("leave.admin.who", "{name} will become the admin.", "name" to next)
                else t("leave.admin.someone", "Someone else will become the admin.")
                t("leave.ask.admin", "Leave this family?\n\nYou are the only admin. {who} {stays} You will stop getting new ones from the family, and they will stop getting yours.", "who" to who, "stays" to stays)
            }
            else -> t("leave.ask", "Leave this family?\n\nThe entries and payments stay on this phone. You will stop getting new ones from the family, and they will stop getting yours.")
        }
    }

    /** "Asha wants to join Smith family. Ravi invited them." The second sentence is left out when nobody is known. */
    fun requestLine(r: JoinRequest, familyName: String, myId: String): String {
        val fam = familyName.ifBlank { t("family.the", "the family") }
        val head = if (r.back) t("req.backLine", "{name} says they are back on a new phone.", "name" to r.name)
        else t("req.line", "{name} wants to join {family}.", "name" to r.name, "family" to fam)
        val by = when {
            r.invitedById.isNotEmpty() && r.invitedById == myId -> t("req.byMe", "You invited them.")
            r.invitedBy.isNotBlank() -> t("req.by", "{inviter} invited them.", "inviter" to r.invitedBy)
            else -> return head
        }
        return "$head $by"
    }

    /**
     * For a request from someone back on a new phone: the member it most likely is, to pre-select in the picker: the one whose
     * name matches the name typed (ignoring case and spaces), else null (the admin picks). Never this phone's own member when
     * another one matches.
     */
    fun likelyMember(r: JoinRequest, members: List<FamilyMember>): FamilyMember? {
        fun norm(s: String) = s.trim().lowercase().replace(Regex("\\s+"), " ")
        val want = norm(r.name)
        val same = members.filter { norm(it.name) == want }
        return same.firstOrNull { !it.me } ?: same.firstOrNull()
    }

    /** The question before "Allow as <name>". */
    fun allowAsQuestion(name: String): String = t(
        "req.as.ask",
        "Let this phone become {name}?\n\n{name}'s old phone will stop working at once. Everything {name} added keeps their name.",
        "name" to name,
    )

    /**
     * Whether to tell this phone's person, once, to make a second admin: they are the only admin and someone else is in the
     * family. Nobody but an admin can let a member back in on a new phone, so a family whose only admin loses their phone (and has
     * no backup) cannot let them back.
     */
    fun needsSecondAdmin(iAmAdmin: Boolean, members: List<FamilyMember>): Boolean =
        iAmAdmin && members.size >= 2 && members.none { it.admin && !it.me }

    /** How long an invite has left: "23 hours left", "Less than an hour left". */
    fun invitePeriod(expiresAt: Long, now: Long): String {
        val left = expiresAt - now
        val hours = ((left + HOUR - 1) / HOUR).toInt()
        return if (left < HOUR) t("invite.left.soon", "Less than an hour left")
        else tp("invite.left", hours, "{n} hour left", "{n} hours left")
    }

    private const val HOUR = 3_600_000L
}
