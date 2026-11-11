package com.tally.book.sync

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64

/*
 * The invite (docs/DESIGN.md, "Invite"):  tally://join#<familyId>.<base64url(key)>.<base64url(familyName)>[.<base64url(inviterName)>]
 *
 * - familyId: 16 random bytes, base64url without padding (22 characters). It gates a public endpoint, so it is random,
 *   not a uid().
 * - key: the 32-byte family key, base64url without padding (43 characters).
 * - name: the family's name as UTF-8, base64url without padding.
 * - inviter: optional, the name of the person inviting (their DB.me.name), same encoding and limits as the name. Invites made
 *   by 0.1.0 have three parts and no inviter.
 * Everything after '#' is a URL fragment, which is never sent to a server.
 *
 * Parsing is strict: anything malformed (wrong scheme, extra parts, padding, non-canonical base64, a key that is not 32
 * bytes, a name or inviter that is not valid UTF-8 or is empty or too long) gives null.
 */
class Invite(val familyId: String, val key: ByteArray, val name: String, val inviter: String? = null) {
    override fun equals(other: Any?) = other is Invite && other.familyId == familyId && other.key.contentEquals(key) && other.name == name && other.inviter == inviter
    override fun hashCode() = familyId.hashCode()
    override fun toString() = "Invite($familyId, <key>, $name, $inviter)"

    companion object {
        const val PREFIX = "tally://join#"
        const val MAX_NAME = 80
        private val B64URL = Regex("^[A-Za-z0-9_-]+$")
        private val FAMILY_ID = Regex("^[A-Za-z0-9_-]{22}$")
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

        /** A new random family key. */
        fun newKey(): ByteArray = SyncCrypto.randomBytes(32)

        fun cleanName(name: String): String? {
            val t = name.trim()
            if (t.isEmpty() || t.length > MAX_NAME || t.any { it.isISOControl() }) return null
            return t
        }

        /** A person's name made fit for the invite (control characters dropped, at most [MAX_NAME] characters), or null when nothing is left. */
        fun inviterName(name: String?): String? =
            name?.filterNot { it.isISOControl() }?.trim()?.take(MAX_NAME)?.trim()?.takeIf { it.isNotEmpty() }

        /**
         * The invite text, or an IllegalArgumentException for a bad id, key or name. [inviter] is the inviting person's name; a blank
         * one is left out (a three-part invite), a long one is shortened to [MAX_NAME] characters.
         */
        fun make(familyId: String, key: ByteArray, name: String, inviter: String? = null): String {
            require(isFamilyId(familyId)) { "bad family id" }
            require(key.size == 32) { "bad key" }
            val nm = requireNotNull(cleanName(name)) { "bad name" }
            val by = inviterName(inviter)
            return PREFIX + familyId + "." + b64url(key) + "." + b64url(nm.toByteArray(Charsets.UTF_8)) +
                (if (by != null) "." + b64url(by.toByteArray(Charsets.UTF_8)) else "")
        }

        fun make(invite: Invite): String = make(invite.familyId, invite.key, invite.name, invite.inviter)

        /**
         * The invite inside [text], which may be a whole shared message ("Join our family in Tally: tally://join#...") or a
         * link someone pasted with other words around it. The invite itself is still parsed strictly.
         */
        fun find(text: String?): Invite? {
            val s = text ?: return null
            val at = s.indexOf(PREFIX)
            if (at < 0) return null
            var end = at
            while (end < s.length && !s[end].isWhitespace()) end++
            return parse(s.substring(at, end))
        }

        /** The invite in [text] (surrounding whitespace allowed, nothing else), or null. */
        fun parse(text: String?): Invite? {
            val s = text?.trim() ?: return null
            if (s.length > MAX_TEXT || !s.startsWith(PREFIX)) return null
            val parts = s.substring(PREFIX.length).split('.')
            if (parts.size != 3 && parts.size != 4) return null
            val id = parts[0]
            if (!isFamilyId(id)) return null
            val key = unb64url(parts[1])?.takeIf { it.size == 32 } ?: return null
            val name = nameOf(parts[2]) ?: return null
            val inviter = if (parts.size == 4) (nameOf(parts[3]) ?: return null) else null
            return Invite(id, key, name, inviter)
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
