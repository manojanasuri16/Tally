package com.tally.book.sync

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64

/*
 * The invite (docs/DESIGN.md, "Invite"):  tally://join#<familyId>.<base64url(key)>.<base64url(familyName)>
 *
 * - familyId: 16 random bytes, base64url without padding (22 characters). It gates a public endpoint, so it is random,
 *   not a uid().
 * - key: the 32-byte family key, base64url without padding (43 characters).
 * - name: the family's name as UTF-8, base64url without padding.
 * Everything after '#' is a URL fragment, which is never sent to a server.
 *
 * Parsing is strict: anything malformed (wrong scheme, extra parts, padding, non-canonical base64, a key that is not 32
 * bytes, a name that is not valid UTF-8 or is empty or too long) gives null.
 */
class Invite(val familyId: String, val key: ByteArray, val name: String) {
    override fun equals(other: Any?) = other is Invite && other.familyId == familyId && other.key.contentEquals(key) && other.name == name
    override fun hashCode() = familyId.hashCode()
    override fun toString() = "Invite($familyId, <key>, $name)"

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

        /** The invite text, or an IllegalArgumentException for a bad id, key or name. */
        fun make(familyId: String, key: ByteArray, name: String): String {
            require(isFamilyId(familyId)) { "bad family id" }
            require(key.size == 32) { "bad key" }
            val nm = requireNotNull(cleanName(name)) { "bad name" }
            return PREFIX + familyId + "." + b64url(key) + "." + b64url(nm.toByteArray(Charsets.UTF_8))
        }

        fun make(invite: Invite): String = make(invite.familyId, invite.key, invite.name)

        /** The invite in [text] (surrounding whitespace allowed, nothing else), or null. */
        fun parse(text: String?): Invite? {
            val s = text?.trim() ?: return null
            if (s.length > 512 || !s.startsWith(PREFIX)) return null
            val parts = s.substring(PREFIX.length).split('.')
            if (parts.size != 3) return null
            val (id, k, nm) = parts
            if (!isFamilyId(id)) return null
            val key = unb64url(k)?.takeIf { it.size == 32 } ?: return null
            val nameBytes = unb64url(nm) ?: return null
            val name = runCatching {
                Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(nameBytes)).toString()
            }.getOrNull() ?: return null
            if (cleanName(name) != name) return null
            return Invite(id, key, name)
        }
    }
}
