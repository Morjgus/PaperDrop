package de.paperdrop.data.preferences

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore(name = "settings")

@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        val KEY_URL             = stringPreferencesKey("paperless_url")
        val KEY_TOKEN           = stringPreferencesKey("api_token")
        val KEY_FOLDER_URI      = stringPreferencesKey("watch_folder_uri")
        val KEY_AFTER_UPLOAD    = stringPreferencesKey("after_upload_action")
        val KEY_MOVE_TARGET_URI = stringPreferencesKey("move_target_uri")
        val KEY_WATCHING        = booleanPreferencesKey("watching_enabled")
        val KEY_LABEL_IDS       = stringPreferencesKey("selected_label_ids")
    }

    val settings: Flow<AppSettings> = context.dataStore.data
        .catch { e ->
            if (e is IOException) emit(emptyPreferences()) else throw e
        }
        .map { prefs ->
            AppSettings(
                paperlessUrl       = prefs[KEY_URL]             ?: "",
                apiToken           = prefs[KEY_TOKEN]           ?: "",
                watchFolderUri     = prefs[KEY_FOLDER_URI]      ?: "",
                afterUpload        = AfterUploadAction.valueOf(
                    prefs[KEY_AFTER_UPLOAD] ?: AfterUploadAction.KEEP.name
                ),
                moveTargetUri      = prefs[KEY_MOVE_TARGET_URI] ?: "",
                isWatchingEnabled  = prefs[KEY_WATCHING]        ?: false,
                selectedLabelIds   = prefs[KEY_LABEL_IDS]
                    ?.split(",")
                    ?.mapNotNull { it.trim().toIntOrNull() }
                    ?.toSet()
                    ?: emptySet()
            )
        }

    suspend fun updateUrl(url: String)                  = context.dataStore.edit { it[KEY_URL]             = url.trimEnd('/') }
    suspend fun updateToken(token: String)              = context.dataStore.edit { it[KEY_TOKEN]           = token }
    suspend fun updateFolderUri(uri: String)            = context.dataStore.edit { it[KEY_FOLDER_URI]      = uri }
    suspend fun updateAfterUpload(a: AfterUploadAction) = context.dataStore.edit { it[KEY_AFTER_UPLOAD]    = a.name }
    suspend fun updateMoveTargetUri(uri: String)        = context.dataStore.edit { it[KEY_MOVE_TARGET_URI] = uri }
    suspend fun updateWatchingEnabled(b: Boolean)       = context.dataStore.edit { it[KEY_WATCHING]        = b }
    suspend fun updateLabelIds(ids: Set<Int>)           = context.dataStore.edit { it[KEY_LABEL_IDS]       = ids.joinToString(",") }

    suspend fun getSnapshot(): AppSettings = settings.first()
}
