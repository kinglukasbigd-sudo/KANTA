package mk.kanta.app.core.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import io.github.jan.supabase.auth.SessionManager
import io.github.jan.supabase.auth.user.UserSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores the Supabase session encrypted at rest (brief: "session persisted securely").
 *
 * The session holds a refresh token, which is effectively a long-lived password
 * for the account. supabase-kt's default store is plain app-private preferences;
 * this wraps the same idea with AES-256-GCM under a key that lives in the Android
 * Keystore, so the token is useless if the file is ever copied off the device.
 *
 * Deliberately no extra dependency: `androidx.security:security-crypto` is
 * deprecated, and the Keystore + javax.crypto primitives it wrapped are all here.
 *
 * Anything that fails to decrypt — a key wiped by a factory reset, a restored
 * backup whose key did not travel with it — is treated as "no session" and
 * deleted. The user signs in again; nothing crashes.
 */
class EncryptedSessionManager(
    context: Context,
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
) : SessionManager {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override suspend fun saveSession(session: UserSession) = withContext(Dispatchers.IO) {
        val plain = json.encodeToString(UserSession.serializer(), session)
        prefs.edit().putString(KEY_SESSION, encrypt(plain)).apply()
    }

    override suspend fun loadSession(): UserSession? = withContext(Dispatchers.IO) {
        val stored = prefs.getString(KEY_SESSION, null) ?: return@withContext null
        runCatching { json.decodeFromString(UserSession.serializer(), decrypt(stored)) }
            .getOrElse {
                prefs.edit().remove(KEY_SESSION).apply()
                null
            }
    }

    override suspend fun deleteSession() = withContext(Dispatchers.IO) {
        prefs.edit().remove(KEY_SESSION).apply()
    }

    // -----------------------------------------------------------------------------------------

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
            generateKey()
        }
    }

    /** IV (12 bytes) + ciphertext, Base64. A fresh random IV per write. */
    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val sealed = cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(sealed, Base64.NO_WRAP)
    }

    private fun decrypt(encoded: String): String {
        val sealed = Base64.decode(encoded, Base64.NO_WRAP)
        val iv = sealed.copyOfRange(0, IV_BYTES)
        val body = sealed.copyOfRange(IV_BYTES, sealed.size)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv))
        }
        return String(cipher.doFinal(body), Charsets.UTF_8)
    }

    private companion object {
        const val PREFS_NAME = "kanta_session"
        const val KEY_SESSION = "session"
        const val KEY_ALIAS = "kanta_session_key"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
