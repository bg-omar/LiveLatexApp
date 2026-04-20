package com.omariskandarani.livelatexapp

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Pro status: lifetime purchase, temporary window ([proUntilEpochMs]), or debug override.
 * Persisted in encrypted prefs (separate file from [EditorPrefs]).
 */
class EntitlementRepository(context: Context) {

    private val appContext = context.applicationContext

    private val masterKey = MasterKey.Builder(appContext)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val encryptedPrefs: SharedPreferences = EncryptedSharedPreferences.create(
        appContext,
        PREFS_FILE,
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    private val _state = MutableStateFlow(readState())
    val state: StateFlow<EntitlementState> = _state.asStateFlow()

    fun isProEffective(): Boolean {
        val s = _state.value
        if (s.debugProOverride) return true
        if (s.purchasedPro) return true
        val until = s.proUntilEpochMs
        return until > System.currentTimeMillis()
    }

    fun refreshFromStorage() {
        _state.value = readState()
    }

    fun setDebugProOverride(enabled: Boolean) {
        encryptedPrefs.edit().putBoolean(KEY_DEBUG_PRO, enabled).apply()
        _state.value = readState()
    }

    fun setPurchasedPro(value: Boolean) {
        encryptedPrefs.edit().putBoolean(KEY_PURCHASED_PRO, value).apply()
        _state.value = readState()
    }

    /** Extends Pro until [epochMs] if that is later than current stored window. */
    fun extendProUntil(epochMs: Long) {
        val cur = encryptedPrefs.getLong(KEY_PRO_UNTIL, 0L)
        val next = maxOf(cur, epochMs)
        encryptedPrefs.edit().putLong(KEY_PRO_UNTIL, next).apply()
        _state.value = readState()
    }

    fun setProUntil(epochMs: Long) {
        encryptedPrefs.edit().putLong(KEY_PRO_UNTIL, epochMs).apply()
        _state.value = readState()
    }

    fun getLastRewardedAdAt(): Long =
        encryptedPrefs.getLong(KEY_LAST_REWARDED_AT, 0L)

    fun setLastRewardedAdAt(epochMs: Long) {
        encryptedPrefs.edit().putLong(KEY_LAST_REWARDED_AT, epochMs).apply()
        _state.value = readState()
    }

    private fun readState(): EntitlementState {
        return EntitlementState(
            purchasedPro = encryptedPrefs.getBoolean(KEY_PURCHASED_PRO, false),
            proUntilEpochMs = encryptedPrefs.getLong(KEY_PRO_UNTIL, 0L),
            debugProOverride = BuildConfig.DEBUG && encryptedPrefs.getBoolean(KEY_DEBUG_PRO, false),
            lastRewardedAdAt = encryptedPrefs.getLong(KEY_LAST_REWARDED_AT, 0L)
        )
    }

    companion object {
        private const val PREFS_FILE = "LiveLatexEntitlements"
        private const val KEY_PURCHASED_PRO = "purchased_pro"
        private const val KEY_PRO_UNTIL = "pro_until_epoch_ms"
        private const val KEY_DEBUG_PRO = "debug_pro_override"
        private const val KEY_LAST_REWARDED_AT = "last_rewarded_ad_at"

        @Volatile
        private var instance: EntitlementRepository? = null

        fun get(context: Context): EntitlementRepository {
            return instance ?: synchronized(this) {
                instance ?: EntitlementRepository(context.applicationContext).also { instance = it }
            }
        }
    }
}

data class EntitlementState(
    val purchasedPro: Boolean,
    val proUntilEpochMs: Long,
    val debugProOverride: Boolean,
    val lastRewardedAdAt: Long
)
