
/*
 * Yumeiro — Copyright (C) 2026 siamform
 * Licensed under the GNU General Public License v3.0 or later. See LICENSE and TRADEMARKS.md.
 * Do not remove or alter this notice.
 */
package dev.asfakul.yumeiro.online

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import dev.asfakul.yumeiro.profile.ProfileManager

/** Preferences for online videos, isolated by active profile. */
object OnlineSettings {
    private var prefs: SharedPreferences? = null
    private var secrets: SharedPreferences? = null

    @Volatile
    private var loadedProfileId: String? = null

    private val _adult = MutableStateFlow(false)
    val adult: StateFlow<Boolean> = _adult.asStateFlow()

    private val _adultPrivate = MutableStateFlow(true)
    val adultPrivate: StateFlow<Boolean> = _adultPrivate.asStateFlow()

    private val _skipIntro = MutableStateFlow(true)
    val skipIntro: StateFlow<Boolean> = _skipIntro.asStateFlow()

    private val _autoSkip = MutableStateFlow(false)
    val autoSkip: StateFlow<Boolean> = _autoSkip.asStateFlow()

    private val _subtitleLang = MutableStateFlow("en")
    val subtitleLang: StateFlow<String> = _subtitleLang.asStateFlow()

    private val _openSubtitlesKey = MutableStateFlow("")
    val openSubtitlesKey: StateFlow<String> = _openSubtitlesKey.asStateFlow()

    private val _subdlKey = MutableStateFlow("")
    val subdlKey: StateFlow<String> = _subdlKey.asStateFlow()

    private val _subsourceKey = MutableStateFlow("")
    val subsourceKey: StateFlow<String> = _subsourceKey.asStateFlow()

    /** Session-only secrets are never written in plaintext. */
    private val volatileSecrets =
        java.util.concurrent.ConcurrentHashMap<String, String>()

    private fun activeProfileId(): String =
        ProfileManager.activeProfile.value?.id
            ?: error("ProfileManager must be initialized first")

    private fun secretStorageKey(profileId: String, name: String): String =
        "profile:$profileId:$name"

    @Synchronized
    fun init(context: Context) {
        val app = context.applicationContext
        val profileId = activeProfileId()

        if (loadedProfileId == profileId && prefs != null) return

        // A profile change must never retain the previous profile's in-memory secrets.
        volatileSecrets.clear()
        prefs = null
        secrets = null
        loadedProfileId = null

        val profilePrefs = app.getSharedPreferences(
            "online_settings_$profileId",
            Context.MODE_PRIVATE
        )

        val secretPrefs = app.getSharedPreferences(
            SECRETS_FILE,
            Context.MODE_PRIVATE
        )

        // Copy old global preferences into Default only.
        if (profileId == ProfileManager.DEFAULT_PROFILE_ID) {
            migrateLegacySettings(app, profilePrefs)
            migrateLegacySecrets(app, secretPrefs, profileId)
        }

        prefs = profilePrefs
        secrets = secretPrefs

        _adult.value = profilePrefs.getBoolean("adult", false)
        _adultPrivate.value = profilePrefs.getBoolean("adult_private", true)
        _skipIntro.value = profilePrefs.getBoolean("skip_intro", true)
        _autoSkip.value = profilePrefs.getBoolean("auto_skip", false)
        _subtitleLang.value =
            profilePrefs.getString("subtitle_lang", null)
                ?: java.util.Locale.getDefault().language.ifBlank { "en" }

        loadedProfileId = profileId

        _openSubtitlesKey.value = readSecret("os_key").orEmpty()
        _subdlKey.value = readSecret("subdl_key").orEmpty()
        _subsourceKey.value = readSecret("subsource_key").orEmpty()
    }

    /**
     * Reload after the central profile-switch coordinator changes profiles.
     * Do not call this directly from profile UI.
     */
    @Synchronized
    fun reloadForActiveProfile(context: Context) {
        prefs = null
        secrets = null
        loadedProfileId = null
        volatileSecrets.clear()

        _adult.value = false
        _adultPrivate.value = true
        _skipIntro.value = true
        _autoSkip.value = false
        _subtitleLang.value =
            java.util.Locale.getDefault().language.ifBlank { "en" }

        _openSubtitlesKey.value = ""
        _subdlKey.value = ""
        _subsourceKey.value = ""

        init(context)
    }

    private fun migrateLegacySettings(
        context: Context,
        destination: SharedPreferences
    ) {
        val legacy = context.getSharedPreferences(
            LEGACY_SETTINGS_FILE,
            Context.MODE_PRIVATE
        )

        val keys = listOf(
            "adult",
            "adult_private",
            "skip_intro",
            "auto_skip",
            "subtitle_lang"
        )

        val edit = destination.edit()
        var changed = false

        keys.forEach { key ->
            if (!destination.contains(key) && legacy.contains(key)) {
                when (val value = legacy.all[key]) {
                    is Boolean -> edit.putBoolean(key, value)
                    is String -> edit.putString(key, value)
                }
                changed = true
            }
        }

        if (changed) edit.apply()
    }

    /**
     * Migrate legacy secrets into Default's namespace.
     * Encrypted values are copied unchanged.
     * Plaintext values are encrypted before storage and removed
     * from their old location.
     */
    private fun migrateLegacySecrets(
        context: Context,
        destination: SharedPreferences,
        profileId: String
    ) {
        val legacySettings = context.getSharedPreferences(
            LEGACY_SETTINGS_FILE,
            Context.MODE_PRIVATE
        )

        val legacySecrets = context.getSharedPreferences(
            SECRETS_FILE,
            Context.MODE_PRIVATE
        )

        fun migrateFrom(
            source: SharedPreferences,
            isLegacySecret: (String) -> Boolean
        ) {
            source.all.forEach { (name, value) ->
                if (value !is String || !isLegacySecret(name)) return@forEach

                val destinationKey = secretStorageKey(profileId, name)

                if (!destination.contains(destinationKey) &&
                    !volatileSecrets.containsKey(destinationKey)
                ) {
                    val safeValue =
                        if (dev.asfakul.yumeiro.security.SecretBox.isSealed(value)) {
                            value
                        } else {
                            dev.asfakul.yumeiro.security.SecretBox.seal(value)
                        }

                    if (safeValue != null) {
                        destination.edit()
                            .putString(destinationKey, safeValue)
                            .apply()
                    } else {
                        // Keep the value in memory only if encryption is unavailable.
                        volatileSecrets[destinationKey] = value
                    }
                }

                // Never leave a legacy plaintext secret stored on disk.
                if (!dev.asfakul.yumeiro.security.SecretBox.isSealed(value)) {
                    source.edit().remove(name).apply()
                }
            }
        }

        migrateFrom(legacySecrets) { name ->
            !name.startsWith("profile:")
        }

        migrateFrom(legacySettings) { name ->
            name == "os_key" ||
                name == "subdl_key" ||
                name == "subsource_key" ||
                name.startsWith("secret:")
        }
    }

    fun setAdult(context: Context, v: Boolean) {
        init(context)
        _adult.value = v
        prefs?.edit()?.putBoolean("adult", v)?.apply()
        OnlineRepository.clearCaches()
    }

    fun setAdultPrivate(context: Context, v: Boolean) {
        init(context)
        _adultPrivate.value = v
        prefs?.edit()?.putBoolean("adult_private", v)?.apply()
    }

    fun setSkipIntro(context: Context, v: Boolean) {
        init(context)
        _skipIntro.value = v
        prefs?.edit()?.putBoolean("skip_intro", v)?.apply()
    }

    fun setAutoSkip(context: Context, v: Boolean) {
        init(context)
        _autoSkip.value = v
        prefs?.edit()?.putBoolean("auto_skip", v)?.apply()
    }

    fun setSubtitleLang(context: Context, v: String) {
        init(context)
        _subtitleLang.value = v
        prefs?.edit()?.putString("subtitle_lang", v)?.apply()
    }

    fun setOpenSubtitlesKey(context: Context, v: String) {
        init(context)
        val value = v.trim()
        _openSubtitlesKey.value = value
        writeSecret("os_key", value)
    }

    fun setSubdlKey(context: Context, v: String) {
        init(context)
        val value = v.trim()
        _subdlKey.value = value
        writeSecret("subdl_key", value)
    }

    fun setSubsourceKey(context: Context, v: String) {
        init(context)
        val value = v.trim()
        _subsourceKey.value = value
        writeSecret("subsource_key", value)
    }

    /** Small encrypted secrets store for sync accounts. */
    fun secret(key: String): String? =
        readSecret("secret:$key")

    fun setSecret(context: Context, key: String, value: String?) {
        init(context)
        writeSecret("secret:$key", value)
    }

    private fun readSecret(name: String): String? {
        val profileId = loadedProfileId ?: return null
        val storageKey = secretStorageKey(profileId, name)

        volatileSecrets[storageKey]?.let { return it }

        val stored = secrets?.getString(storageKey, null) ?: return null

        if (dev.asfakul.yumeiro.security.SecretBox.isSealed(stored)) {
            return dev.asfakul.yumeiro.security.SecretBox.open(stored)
        }

        // Repair a plaintext value if one is encountered.
        writeSecret(name, stored)
        return stored
    }

    private fun writeSecret(name: String, value: String?) {
        val profileId = loadedProfileId ?: return
        val storage = secrets ?: return
        val storageKey = secretStorageKey(profileId, name)

        if (value.isNullOrBlank()) {
            volatileSecrets.remove(storageKey)
            storage.edit().remove(storageKey).apply()
            return
        }

        val sealed = dev.asfakul.yumeiro.security.SecretBox.seal(value)

        if (sealed != null) {
            volatileSecrets.remove(storageKey)
            storage.edit().putString(storageKey, sealed).apply()
        } else {
            volatileSecrets[storageKey] = value
            storage.edit().remove(storageKey).apply()
        }
    }

    @androidx.annotation.VisibleForTesting
    internal fun resetForTests() {
        prefs = null
        secrets = null
        loadedProfileId = null
        volatileSecrets.clear()
    }

    private const val LEGACY_SETTINGS_FILE = "online_settings"

    /** Same file name excluded by Android backup rules. */
    private const val SECRETS_FILE = "online_secrets"
}
