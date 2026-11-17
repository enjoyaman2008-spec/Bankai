
/*
 * Yumeiro — Copyright (C) 2026 siamform
 * Licensed under the GNU General Public License v3.0 or later. See LICENSE and TRADEMARKS.md.
 * Do not remove or alter this notice.
 */
package dev.asfakul.yumeiro.online.download

import android.content.Context
import android.content.SharedPreferences
import dev.asfakul.yumeiro.profile.ProfileManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Offline / download preferences (Settings → Downloads). */
object DownloadSettings {

    val AUTO_DELETE_CHOICES = listOf(0, 1, 3, 7, 14, 30)
    val QUALITY_CHOICES = listOf(0, 1080, 720, 480, 360)

    private const val LEGACY_PREFS = "download_settings"
    private const val PREFS_PREFIX = "download_settings_"

    private var prefs: SharedPreferences? = null
    private var loadedProfileId: String? = null

    private val _autoDeleteDays = MutableStateFlow(0)
    val autoDeleteDays: StateFlow<Int> = _autoDeleteDays.asStateFlow()

    private val _quality = MutableStateFlow(0)
    val quality: StateFlow<Int> = _quality.asStateFlow()

    private val _parallel = MutableStateFlow(2)
    val parallel: StateFlow<Int> = _parallel.asStateFlow()

    private val _keepByDefault = MutableStateFlow(false)
    val keepByDefault: StateFlow<Boolean> = _keepByDefault.asStateFlow()

    private val _saveYouTubeToPhone = MutableStateFlow(true)
    val saveYouTubeToPhone: StateFlow<Boolean> = _saveYouTubeToPhone.asStateFlow()

    private val _askFirst = MutableStateFlow(true)
    val askFirst: StateFlow<Boolean> = _askFirst.asStateFlow()

    private val _showTips = MutableStateFlow(true)
    val showTips: StateFlow<Boolean> = _showTips.asStateFlow()

    private val _downloader = MutableStateFlow(ExternalDownloader.IN_APP)
    val downloader: StateFlow<String> = _downloader.asStateFlow()

    /**
     * Loads settings belonging to the active profile.
     * Legacy global settings are copied into Default only.
     */
    @Synchronized
    fun init(context: Context) {
        val app = context.applicationContext
        val profileId = ProfileManager.activeProfile.value?.id
            ?: error("ProfileManager is not initialized")

        if (loadedProfileId == profileId && prefs != null) return

        val target = app.getSharedPreferences(
            PREFS_PREFIX + profileId,
            Context.MODE_PRIVATE
        )

        if (
            profileId == ProfileManager.DEFAULT_PROFILE_ID &&
            target.all.isEmpty()
        ) {
            migrateLegacySettings(
                app.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE),
                target
            )
        }

        prefs = target
        loadedProfileId = profileId
        loadValues(target)
    }

    private fun migrateLegacySettings(
        legacy: SharedPreferences,
        destination: SharedPreferences
    ) {
        val values = legacy.all
        if (values.isEmpty()) return

        val editor = destination.edit()

        values.forEach { (key, value) ->
            when (value) {
                is String -> editor.putString(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is Set<*> -> {
                    val strings = value.filterIsInstance<String>().toSet()
                    editor.putStringSet(key, strings)
                }
            }
        }

        editor.apply()
    }

    private fun loadValues(p: SharedPreferences) {
        _autoDeleteDays.value =
            p.getInt("auto_delete_days", 0)
                .takeIf { it in AUTO_DELETE_CHOICES } ?: 0

        _quality.value =
            p.getInt("quality", 0)
                .takeIf { it in QUALITY_CHOICES } ?: 0

        _parallel.value = p.getInt("parallel", 2).coerceIn(1, 3)
        _keepByDefault.value = p.getBoolean("keep_default", false)
        _saveYouTubeToPhone.value = p.getBoolean("yt_to_phone", true)
        _askFirst.value = p.getBoolean("ask_first", true)
        _showTips.value = p.getBoolean("show_tips", true)
        _downloader.value =
            p.getString("downloader", ExternalDownloader.IN_APP)
                ?: ExternalDownloader.IN_APP
    }

    @Synchronized
    fun reloadForActiveProfile(context: Context) {
        prefs = null
        loadedProfileId = null
        init(context)
    }

    fun setAutoDeleteDays(context: Context, days: Int) {
        init(context)
        val safe = days.takeIf { it in AUTO_DELETE_CHOICES } ?: 0
        _autoDeleteDays.value = safe
        prefs?.edit()?.putInt("auto_delete_days", safe)?.apply()
    }

    fun setQuality(context: Context, height: Int) {
        init(context)
        val safe = height.takeIf { it in QUALITY_CHOICES } ?: 0
        _quality.value = safe
        prefs?.edit()?.putInt("quality", safe)?.apply()
    }

    fun setParallel(context: Context, n: Int) {
        init(context)
        _parallel.value = n.coerceIn(1, 3)
        prefs?.edit()?.putInt("parallel", _parallel.value)?.apply()
    }

    fun setKeepByDefault(context: Context, on: Boolean) {
        init(context)
        _keepByDefault.value = on
        prefs?.edit()?.putBoolean("keep_default", on)?.apply()
    }

    fun setSaveYouTubeToPhone(context: Context, on: Boolean) {
        init(context)
        _saveYouTubeToPhone.value = on
        prefs?.edit()?.putBoolean("yt_to_phone", on)?.apply()
    }

    fun setAskFirst(context: Context, on: Boolean) {
        init(context)
        _askFirst.value = on
        prefs?.edit()?.putBoolean("ask_first", on)?.apply()
    }

    fun setShowTips(context: Context, on: Boolean) {
        init(context)
        _showTips.value = on
        prefs?.edit()?.putBoolean("show_tips", on)?.apply()
    }

    fun setDownloader(context: Context, pkg: String) {
        init(context)
        _downloader.value = pkg
        prefs?.edit()?.putString("downloader", pkg)?.apply()
    }

    fun autoDeleteLabel(days: Int) =
        when (days) {
            0 -> "Never"
            1 -> "1 day"
            else -> "$days days"
        }

    fun qualityLabel(h: Int) =
        if (h == 0) "Best" else "${h}p"

    @Synchronized
    internal fun resetForTests() {
        prefs = null
        loadedProfileId = null
        _autoDeleteDays.value = 0
        _quality.value = 0
        _parallel.value = 2
        _keepByDefault.value = false
        _saveYouTubeToPhone.value = true
        _askFirst.value = true
        _showTips.value = true
        _downloader.value = ExternalDownloader.IN_APP
    }
}
