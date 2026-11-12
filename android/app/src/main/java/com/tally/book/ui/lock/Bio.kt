package com.tally.book.ui.lock

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.tally.book.MainActivity
import com.tally.book.core.*
import com.tally.book.data.Crypto
import kotlinx.coroutines.suspendCancellableCoroutine
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.coroutines.resume

/*
 * Fingerprint unlock, the native counterpart of the web app's WebAuthn-PRF unlock (lock-screen.js
 * finishEnableBio / tryBioUnlock / bioFastUnlock). The web app keeps the lock's key bytes and their
 * salt sealed under a key only a fingerprint opens (bioEnvelope.k); here that key is an AES-GCM key
 * in the Android Keystore that can only be used right after a strong biometric check and is
 * invalidated when the phone's fingerprints change. What it seals is { s: salt, k: the PIN's PBKDF2 key, t, l } (data/Crypto.kt:
 * the PIN's key still needs this phone's own secret to open the book), so a fingerprint unlock goes straight to
 * Ledger.unlockWithKey() without the 150k-round PBKDF2.
 * A salt that no longer matches the stored envelope (the lock changed, a restore) falls back to the lock.
 */
object Bio {
    private const val ALIAS = "balance-lock-bio"
    private const val PREFS = "bio"
    /** `money-bio-auto` (bioAutoOn): ask for the fingerprint as soon as the lock screen opens. */
    const val AUTO_KEY = "tally-bio-auto"

    class Failure(val name: String, message: String) : Exception(message)

    private fun prefs(): android.content.SharedPreferences? = appCtx?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val appCtx: Context? get() = MainActivity.current?.applicationContext ?: ctxRef
    @Volatile private var ctxRef: Context? = null
    fun init(ctx: Context) { ctxRef = ctx.applicationContext }

    private val ks: KeyStore get() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    fun available(ctx: Context): Boolean =
        BiometricManager.from(ctx).canAuthenticate(BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS

    /** Set up (bioCredId && bioEnvelope). */
    fun on(): Boolean = prefs()?.getString("d", null) != null && runCatching { ks.containsAlias(ALIAS) }.getOrDefault(false)

    fun autoOn(): Boolean = DevicePrefs.get(AUTO_KEY) == "1"
    fun autoSet(on: Boolean) = DevicePrefs.set(AUTO_KEY, if (on) "1" else null)

    /** disableBioUnlock() */
    fun disable() {
        prefs()?.edit()?.clear()?.apply()
        runCatching { ks.deleteEntry(ALIAS) }
    }

    /** finishEnableBio(): seals the PIN's key ([pinKey], from the check just made) under a new fingerprint key (one prompt). */
    suspend fun enable(act: FragmentActivity, pinKey: ByteArray): Result<Unit> {
        if (!Crypto.locked) return Result.failure(Failure("NoLock", t("lock.setLockFirst", "Set a lock first.")))
        val key = pinKey
        when (BiometricManager.from(act).canAuthenticate(BIOMETRIC_STRONG)) {
            BiometricManager.BIOMETRIC_SUCCESS -> Unit
            else -> return Result.failure(Failure("NotSupportedError", t("lock.err.noFingerprint", "no fingerprint is set up on this phone.")))
        }
        return runCatching {
            disable()
            val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            gen.init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setUserAuthenticationRequired(true)
                    .setInvalidatedByBiometricEnrollment(true)
                    .apply { if (android.os.Build.VERSION.SDK_INT >= 30) setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG) }
                    .build()
            )
            gen.generateKey()
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, ks.getKey(ALIAS, null) as SecretKey)
            val done = prompt(act, c, t("security.setUpFingerprintUnlock", "Set up fingerprint unlock")).getOrThrow()
            val plain = JsonCodec.stringify(obj("s" to Crypto.b64(Crypto.salt!!), "k" to Crypto.b64(key), "t" to Crypto.type, "l" to Crypto.pinLen))
            val ct = done.doFinal(plain.toByteArray(Charsets.UTF_8))
            prefs()!!.edit().putString("i", Crypto.b64(done.iv)).putString("d", Crypto.b64(ct)).apply()
            if (DevicePrefs.get(AUTO_KEY) == null) autoSet(true)
            Unit
        }.onFailure { if (!on()) disable() }
    }

    /** Asks for the fingerprint and opens the sealed copy: { s, k, t, l }. */
    suspend fun open(act: FragmentActivity, title: String): Result<Obj> = runCatching {
        val p = prefs() ?: throw Failure("NotAllowedError", "")
        val iv = Crypto.unb64(p.getString("i", null) ?: throw Failure("NotAllowedError", ""))
        val d = Crypto.unb64(p.getString("d", null) ?: throw Failure("NotAllowedError", ""))
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        try {
            c.init(Cipher.DECRYPT_MODE, ks.getKey(ALIAS, null) as? SecretKey ?: throw Failure("Gone", ""), GCMParameterSpec(128, iv))
        } catch (e: KeyPermanentlyInvalidatedException) {
            disable(); throw Failure("Gone", "")
        }
        val done = prompt(act, c, title).getOrThrow()
        JsonCodec.parseObj(String(done.doFinal(d), Charsets.UTF_8))
    }

    private suspend fun prompt(act: FragmentActivity, cipher: Cipher, title: String): Result<Cipher> =
        suspendCancellableCoroutine { cont ->
            val bp = BiometricPrompt(act, ContextCompat.getMainExecutor(act), object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    val c = result.cryptoObject?.cipher
                    if (cont.isActive) cont.resume(if (c != null) Result.success(c) else Result.failure(Failure("NotAllowedError", "")))
                }
                override fun onAuthenticationError(code: Int, msg: CharSequence) {
                    val name = when (code) {
                        BiometricPrompt.ERROR_USER_CANCELED, BiometricPrompt.ERROR_NEGATIVE_BUTTON, BiometricPrompt.ERROR_CANCELED, BiometricPrompt.ERROR_TIMEOUT -> "NotAllowedError"
                        else -> "Failed"
                    }
                    if (cont.isActive) cont.resume(Result.failure(Failure(name, msg.toString())))
                }
            })
            val info = BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .setNegativeButtonText(t("lock.cancel", "Cancel"))
                .setAllowedAuthenticators(BIOMETRIC_STRONG)
                .setConfirmationRequired(false)
                .build()
            bp.authenticate(info, BiometricPrompt.CryptoObject(cipher))
            cont.invokeOnCancellation { runCatching { bp.cancelAuthentication() } }
        }
}
