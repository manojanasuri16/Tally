package com.tally.book.ui.lock

import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import androidx.annotation.RequiresApi
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.tally.book.MainActivity
import com.tally.book.core.*
import com.tally.book.data.Crypto
import com.tally.book.data.LockRules
import com.tally.book.data.PhoneLockOffer
import com.tally.book.data.PhoneSeal
import com.tally.book.data.PhoneSeal.Outcome
import kotlinx.coroutines.suspendCancellableCoroutine
import java.security.KeyStore
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.coroutines.resume

/*
 * The phone's lock, the Android side (docs/DESIGN.md, "App lock"). The rules and the sealed copy's format are in
 * data/LockRules.kt (PhoneSeal); here is the Keystore key and the system prompt.
 *
 * The key: AES-256-GCM in the Android Keystore, usable for one operation right after the phone's own screen lock or a strong
 * biometric (setUserAuthenticationParameters(0, AUTH_BIOMETRIC_STRONG or AUTH_DEVICE_CREDENTIAL), Android 11 and up) and NOT
 * invalidated when fingerprints are added or removed (setInvalidatedByBiometricEnrollment(false)). Android destroys it when the
 * screen lock is removed; the book then opens with the recovery code, never lost. What it seals is { s: salt, k: lock key },
 * stored in this app's own SharedPreferences "phonelock" (device-only: never in the book or a backup).
 *
 * Android 10 (API 29) cannot bind a Keystore key to "screen lock or biometric, per use" with a system prompt, so the phone's
 * lock is offered from Android 11 only; a phone-lock book on Android 10 (a restore) opens with its recovery code.
 *
 * Every prompt sets MainActivity.holdLock: the prompt (and the screen-lock page it can show) is not "leaving the app", so it
 * never triggers the re-lock.
 */
object PhoneLock {
    private const val ALIAS = "tally-phone-lock"
    private const val PREFS = "phonelock"

    @Volatile private var ctxRef: Context? = null
    fun init(ctx: Context) { ctxRef = ctx.applicationContext }
    private val appCtx: Context? get() = MainActivity.current?.applicationContext ?: ctxRef
    private fun prefs() = appCtx?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val ks: KeyStore get() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    /** The phone has a secure screen lock (PIN, pattern or password; a fingerprint or face needs one). */
    fun deviceSecure(ctx: Context): Boolean = runCatching { ctx.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true }.getOrDefault(false)

    fun offer(ctx: Context): PhoneLockOffer = LockRules.phoneLockOffer(Build.VERSION.SDK_INT, deviceSecure(ctx))

    private fun stored(): ByteArray? = prefs()?.getString("w", null)?.let { runCatching { Crypto.unb64(it) }.getOrNull() }

    /** Whether this phone holds a copy for the lock with [salt] (it may still turn out to be gone when it is used). */
    fun fits(salt: ByteArray?): Boolean =
        salt != null && stored() != null && prefs()?.getString("s", null) == Crypto.b64(salt) && runCatching { ks.containsAlias(ALIAS) }.getOrDefault(false)

    /** Forgets the copy and its key (a lock change, a restore of another lock, turning the lock off). */
    fun disable() {
        prefs()?.edit()?.clear()?.apply()
        runCatching { ks.deleteEntry(ALIAS) }
    }

    /** Seals the current lock's key under a new Keystore key, after one prompt. Ok when the phone's lock now opens Tally. */
    suspend fun enable(act: FragmentActivity, salt: ByteArray, key: ByteArray, title: String): Outcome {
        disable()
        val r = PhoneSeal.wrap(box(act, title), salt, key)
        if (r is Outcome.Ok) prefs()?.edit()?.putString("w", Crypto.b64(r.bytes))?.putString("s", Crypto.b64(salt))?.commit()
        else disable()
        return r
    }

    /** One prompt: the key that opens [env] (Ok), or Cancelled, Gone (use the recovery code) or Failed. */
    suspend fun unlockKey(act: FragmentActivity, env: Map<String, Any?>, title: String): Outcome {
        val r = PhoneSeal.unlockKey(box(act, title), stored(), env)
        if (r === Outcome.Gone) disable()
        return r
    }

    /** "Is it you?" with no app lock set: the phone's own prompt, no key involved (showing hidden amounts again). */
    suspend fun confirmPlain(act: FragmentActivity, title: String): Outcome {
        if (!deviceSecure(act)) return Outcome.Gone
        // Android 10 cannot combine a strong biometric with the screen lock; a weak one (face) plus the screen lock is allowed
        val auth = if (Build.VERSION.SDK_INT >= 30) BIOMETRIC_STRONG or DEVICE_CREDENTIAL else BIOMETRIC_WEAK or DEVICE_CREDENTIAL
        return when (val r = prompt(act, null, title, auth)) { is Prompted.Ok -> Outcome.Ok(ByteArray(0)); is Prompted.No -> r.why }
    }

    private fun box(act: FragmentActivity, title: String): PhoneSeal.Box = object : PhoneSeal.Box {
        override suspend fun seal(plain: ByteArray): Outcome {
            if (Build.VERSION.SDK_INT < 30 || !deviceSecure(act)) return Outcome.Gone
            val c = try {
                Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, makeKey()) }
            } catch (e: Exception) { return Outcome.Failed(e.message.orEmpty()) }
            return when (val r = prompt(act, c, title, BIOMETRIC_STRONG or DEVICE_CREDENTIAL)) {
                is Prompted.Ok -> try {
                    val done = r.cipher ?: return Outcome.Failed("")
                    val ct = done.doFinal(plain)
                    Outcome.Ok(done.iv + ct)
                } catch (e: Exception) { Outcome.Failed(e.message.orEmpty()) }
                is Prompted.No -> r.why
            }
        }

        override suspend fun open(stored: ByteArray): Outcome {
            if (Build.VERSION.SDK_INT < LockRules.PHONE_MIN_SDK || !deviceSecure(act) || stored.size <= 12) return Outcome.Gone
            val c = try {
                val k = ks.getKey(ALIAS, null) as? SecretKey ?: return Outcome.Gone
                Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(128, stored.copyOfRange(0, 12))) }
            } catch (e: KeyPermanentlyInvalidatedException) { return Outcome.Gone } catch (e: Exception) { return Outcome.Gone }
            return when (val r = prompt(act, c, title, BIOMETRIC_STRONG or DEVICE_CREDENTIAL)) {
                is Prompted.Ok -> try {
                    Outcome.Ok((r.cipher ?: return Outcome.Failed("")).doFinal(stored.copyOfRange(12, stored.size)))
                } catch (e: AEADBadTagException) { Outcome.Gone } catch (e: Exception) { Outcome.Failed(e.message.orEmpty()) }
                is Prompted.No -> r.why
            }
        }
    }

    @RequiresApi(30)
    private fun makeKey(): SecretKey {
        runCatching { ks.deleteEntry(ALIAS) }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(true)
                // the screen lock or a strong biometric, for each use
                .setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL)
                // never destroyed by adding or removing a fingerprint: that must not lock a family out of its book
                .setInvalidatedByBiometricEnrollment(false)
                .build()
        )
        return gen.generateKey()
    }

    private sealed class Prompted {
        class Ok(val cipher: Cipher?) : Prompted()
        class No(val why: Outcome) : Prompted()
    }

    private suspend fun prompt(act: FragmentActivity, cipher: Cipher?, title: String, authenticators: Int): Prompted =
        suspendCancellableCoroutine { cont ->
            // the hold outlives the prompt a moment: the screen-lock page it may show can hand back its answer before onStart runs
            fun done(p: Prompted) {
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ MainActivity.holdLock = false }, 1500)
                if (cont.isActive) cont.resume(p)
            }
            val bp = BiometricPrompt(act, ContextCompat.getMainExecutor(act), object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    val c = result.cryptoObject?.cipher
                    done(if (cipher != null && c == null) Prompted.No(Outcome.Failed("")) else Prompted.Ok(c))
                }
                override fun onAuthenticationError(code: Int, msg: CharSequence) {
                    done(Prompted.No(when (code) {
                        BiometricPrompt.ERROR_USER_CANCELED, BiometricPrompt.ERROR_NEGATIVE_BUTTON,
                        BiometricPrompt.ERROR_CANCELED, BiometricPrompt.ERROR_TIMEOUT -> Outcome.Cancelled
                        BiometricPrompt.ERROR_NO_DEVICE_CREDENTIAL -> Outcome.Gone
                        else -> Outcome.Failed(msg.toString())
                    }))
                }
            })
            // with the screen lock allowed there is no Cancel button of our own: the system prompt brings its own way out
            val info = BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .setSubtitle(t("phonelock.prompt.sub", "Use your phone's screen lock or fingerprint"))
                .setAllowedAuthenticators(authenticators)
                .setConfirmationRequired(false)
                .build()
            MainActivity.holdLock = true
            try {
                if (cipher != null) bp.authenticate(info, BiometricPrompt.CryptoObject(cipher)) else bp.authenticate(info)
            } catch (e: Exception) { done(Prompted.No(Outcome.Failed(e.message.orEmpty()))) }
            cont.invokeOnCancellation { MainActivity.holdLock = false; runCatching { bp.cancelAuthentication() } }
        }
}
