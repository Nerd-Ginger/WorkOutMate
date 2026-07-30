package com.nerdginger.workoutmate

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Keystore-backed storage for the user's Anthropic API key.
 *
 * The key deliberately never reaches the WebView: the page asks whether one is
 * present and asks for a request to be *made*, but can never read the value
 * back. That is the whole reason the Claude call lives on the Kotlin side.
 *
 * `EncryptedSharedPreferences` fails to initialise on a small number of devices
 * with a broken keystore. Rather than crash — or silently fall back to plaintext
 * — the store reports itself unavailable, and the UI steers the user to the
 * copy/paste routine builder, which needs no key at all.
 */
class SecureStore(context: Context) {

    private val app = context.applicationContext

    private val prefs: SharedPreferences? by lazy {
        runCatching {
            val masterKey = MasterKey.Builder(app)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                app,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            ) as SharedPreferences
        }.getOrNull()
    }

    val isAvailable: Boolean get() = prefs != null

    fun hasApiKey(): Boolean = !apiKey().isNullOrBlank()

    fun apiKey(): String? = prefs?.getString(KEY_API, null)

    fun setApiKey(value: String): Boolean {
        val store = prefs ?: return false
        store.edit().putString(KEY_API, value.trim()).apply()
        return true
    }

    fun clearApiKey(): Boolean {
        val store = prefs ?: return false
        store.edit().remove(KEY_API).apply()
        return true
    }

    fun model(): String = prefs?.getString(KEY_MODEL, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_MODEL

    fun setModel(value: String): Boolean {
        val store = prefs ?: return false
        store.edit().putString(KEY_MODEL, value.trim()).apply()
        return true
    }

    companion object {
        const val DEFAULT_MODEL = "claude-opus-5"
        private const val PREFS_NAME = "workoutmate_secure"
        private const val KEY_API = "anthropic_api_key"
        private const val KEY_MODEL = "anthropic_model"
    }
}
