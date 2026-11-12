package com.tally.book.ui.lock

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.tally.book.data.Crypto
import com.tally.book.data.DeviceSecret
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/*
 * This phone's own secret for Tally's own lock (docs/DESIGN.md, "The seal"): 32 random bytes, made once, kept in the app's
 * SharedPreferences "devicekey" sealed under an Android Keystore AES-256-GCM key ("tally-device-key", no user authentication:
 * the PIN check in the app is what gates the screen, and the Keystore key only makes sure the bytes cannot be used off this
 * phone). The book's PIN wrap needs these bytes, so a copied book or backup cannot be opened by guessing the PIN on another
 * computer: only the recovery code opens it there. Never in the book, a backup or a log.
 *
 * If this phone's Keystore cannot make or use a key at all, the bytes are kept in the private SharedPreferences unsealed
 * (`plain`): still never in the book file or a backup, but readable to someone who can read the app's private files.
 * A stored copy that fails to open is never replaced (that would cut off the PIN for good): the PIN then fails and the
 * recovery code opens the book.
 */
class DeviceKey(ctx: Context) : DeviceSecret {
    private val app = ctx.applicationContext
    private fun prefs() = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val ks: KeyStore get() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    @Synchronized override fun get(): ByteArray {
        val p = prefs()
        p.getString("w", null)?.let { w ->
            val all = Crypto.unb64(w)
            val k = ks.getKey(ALIAS, null) as? SecretKey ?: error("device key gone")
            val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(128, all.copyOfRange(0, 12))) }
            return c.doFinal(all.copyOfRange(12, all.size))
        }
        p.getString("plain", null)?.let { return Crypto.unb64(it) }
        val secret = Crypto.randomBytes(32)
        val sealed = runCatching {
            val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            gen.init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
            val k = gen.generateKey()
            val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, k) }
            c.iv + c.doFinal(secret)
        }.getOrNull()
        if (sealed != null) p.edit().putString("w", Crypto.b64(sealed)).commit()
        else p.edit().putString("plain", Crypto.b64(secret)).commit()
        return secret
    }

    companion object {
        private const val ALIAS = "tally-device-key"
        private const val PREFS = "devicekey"
    }
}
