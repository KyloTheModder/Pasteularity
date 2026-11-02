package win.libpaste.pasteularity.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import win.libpaste.pasteularity.shell.BackendPreference

@Serializable
data class Snippet(val label: String, val command: String)

@Serializable
data class DisplayProfile(val name: String, val values: Map<String, String>)

@Serializable
data class ProximityRecord(val disabled: Boolean, val bootId: String)

private val Context.dataStore by preferencesDataStore("settings")

private val defaultSnippets = listOf(
    Snippet("Android version", "getprop ro.build.version.release"),
    Snippet("Sideloaded apps", "pm list packages -3"),
    Snippet("Storage", "df -h /sdcard"),
    Snippet("Battery", "dumpsys battery"),
)

class SettingsStore(context: Context) {
    private val store = context.applicationContext.dataStore
    private val json = Json { ignoreUnknownKeys = true }

    val backendPreference: Flow<BackendPreference> = store.data.map { prefs ->
        prefs[BACKEND]?.let { saved -> BackendPreference.entries.firstOrNull { it.name == saved } }
            ?: BackendPreference.Auto
    }.distinctUntilChanged()
    val adbPaired: Flow<Boolean> = store.data.map { it[ADB_PAIRED] ?: false }.distinctUntilChanged()
    val onboardingDone: Flow<Boolean> = store.data.map { it[ONBOARDING_DONE] ?: false }.distinctUntilChanged()
    val snippets: Flow<List<Snippet>> = saved(SNIPPETS, defaultSnippets)
    val commandHistory: Flow<List<String>> = saved(HISTORY, emptyList())
    val profiles: Flow<List<DisplayProfile>> = saved(PROFILES, emptyList())
    val proximity: Flow<ProximityRecord?> = saved(PROXIMITY, null)

    /** Decodes only when this key changes, so saving terminal history does not re-read everything else. */
    private inline fun <reified T> saved(key: Preferences.Key<String>, fallback: T): Flow<T> =
        store.data.map { it[key] }.distinctUntilChanged().map { raw ->
            raw?.let { runCatching { json.decodeFromString<T>(it) }.getOrNull() } ?: fallback
        }

    suspend fun setBackendPreference(value: BackendPreference) {
        store.edit { it[BACKEND] = value.name }
    }

    suspend fun setAdbPaired(value: Boolean) {
        store.edit { it[ADB_PAIRED] = value }
    }

    suspend fun setOnboardingDone(value: Boolean) {
        store.edit { it[ONBOARDING_DONE] = value }
    }

    suspend fun setSnippets(value: List<Snippet>) {
        store.edit { it[SNIPPETS] = json.encodeToString(value) }
    }

    suspend fun setCommandHistory(value: List<String>) {
        store.edit { it[HISTORY] = json.encodeToString(value) }
    }

    suspend fun setProfiles(value: List<DisplayProfile>) {
        store.edit { it[PROFILES] = json.encodeToString(value) }
    }

    suspend fun setProximity(value: ProximityRecord) {
        store.edit { it[PROXIMITY] = json.encodeToString(value) }
    }

    private companion object {
        val BACKEND = stringPreferencesKey("backend_preference")
        val ADB_PAIRED = booleanPreferencesKey("adb_paired")
        val ONBOARDING_DONE = booleanPreferencesKey("onboarding_done")
        val SNIPPETS = stringPreferencesKey("snippets")
        val HISTORY = stringPreferencesKey("command_history")
        val PROFILES = stringPreferencesKey("display_profiles")
        val PROXIMITY = stringPreferencesKey("proximity")
    }
}
