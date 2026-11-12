package com.tally.book.data

import com.tally.book.core.*
import java.security.SecureRandom

/*
 * The two kinds of app lock, as plain JVM rules (docs/DESIGN.md, "App lock"). No Android here, so all of it runs in JVM tests;
 * ui/lock/PhoneLock.kt is the Android Keystore and system prompt side.
 *
 * - Tally's own lock: a PIN, pattern or password. The book is sealed under PBKDF2 of it (Crypto, unchanged). A fingerprint can
 *   open it through Bio, which keeps a copy of the derived key under a Keystore key.
 * - The phone's lock (envelope type "phone"): the book is sealed exactly the same way, but the secret is a random 20-digit
 *   recovery code made by the app and written down by the person. A copy of the derived key is kept on the phone sealed under
 *   an Android Keystore key that only the phone's own screen lock or fingerprint can use (PhoneLock). So the phone's prompt
 *   opens the book every day, and the recovery code opens it when that copy is gone (screen lock removed, a new phone, a
 *   backup). The code lives inside the sealed book (DB.recoveryCode) so it can be shown again behind the phone's prompt.
 */

/** The 20-digit recovery code of the phone's lock. Digits only: easy to write down and to type in any language. */
object RecoveryCode {
    const val DIGITS = 20
    private val random = SecureRandom()

    fun make(): String = buildString { repeat(DIGITS) { append('0' + random.nextInt(10)) } }

    /** What was typed, as plain ASCII digits: spaces and dashes dropped, Telugu or Devanagari digits read as digits. */
    fun normalize(typed: String): String = buildString {
        for (c in typed) { val d = Character.digit(c, 10); if (d >= 0) append('0' + d) }
    }

    fun valid(typed: String): Boolean = normalize(typed).length == DIGITS

    /** "4821 0937 5512 8836 2290": groups of four, for reading and writing down. */
    fun format(code: String): String = normalize(code).chunked(4).joinToString(" ")
}

enum class LockKind { OFF, OWN, PHONE }

/** Why the phone's lock can or cannot be chosen on this phone. */
enum class PhoneLockOffer { OK, OLD_ANDROID, NO_SCREEN_LOCK }

/** What showing amounts again asks for. */
enum class UnhideAsk { APP_LOCK, PHONE, NOTHING }

object LockRules {
    const val PHONE = "phone"
    /** The lock screen's stage type while the recovery code is typed (never stored: the envelope says "phone"). */
    const val RECOVERY = "recovery"
    /** The phone's lock needs Android 11: a Keystore key usable after the screen lock or a strong biometric, per use. */
    const val PHONE_MIN_SDK = 30

    fun kindOf(locked: Boolean, type: String): LockKind = when {
        !locked -> LockKind.OFF
        type == PHONE -> LockKind.PHONE
        else -> LockKind.OWN
    }

    fun phoneLockOffer(sdk: Int, deviceSecure: Boolean): PhoneLockOffer = when {
        sdk < PHONE_MIN_SDK -> PhoneLockOffer.OLD_ANDROID
        !deviceSecure -> PhoneLockOffer.NO_SCREEN_LOCK
        else -> PhoneLockOffer.OK
    }

    /** Hiding never asks. Showing again asks the app lock when one is set, else the phone's own lock when it has one, else nothing. */
    fun unhideAsk(kind: LockKind, deviceSecure: Boolean): UnhideAsk = when {
        kind != LockKind.OFF -> UnhideAsk.APP_LOCK
        deviceSecure -> UnhideAsk.PHONE
        else -> UnhideAsk.NOTHING
    }

    /** The secret as the key derivation takes it: a recovery code is reduced to its digits, anything else is used as typed. */
    fun secretFor(stageType: String, typed: String): String = if (stageType == RECOVERY || stageType == PHONE) RecoveryCode.normalize(typed) else typed

    /** The shortest secret worth checking for a stage type. */
    fun minLength(stageType: String): Int = when (stageType) {
        RECOVERY, PHONE -> RecoveryCode.DIGITS
        "password" -> 6
        else -> 4
    }
}

/**
 * The device copy of the lock key for the phone's lock: { s: salt, k: key } (as Bio keeps for a fingerprint), sealed by a [Box]
 * that only the phone's own lock opens. The copy says which salt it belongs to, so a copy left from an earlier lock (or a
 * restore) is never used on a book it does not fit: that book falls back to the recovery code.
 */
object PhoneSeal {
    sealed class Outcome {
        class Ok(val bytes: ByteArray) : Outcome()
        /** The person closed the prompt. Nothing changed; try again. */
        object Cancelled : Outcome()
        /** The phone's lock can no longer open the copy (screen lock removed, key gone, another phone): use the recovery code. */
        object Gone : Outcome()
        /** Anything else (too many tries, a sensor error); [why] is the system's own words. */
        class Failed(val why: String) : Outcome()
    }

    /** What seals the copy: the Android Keystore key behind the phone's lock, or a fake in tests. */
    interface Box {
        /** Seals [plain] after the phone's prompt. Ok carries what to store. */
        suspend fun seal(plain: ByteArray): Outcome
        /** Opens what [seal] gave, after the phone's prompt. Ok carries the plain bytes. */
        suspend fun open(stored: ByteArray): Outcome
    }

    fun payload(salt: ByteArray, key: ByteArray): ByteArray =
        JsonCodec.stringify(obj("s" to Crypto.b64(salt), "k" to Crypto.b64(key))).toByteArray(Charsets.UTF_8)

    /** The key in a payload, only when it belongs to the envelope [env] (the same salt); null otherwise. */
    fun keyFor(payload: ByteArray, env: Map<String, Any?>): ByteArray? = runCatching {
        val o = JsonCodec.parseObj(String(payload, Charsets.UTF_8))
        if (o.s("s").isEmpty() || o.s("s") != env.s("s")) null else Crypto.unb64(o.s("k"))
    }.getOrNull()

    /** Seals the current lock's key ([salt], [key]) with the box. Ok carries what to store. */
    suspend fun wrap(box: Box, salt: ByteArray, key: ByteArray): Outcome = box.seal(payload(salt, key))

    /** The key that opens [env], from the stored copy. No copy, or a copy of another lock: Gone. */
    suspend fun unlockKey(box: Box, stored: ByteArray?, env: Map<String, Any?>): Outcome {
        if (stored == null) return Outcome.Gone
        return when (val r = box.open(stored)) {
            is Outcome.Ok -> keyFor(r.bytes, env)?.let { Outcome.Ok(it) } ?: Outcome.Gone
            else -> r
        }
    }
}
