package com.tally.book.sync

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64

/*
 * The invite (docs/DESIGN.md, "Invite"): a one-time code that lets someone ASK to join. It never contains a family key.
 *
 *   tally://join#t2.<familyId>.<secret>.<expires>.<base64url(familyName)>[.<base64url(inviterName)>]
 *
 * - familyId: 16 random bytes, base64url without padding (22 characters).
 * - secret: 32 random bytes made for this invite only, base64url (43 characters). It never reaches the relay. From it:
 *     inviteId   = base64url(HKDF(secret, "tally-invite-id", 16))   the name the relay knows the invite by
 *     requestKey = HKDF(secret, "tally-join-req")                    seals the joiner's request (name, public key)
 *     grantKey   = HKDF(secret, "tally-grant")                       the admin's MAC over the keys it gives the joiner
 *   so the relay can neither read a request, nor forge one, nor forge the admin's answer: only people who saw the code
 *   (and the family's members, who get the secret sealed under the family key) hold it.
 * - expires: when the invite stops working, epoch seconds in decimal (the relay enforces its own 24 hours too).
 * - name / inviter: as in version 1: the family's name and the inviting person's name, UTF-8, 1 to 80 characters.
 * Everything after '#' is a URL fragment, never sent to a server.
 *
 * Invites made by 0.1.0 / 0.2.0 (`tally://join#<familyId>.<key>.<name>[.<inviter>]`) are not accepted: [isOld] recognises
 * them so the app can say "ask for a new code". Parsing is strict: anything malformed gives null.
 */
class Invite(val familyId: String, val secret: ByteArray, val expiresAt: Long, val name: String, val inviter: String? = null) {
    /** The id the relay knows this invite by. */
    val inviteId: String get() = idOf(secret)

    /** True once the invite's own expiry has passed (the relay may refuse it earlier: used, cancelled). */
    fun expired(nowMs: Long): Boolean = nowMs >= expiresAt

    override fun equals(other: Any?) = other is Invite && other.familyId == familyId && other.secret.contentEquals(secret) &&
        other.expiresAt == expiresAt && other.name == name && other.inviter == inviter
    override fun hashCode() = familyId.hashCode()
    override fun toString() = "Invite($familyId, <secret>, $expiresAt, $name, $inviter)"

    companion object {
        const val PREFIX = "tally://join#"
        private const val TAG = "t2"
        const val MAX_NAME = 80
        private val B64URL = Regex("^[A-Za-z0-9_-]+$")
        private val FAMILY_ID = Regex("^[A-Za-z0-9_-]{22}$")
        private val EXPIRES = Regex("^[1-9][0-9]{0,11}$")
        private val enc = Base64.getUrlEncoder().withoutPadding()

        fun b64url(b: ByteArray): String = enc.encodeToString(b)

        /** Canonical base64url (no padding) or null. */
        fun unb64url(s: String): ByteArray? {
            if (!B64URL.matches(s)) return null
            val b = runCatching { Base64.getUrlDecoder().decode(s) }.getOrNull() ?: return null
            return if (enc.encodeToString(b) == s) b else null
        }

        fun isFamilyId(s: String): Boolean = FAMILY_ID.matches(s) && unb64url(s)?.size == 16

        /** A new random family id. */
        fun newFamilyId(): String = b64url(SyncCrypto.randomBytes(16))

        /** A new random invite secret. */
        fun newSecret(): ByteArray = SyncCrypto.randomBytes(32)

        fun idOf(secret: ByteArray): String = b64url(SyncCrypto.hkdfInfo(secret, "tally-invite-id", 16))
        fun requestKey(secret: ByteArray): ByteArray = SyncCrypto.hkdfInfo(secret, "tally-join-req")
        fun grantKey(secret: ByteArray): ByteArray = SyncCrypto.hkdfInfo(secret, "tally-grant")

        fun cleanName(name: String): String? {
            val t = name.trim()
            if (t.isEmpty() || t.length > MAX_NAME || t.any { it.isISOControl() }) return null
            return t
        }

        /** A person's name made fit for the invite (control characters dropped, at most [MAX_NAME] characters), or null when nothing is left. */
        fun inviterName(name: String?): String? =
            name?.filterNot { it.isISOControl() }?.trim()?.take(MAX_NAME)?.trim()?.takeIf { it.isNotEmpty() }

        /** The invite text, or an IllegalArgumentException for a bad id, secret, expiry or name. A blank inviter is left out. */
        fun make(familyId: String, secret: ByteArray, expiresAt: Long, name: String, inviter: String? = null): String {
            require(isFamilyId(familyId)) { "bad family id" }
            require(secret.size == 32) { "bad secret" }
            val secs = expiresAt / 1000
            require(EXPIRES.matches(secs.toString())) { "bad expiry" }
            val nm = requireNotNull(cleanName(name)) { "bad name" }
            val by = inviterName(inviter)
            return PREFIX + TAG + "." + familyId + "." + b64url(secret) + "." + secs + "." + b64url(nm.toByteArray(Charsets.UTF_8)) +
                (if (by != null) "." + b64url(by.toByteArray(Charsets.UTF_8)) else "")
        }

        fun make(invite: Invite): String = make(invite.familyId, invite.secret, invite.expiresAt, invite.name, invite.inviter)

        /** The link inside [text] (a whole shared message, or a link with words around it), or null. */
        private fun linkIn(text: String?): String? {
            val s = text ?: return null
            val at = s.indexOf(PREFIX)
            if (at < 0) return null
            var end = at
            while (end < s.length && !s[end].isWhitespace()) end++
            return s.substring(at, end)
        }

        /** The invite inside [text], which may be a whole shared message. The invite itself is still parsed strictly. */
        fun find(text: String?): Invite? = linkIn(text)?.let { parse(it) }

        /** True when [text] holds an invite in the format of 0.1.0/0.2.0 (it carried the family key; no longer accepted). */
        fun isOld(text: String?): Boolean {
            val s = linkIn(text) ?: return false
            if (s.length > MAX_TEXT) return false
            val parts = s.substring(PREFIX.length).split('.')
            return (parts.size == 3 || parts.size == 4) && isFamilyId(parts[0]) && unb64url(parts[1])?.size == 32
        }

        /** The invite in [text] (surrounding whitespace allowed, nothing else), or null. */
        fun parse(text: String?): Invite? {
            val s = text?.trim() ?: return null
            if (s.length > MAX_TEXT || !s.startsWith(PREFIX)) return null
            val parts = s.substring(PREFIX.length).split('.')
            if (parts.size != 5 && parts.size != 6) return null
            if (parts[0] != TAG) return null
            val id = parts[1]
            if (!isFamilyId(id)) return null
            val secret = unb64url(parts[2])?.takeIf { it.size == 32 } ?: return null
            if (!EXPIRES.matches(parts[3])) return null
            val exp = parts[3].toLongOrNull() ?: return null
            val name = nameOf(parts[4]) ?: return null
            val inviter = if (parts.size == 6) (nameOf(parts[5]) ?: return null) else null
            return Invite(id, secret, exp * 1000, name, inviter)
        }

        /** The longest invite text accepted: two names of 80 characters in a script that takes 3 UTF-8 bytes each still fit. */
        const val MAX_TEXT = 1024

        /** A name segment: canonical base64url of valid UTF-8 that [cleanName] leaves unchanged, or null. */
        private fun nameOf(seg: String): String? {
            val bytes = unb64url(seg) ?: return null
            val name = runCatching {
                Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString()
            }.getOrNull() ?: return null
            return if (cleanName(name) == name) name else null
        }
    }
}
