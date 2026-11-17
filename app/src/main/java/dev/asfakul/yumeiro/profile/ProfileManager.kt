
package dev.asfakul.yumeiro.profile

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class Profile(
    val id: String,
    val name: String,
    val createdAt: Long
)

object ProfileManager {

    const val DEFAULT_PROFILE_ID = "default"

    private const val PREFS_NAME = "profiles"
    private const val KEY_REGISTRY = "registry"
    private const val KEY_ACTIVE_PROFILE = "active_profile"

    private val lock = Any()

    private val _profiles = MutableStateFlow<List<Profile>>(emptyList())
    val profiles: StateFlow<List<Profile>> = _profiles.asStateFlow()

    private val _activeProfile = MutableStateFlow<Profile?>(null)
    val activeProfile: StateFlow<Profile?> = _activeProfile.asStateFlow()

    @Volatile
    private var initialized = false

    /**
     * Must be called once during application startup,
     * before profile-aware stores are loaded.
     */
    fun initialize(context: Context) {
        synchronized(lock) {
            if (initialized) return

            val appContext = context.applicationContext
            val prefs = appContext.getSharedPreferences(
                PREFS_NAME,
                Context.MODE_PRIVATE
            )

            val loadedProfiles = readProfiles(prefs)

            val safeProfiles = if (loadedProfiles.isEmpty()) {
                listOf(
                    Profile(
                        id = DEFAULT_PROFILE_ID,
                        name = "Default",
                        createdAt = System.currentTimeMillis()
                    )
                )
            } else {
                loadedProfiles
            }

            _profiles.value = safeProfiles

            val savedActiveId = prefs.getString(
                KEY_ACTIVE_PROFILE,
                DEFAULT_PROFILE_ID
            )

            val selected = safeProfiles.firstOrNull {
                it.id == savedActiveId
            } ?: safeProfiles.first()

            _activeProfile.value = selected

            saveRegistry(prefs, safeProfiles, selected.id)

            // Copy legacy data into the default profile.
            // Original files are deliberately preserved.
            migrateLegacyFiles(appContext)

            initialized = true
        }
    }

    fun profileDir(context: Context, profileId: String): File {
        require(isValidId(profileId)) {
            "Invalid profile ID"
        }

        return File(
            File(context.applicationContext.filesDir, "profiles"),
            profileId
        ).apply {
            if (!exists()) mkdirs()
        }
    }

    fun dataFile(context: Context, fileName: String): File {
        require(
            fileName.isNotBlank() &&
                fileName == File(fileName).name &&
                fileName != "." &&
                fileName != ".."
        ) {
            "Invalid data file name"
        }

        val profile = activeProfile.value
            ?: error("ProfileManager is not initialized")

        return File(profileDir(context, profile.id), fileName)
    }

    fun createProfile(context: Context, name: String): Profile {
        synchronized(lock) {
            checkInitialized()

            val cleanName = name.trim()
            require(cleanName.isNotEmpty()) {
                "Profile name cannot be empty"
            }
            require(cleanName.length <= 32) {
                "Profile name must be 32 characters or less"
            }

            require(_profiles.value.none {
                it.name.equals(cleanName, ignoreCase = true)
            }) {
                "A profile with this name already exists"
            }

            val profile = Profile(
                id = UUID.randomUUID().toString(),
                name = cleanName,
                createdAt = System.currentTimeMillis()
            )

            val updated = _profiles.value + profile
            _profiles.value = updated

            profileDir(context, profile.id)

            saveRegistry(
                context.applicationContext.getSharedPreferences(
                    PREFS_NAME,
                    Context.MODE_PRIVATE
                ),
                updated,
                activeProfile.value?.id ?: DEFAULT_PROFILE_ID
            )

            return profile
        }
    }

    /**
     * Do not expose profile switching in the UI yet.
     * First make every persistent data store profile-aware.
     */
    fun setActiveProfile(context: Context, profileId: String) {
        synchronized(lock) {
            checkInitialized()

            val selected = _profiles.value.firstOrNull {
                it.id == profileId
            } ?: throw IllegalArgumentException("Profile not found")

            _activeProfile.value = selected

            saveRegistry(
                context.applicationContext.getSharedPreferences(
                    PREFS_NAME,
                    Context.MODE_PRIVATE
                ),
                _profiles.value,
                selected.id
            )
        }
    }

    fun renameProfile(
        context: Context,
        profileId: String,
        newName: String
    ) {
        synchronized(lock) {
            checkInitialized()

            val cleanName = newName.trim()
            require(cleanName.isNotEmpty() && cleanName.length <= 32) {
                "Profile name must contain 1–32 characters"
            }

            require(_profiles.value.none {
                it.id != profileId &&
                    it.name.equals(cleanName, ignoreCase = true)
            }) {
                "A profile with this name already exists"
            }

            val updated = _profiles.value.map {
                if (it.id == profileId) {
                    it.copy(name = cleanName)
                } else {
                    it
                }
            }

            require(updated.any { it.id == profileId }) {
                "Profile not found"
            }

            _profiles.value = updated

            _activeProfile.value = updated.firstOrNull {
                it.id == activeProfile.value?.id
            }

            saveRegistry(
                context.applicationContext.getSharedPreferences(
                    PREFS_NAME,
                    Context.MODE_PRIVATE
                ),
                updated,
                activeProfile.value?.id ?: DEFAULT_PROFILE_ID
            )
        }
    }

    private fun migrateLegacyFiles(context: Context) {
        val defaultDir = profileDir(context, DEFAULT_PROFILE_ID)

        val legacyFiles = listOf(
            "online_history.json",
            "online_bookmarks.json",
            "online_downloads.json"
        )

        legacyFiles.forEach { fileName ->
            val oldFile = File(context.filesDir, fileName)
            val newFile = File(defaultDir, fileName)

            if (oldFile.isFile && !newFile.exists()) {
                oldFile.copyTo(newFile, overwrite = false)
            }
        }
    }

    private fun readProfiles(
        prefs: android.content.SharedPreferences
    ): List<Profile> {
        return try {
            val raw = prefs.getString(KEY_REGISTRY, null)
                ?: return emptyList()

            val array = JSONArray(raw)
            val result = mutableListOf<Profile>()

            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue

                val id = item.optString("id")
                val name = item.optString("name")
                val createdAt = item.optLong("createdAt")

                if (
                    isValidId(id) &&
                    name.isNotBlank() &&
                    name.length <= 32
                ) {
                    result.add(Profile(id, name, createdAt))
                }
            }

            result.distinctBy { it.id }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun saveRegistry(
        prefs: android.content.SharedPreferences,
        profiles: List<Profile>,
        activeId: String
    ) {
        val array = JSONArray()

        profiles.forEach { profile ->
            array.put(
                JSONObject().apply {
                    put("id", profile.id)
                    put("name", profile.name)
                    put("createdAt", profile.createdAt)
                }
            )
        }

        prefs.edit()
            .putString(KEY_REGISTRY, array.toString())
            .putString(KEY_ACTIVE_PROFILE, activeId)
            .apply()
    }

    private fun isValidId(id: String): Boolean {
        return id == DEFAULT_PROFILE_ID ||
            Regex(
                "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-" +
                    "[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-" +
                    "[0-9a-fA-F]{12}$"
            ).matches(id)
    }

    private fun checkInitialized() {
        check(initialized) {
            "Call ProfileManager.initialize() first"
        }
    }
}
