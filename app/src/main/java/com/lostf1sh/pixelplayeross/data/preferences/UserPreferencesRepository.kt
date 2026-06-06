package com.lostf1sh.pixelplayeross.data.preferences

import android.content.Context
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey // Added import
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.media3.common.Player
import com.lostf1sh.pixelplayeross.data.model.PlaybackQueueSnapshot
import com.lostf1sh.pixelplayeross.data.model.Playlist
import com.lostf1sh.pixelplayeross.data.model.SortOption // Added import
import com.lostf1sh.pixelplayeross.data.model.FolderSource
import com.lostf1sh.pixelplayeross.data.model.LyricsSourcePreference
import com.lostf1sh.pixelplayeross.data.model.TransitionSettings
import com.lostf1sh.pixelplayeross.data.model.StorageFilter
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.text.get
import kotlin.text.set
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(
        name = "settings",
        // If the backing settings file is unreadable/corrupt (unclean shutdown, disk error,
        // partial Platform Auto Backup restore), fall back to empty preferences instead of
        // propagating an IOException through every preference flow and crashing on launch.
        corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() }
)

object ThemePreference {
    const val DEFAULT = "default"
    const val DYNAMIC = "dynamic"
    const val ALBUM_ART = "album_art"
    const val GLOBAL = "global"
}

object AppThemeMode {
    const val FOLLOW_SYSTEM = "follow_system"
    const val LIGHT = "light"
    const val DARK = "dark"
}

const val MIN_NAV_BAR_CORNER_RADIUS = 0
const val MAX_NAV_BAR_CORNER_RADIUS = 60

internal fun sanitizeNavBarCornerRadius(radius: Int): Int =
        radius.coerceIn(MIN_NAV_BAR_CORNER_RADIUS, MAX_NAV_BAR_CORNER_RADIUS)

/**
 * Album art quality settings for developer options.
 * Controls maximum resolution for album artwork in player view.
 * Thumbnails in lists always use low resolution for performance.
 *
 * @property maxSize Maximum size in pixels (0 = original size)
 * @property label Human-readable label for UI
 */
enum class AlbumArtQuality(val maxSize: Int, val label: String) {
    LOW(256, "Low (256px) - Better performance"),
    MEDIUM(512, "Medium (512px) - Balanced"),
    HIGH(800, "High (800px) - Best quality"),
    ORIGINAL(0, "Original - Maximum quality")
}

/**
 * Expected DataStore value type for a known preference key. Used by backup import to validate the
 * backup-supplied [PreferenceBackupEntry.type] against what live code actually reads a key as, so a
 * crafted/old backup cannot write a key under a different type than the app expects (which would
 * throw ClassCastException on the next typed read).
 */
enum class PrefType {
    STRING,
    INT,
    LONG,
    BOOLEAN,
    FLOAT,
    DOUBLE,
    STRING_SET
}

/**
 * Per-key expected-type registry covering all DataStore keys owned by [UserPreferencesRepository],
 * [EqualizerPreferencesRepository] and [ThemePreferencesRepository] (they share one DataStore).
 *
 * This is the single source of truth for backup type validation. A key NOT present here is treated
 * as unknown and is restored on trust (the backup-supplied type wins) so that legitimately-restored
 * keys we forgot to register, or keys added by newer app versions, are never silently dropped on an
 * older build — i.e. the registry only *repairs/skips* known type mismatches, it never rejects
 * unknown keys.
 */
internal object PreferenceTypeRegistry {

    val expectedTypes: Map<String, PrefType> = buildMap {
        // --- UserPreferencesRepository keys ---
        put("app_rebrand_dialog_shown", PrefType.BOOLEAN)
        put("beta_05_clean_install_disclaimer_dismissed", PrefType.BOOLEAN)
        put("allowed_directories", PrefType.STRING_SET)
        put("blocked_directories", PrefType.STRING_SET)
        put("initial_setup_done", PrefType.BOOLEAN)
        put("player_theme_preference_v2", PrefType.STRING)
        put("album_art_palette_style_v1", PrefType.STRING)
        put("app_theme_mode", PrefType.STRING)
        put("favorite_song_ids", PrefType.STRING_SET)
        put("user_playlists_json_v1", PrefType.STRING)
        put("playlist_song_order_modes", PrefType.STRING)
        put("songs_sort_option", PrefType.STRING)
        put("songs_sort_option_migrated_v2", PrefType.BOOLEAN)
        put("albums_sort_option", PrefType.STRING)
        put("artists_sort_option", PrefType.STRING)
        put("playlists_sort_option", PrefType.STRING)
        put("folders_sort_option", PrefType.STRING)
        put("liked_songs_sort_option", PrefType.STRING)
        put("last_library_tab_index", PrefType.INT)
        put("last_storage_filter", PrefType.STRING)
        put("mock_genres_enabled", PrefType.BOOLEAN)
        put("last_daily_mix_update", PrefType.LONG)
        put("daily_mix_song_ids", PrefType.STRING)
        put("your_mix_song_ids", PrefType.STRING)
        put("nav_bar_corner_radius", PrefType.INT)
        put("nav_bar_style", PrefType.STRING)
        put("nav_bar_compact_mode", PrefType.BOOLEAN)
        put("carousel_style", PrefType.STRING)
        put("library_navigation_mode", PrefType.STRING)
        put("launch_tab", PrefType.STRING)
        put("global_transition_settings_json", PrefType.STRING)
        put("library_tabs_order", PrefType.STRING)
        put("is_folder_filter_active", PrefType.BOOLEAN)
        put("is_folders_playlist_view", PrefType.BOOLEAN)
        put("hide_local_media", PrefType.BOOLEAN)
        put("folders_source", PrefType.STRING)
        put("folder_back_gesture_navigation", PrefType.BOOLEAN)
        put("use_smooth_corners", PrefType.BOOLEAN)
        put("keep_playing_in_background", PrefType.BOOLEAN)
        put("is_crossfade_enabled", PrefType.BOOLEAN)
        put("hi_fi_mode_enabled", PrefType.BOOLEAN)
        put("crossfade_duration", PrefType.INT)
        put("playback_speed", PrefType.FLOAT)
        put("custom_genres", PrefType.STRING_SET)
        put("custom_genre_icons", PrefType.STRING)
        put("repeat_mode", PrefType.INT)
        put("is_shuffle_on", PrefType.BOOLEAN)
        put("persistent_shuffle_enabled", PrefType.BOOLEAN)
        put("resume_on_headset_reconnect", PrefType.BOOLEAN)
        put("show_queue_history", PrefType.BOOLEAN)
        put("playback_queue_snapshot_v1", PrefType.STRING)
        put("full_player_show_file_info", PrefType.BOOLEAN)
        put("full_player_delay_all", PrefType.BOOLEAN)
        put("full_player_delay_album", PrefType.BOOLEAN)
        put("full_player_delay_metadata", PrefType.BOOLEAN)
        put("full_player_delay_progress", PrefType.BOOLEAN)
        put("full_player_delay_controls", PrefType.BOOLEAN)
        put("full_player_placeholders", PrefType.BOOLEAN)
        put("full_player_placeholder_transparent", PrefType.BOOLEAN)
        put("full_player_placeholders_on_close", PrefType.BOOLEAN)
        put("full_player_switch_on_drag_release", PrefType.BOOLEAN)
        put("full_player_delay_threshold_percent", PrefType.INT)
        put("full_player_close_threshold_percent", PrefType.INT)
        put("use_player_sheet_v2", PrefType.BOOLEAN)
        put("artist_delimiters", PrefType.STRING)
        put("artist_word_delimiters", PrefType.STRING)
        put("extract_artists_from_title", PrefType.BOOLEAN)
        put("group_by_album_artist", PrefType.BOOLEAN)
        put("artist_settings_rescan_required", PrefType.BOOLEAN)
        put("backup_info_dismissed", PrefType.BOOLEAN)
        put("last_sync_timestamp", PrefType.LONG)
        put("directory_rules_version", PrefType.INT)
        put("last_applied_directory_rules_version", PrefType.INT)
        put("lyrics_sync_offsets_json", PrefType.STRING)
        put("lyrics_source_preference", PrefType.STRING)
        put("auto_scan_lrc_files", PrefType.BOOLEAN)
        put("external_lyrics_enabled", PrefType.BOOLEAN)
        put("external_artist_images_enabled", PrefType.BOOLEAN)
        put("album_art_quality", PrefType.STRING)
        put("album_art_cache_limit_mb", PrefType.INT)
        put("tap_background_closes_player", PrefType.BOOLEAN)
        put("haptics_enabled", PrefType.BOOLEAN)
        put("immersive_lyrics_enabled", PrefType.BOOLEAN)
        put("immersive_lyrics_timeout", PrefType.LONG)
        put("use_animated_lyrics", PrefType.BOOLEAN)
        put("animated_lyrics_blur_enabled", PrefType.BOOLEAN)
        put("animated_lyrics_blur_strength", PrefType.FLOAT)
        put("is_genre_grid_view", PrefType.BOOLEAN)
        put("is_albums_list_view", PrefType.BOOLEAN)
        put("collage_pattern", PrefType.STRING)
        put("collage_auto_rotate", PrefType.BOOLEAN)
        put("last_playlist_id", PrefType.STRING)
        put("last_playlist_name", PrefType.STRING)
        put("min_song_duration_ms", PrefType.INT)
        put("min_tracks_per_album", PrefType.INT)
        put("replaygain_enabled", PrefType.BOOLEAN)
        put("replaygain_use_album_gain", PrefType.BOOLEAN)

        // --- EqualizerPreferencesRepository keys ---
        put("equalizer_enabled", PrefType.BOOLEAN)
        put("equalizer_preset", PrefType.STRING)
        put("equalizer_custom_bands", PrefType.STRING)
        put("bass_boost_strength", PrefType.INT)
        put("virtualizer_strength", PrefType.INT)
        put("bass_boost_enabled", PrefType.BOOLEAN)
        put("virtualizer_enabled", PrefType.BOOLEAN)
        put("loudness_enhancer_enabled", PrefType.BOOLEAN)
        put("loudness_enhancer_strength", PrefType.INT)
        put("bass_boost_dismissed", PrefType.BOOLEAN)
        put("virtualizer_dismissed", PrefType.BOOLEAN)
        put("loudness_dismissed", PrefType.BOOLEAN)
        put("equalizer_view_mode", PrefType.STRING)
        put("custom_presets_json", PrefType.STRING)
        put("pinned_presets_json", PrefType.STRING)
        put("is_graph_view", PrefType.BOOLEAN) // legacy fallback key still read by EqualizerPreferencesRepository

        // --- ThemePreferencesRepository keys (player_theme_preference_v2 / album_art_palette_style_v1
        //     / app_theme_mode already registered above; only the unique one is added here) ---
        put("album_art_color_accuracy_v1", PrefType.INT)
    }

    /** The backup-entry `type` discriminator string corresponding to each [PrefType]. */
    fun wireName(type: PrefType): String = when (type) {
        PrefType.STRING -> "string"
        PrefType.INT -> "int"
        PrefType.LONG -> "long"
        PrefType.BOOLEAN -> "boolean"
        PrefType.FLOAT -> "float"
        PrefType.DOUBLE -> "double"
        PrefType.STRING_SET -> "string_set"
    }
}

/**
 * Keys that hold secrets / device-specific endpoints which must be redacted from a backup export.
 * Matched by substring (case-insensitive) so future token/secret/password/URL keys are caught
 * automatically. This is an opt-in redaction filter layered on top of [backupExcludedKeyNames].
 */
private val SECRET_KEY_SUBSTRINGS = listOf("token", "secret", "password", "url")

private fun isLikelySecretKey(keyName: String): Boolean {
    val lower = keyName.lowercase()
    return SECRET_KEY_SUBSTRINGS.any { lower.contains(it) }
}

// Narrowing guards for backup restore: reject values that would overflow the target type or lose
// integrality (e.g. a tampered/corrupt backup carrying 1e300 or 3.7 under an int key), returning
// null so the caller skips the entry instead of silently writing a truncated/saturated value.
private fun Double.toIntExactPrefOrNull(): Int? =
    takeIf { it.isFinite() && it >= Int.MIN_VALUE.toDouble() && it <= Int.MAX_VALUE.toDouble() && it % 1.0 == 0.0 }
        ?.toInt()

private fun Double.toLongExactPrefOrNull(): Long? =
    takeIf { it.isFinite() && it >= Long.MIN_VALUE.toDouble() && it <= Long.MAX_VALUE.toDouble() && it % 1.0 == 0.0 }
        ?.toLong()

private fun Long.toIntRangedPrefOrNull(): Int? =
    takeIf { it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() }?.toInt()

private fun Double.toFloatFinitePrefOrNull(): Float? {
    if (!isFinite()) return null
    return toFloat().takeIf { it.isFinite() }
}

@Singleton
class UserPreferencesRepository
@Inject
constructor(
        private val dataStore: DataStore<Preferences>,
        private val json: Json // Inject Json for serialization
) {

    private val backupExcludedKeyNames = setOf(
        PreferencesKeys.INITIAL_SETUP_DONE.name,
        // Legacy scrobbling secrets from removed ListenBrainz/Last.fm support.
        "listenbrainz_token",
        "lastfm_session_key"
    )

    // Transient/derived runtime state that is not really a user "setting". A restore that wipes
    // existing prefs must NOT clear these: doing so drops the resume-playback queue and resets
    // the directory-rules/sync version markers, forcing a surprising full library re-sync. They
    // are still imported normally if the backup actually carries them; this set only protects
    // them from the clear step when the backup omits them.
    private val restorePreservedKeyNames = setOf(
        PreferencesKeys.PLAYBACK_QUEUE_SNAPSHOT.name,
        PreferencesKeys.LAST_SYNC_TIMESTAMP.name,
        PreferencesKeys.DIRECTORY_RULES_VERSION.name,
        PreferencesKeys.LAST_APPLIED_DIRECTORY_RULES_VERSION.name,
        PreferencesKeys.LAST_DAILY_MIX_UPDATE.name
    )

    private object PreferencesKeys {
        val APP_REBRAND_DIALOG_SHOWN = booleanPreferencesKey("app_rebrand_dialog_shown")
        val BETA_05_CLEAN_INSTALL_DISCLAIMER_DISMISSED =
            booleanPreferencesKey("beta_05_clean_install_disclaimer_dismissed")
        val ALLOWED_DIRECTORIES = stringSetPreferencesKey("allowed_directories")
        val BLOCKED_DIRECTORIES = stringSetPreferencesKey("blocked_directories")
        val INITIAL_SETUP_DONE = booleanPreferencesKey("initial_setup_done")
        // val GLOBAL_THEME_PREFERENCE = stringPreferencesKey("global_theme_preference_v2") //
        // Removed
        val PLAYER_THEME_PREFERENCE = stringPreferencesKey("player_theme_preference_v2")
        val ALBUM_ART_PALETTE_STYLE = stringPreferencesKey("album_art_palette_style_v1")
        val APP_THEME_MODE = stringPreferencesKey("app_theme_mode")
        val FAVORITE_SONG_IDS = stringSetPreferencesKey("favorite_song_ids")
        val USER_PLAYLISTS = stringPreferencesKey("user_playlists_json_v1")
        val PLAYLIST_SONG_ORDER_MODES = stringPreferencesKey("playlist_song_order_modes")

        // Sort Option Keys
        val SONGS_SORT_OPTION = stringPreferencesKey("songs_sort_option")
        val SONGS_SORT_OPTION_MIGRATED = booleanPreferencesKey("songs_sort_option_migrated_v2")
        val ALBUMS_SORT_OPTION = stringPreferencesKey("albums_sort_option")
        val ARTISTS_SORT_OPTION = stringPreferencesKey("artists_sort_option")
        val PLAYLISTS_SORT_OPTION = stringPreferencesKey("playlists_sort_option")
        val FOLDERS_SORT_OPTION = stringPreferencesKey("folders_sort_option")
        val LIKED_SONGS_SORT_OPTION = stringPreferencesKey("liked_songs_sort_option")

        // UI State Keys
        val LAST_LIBRARY_TAB_INDEX =
                intPreferencesKey("last_library_tab_index") // Corrected: Add intPreferencesKey here
        val LAST_STORAGE_FILTER = stringPreferencesKey("last_storage_filter")
        val MOCK_GENRES_ENABLED = booleanPreferencesKey("mock_genres_enabled")
        val LAST_DAILY_MIX_UPDATE = longPreferencesKey("last_daily_mix_update")
        val DAILY_MIX_SONG_IDS = stringPreferencesKey("daily_mix_song_ids")
        val YOUR_MIX_SONG_IDS = stringPreferencesKey("your_mix_song_ids")
        val NAV_BAR_CORNER_RADIUS = intPreferencesKey("nav_bar_corner_radius")
        val NAV_BAR_STYLE = stringPreferencesKey("nav_bar_style")
        val NAV_BAR_COMPACT_MODE = booleanPreferencesKey("nav_bar_compact_mode")
        val CAROUSEL_STYLE = stringPreferencesKey("carousel_style")
        val LIBRARY_NAVIGATION_MODE = stringPreferencesKey("library_navigation_mode")
        val LAUNCH_TAB = stringPreferencesKey("launch_tab")

        // Transition Settings
        val GLOBAL_TRANSITION_SETTINGS = stringPreferencesKey("global_transition_settings_json")
        val LIBRARY_TABS_ORDER = stringPreferencesKey("library_tabs_order")
        val IS_FOLDER_FILTER_ACTIVE = booleanPreferencesKey("is_folder_filter_active")
        val IS_FOLDERS_PLAYLIST_VIEW = booleanPreferencesKey("is_folders_playlist_view")
        val HIDE_LOCAL_MEDIA = booleanPreferencesKey("hide_local_media")
        val FOLDERS_SOURCE = stringPreferencesKey("folders_source")
        val FOLDER_BACK_GESTURE_NAVIGATION = booleanPreferencesKey("folder_back_gesture_navigation")
        val USE_SMOOTH_CORNERS = booleanPreferencesKey("use_smooth_corners")
        val KEEP_PLAYING_IN_BACKGROUND = booleanPreferencesKey("keep_playing_in_background")
        val IS_CROSSFADE_ENABLED = booleanPreferencesKey("is_crossfade_enabled")
        val HI_FI_MODE_ENABLED = booleanPreferencesKey("hi_fi_mode_enabled")
        val CROSSFADE_DURATION = intPreferencesKey("crossfade_duration")
        val PLAYBACK_SPEED = androidx.datastore.preferences.core.floatPreferencesKey("playback_speed")
        val CUSTOM_GENRES = androidx.datastore.preferences.core.stringSetPreferencesKey("custom_genres")
        val CUSTOM_GENRE_ICONS = stringPreferencesKey("custom_genre_icons") // JSON Map<String, Int>
        val REPEAT_MODE = intPreferencesKey("repeat_mode")
        val IS_SHUFFLE_ON = booleanPreferencesKey("is_shuffle_on")
        val PERSISTENT_SHUFFLE_ENABLED = booleanPreferencesKey("persistent_shuffle_enabled")
        val RESUME_ON_HEADSET_RECONNECT = booleanPreferencesKey("resume_on_headset_reconnect")
        val SHOW_QUEUE_HISTORY = booleanPreferencesKey("show_queue_history")
        val PLAYBACK_QUEUE_SNAPSHOT = stringPreferencesKey("playback_queue_snapshot_v1")
        val FULL_PLAYER_SHOW_FILE_INFO = booleanPreferencesKey("full_player_show_file_info")
        val FULL_PLAYER_DELAY_ALL = booleanPreferencesKey("full_player_delay_all")
        val FULL_PLAYER_DELAY_ALBUM = booleanPreferencesKey("full_player_delay_album")
        val FULL_PLAYER_DELAY_METADATA = booleanPreferencesKey("full_player_delay_metadata")
        val FULL_PLAYER_DELAY_PROGRESS = booleanPreferencesKey("full_player_delay_progress")
        val FULL_PLAYER_DELAY_CONTROLS = booleanPreferencesKey("full_player_delay_controls")
        val FULL_PLAYER_PLACEHOLDERS = booleanPreferencesKey("full_player_placeholders")
        val FULL_PLAYER_PLACEHOLDER_TRANSPARENT = booleanPreferencesKey("full_player_placeholder_transparent")
        val FULL_PLAYER_PLACEHOLDERS_ON_CLOSE = booleanPreferencesKey("full_player_placeholders_on_close")
        val FULL_PLAYER_SWITCH_ON_DRAG_RELEASE = booleanPreferencesKey("full_player_switch_on_drag_release")
        val FULL_PLAYER_DELAY_THRESHOLD = intPreferencesKey("full_player_delay_threshold_percent")
        val FULL_PLAYER_CLOSE_THRESHOLD = intPreferencesKey("full_player_close_threshold_percent")
        // Deprecated experiment key kept only for one-time cleanup after removing the legacy player sheet.
        val USE_PLAYER_SHEET_V2 = booleanPreferencesKey("use_player_sheet_v2")

        // Multi-Artist Settings
        val ARTIST_DELIMITERS = stringPreferencesKey("artist_delimiters")
        val ARTIST_WORD_DELIMITERS = stringPreferencesKey("artist_word_delimiters")
        val EXTRACT_ARTISTS_FROM_TITLE = booleanPreferencesKey("extract_artists_from_title")
        val GROUP_BY_ALBUM_ARTIST = booleanPreferencesKey("group_by_album_artist")
        val ARTIST_SETTINGS_RESCAN_REQUIRED =
                booleanPreferencesKey("artist_settings_rescan_required")

        // Equalizer settings (enabled/preset/custom bands, bass boost, virtualizer, loudness
        // enhancer, dismissed-warning flags, view mode, custom/pinned presets) are owned solely
        // by EqualizerPreferencesRepository over the same DataStore. Their key declarations used
        // to be duplicated here but were dead (no flow/setter referenced them); they were removed
        // to keep EqualizerPreferencesRepository the single owner of those key names.

        // Dismissed Warning States
        val BACKUP_INFO_DISMISSED = booleanPreferencesKey("backup_info_dismissed")

        // Library Sync
        val LAST_SYNC_TIMESTAMP = longPreferencesKey("last_sync_timestamp")
        val DIRECTORY_RULES_VERSION = intPreferencesKey("directory_rules_version")
        val LAST_APPLIED_DIRECTORY_RULES_VERSION =
            intPreferencesKey("last_applied_directory_rules_version")

        // Lyrics Sync Offset per song (Map<songId, offsetMs> as JSON)
        val LYRICS_SYNC_OFFSETS = stringPreferencesKey("lyrics_sync_offsets_json")

        // Lyrics Source Preference
        val LYRICS_SOURCE_PREFERENCE = stringPreferencesKey("lyrics_source_preference")
        val AUTO_SCAN_LRC_FILES = booleanPreferencesKey("auto_scan_lrc_files")
        val EXTERNAL_LYRICS_ENABLED = booleanPreferencesKey("external_lyrics_enabled")
        val EXTERNAL_ARTIST_IMAGES_ENABLED = booleanPreferencesKey("external_artist_images_enabled")

        // Developer Options
        val ALBUM_ART_QUALITY = stringPreferencesKey("album_art_quality")
        val ALBUM_ART_CACHE_LIMIT_MB = intPreferencesKey("album_art_cache_limit_mb")
        val TAP_BACKGROUND_CLOSES_PLAYER = booleanPreferencesKey("tap_background_closes_player")
        val HAPTICS_ENABLED = booleanPreferencesKey("haptics_enabled")
        val IMMERSIVE_LYRICS_ENABLED = booleanPreferencesKey("immersive_lyrics_enabled")
        val IMMERSIVE_LYRICS_TIMEOUT = longPreferencesKey("immersive_lyrics_timeout")
        val USE_ANIMATED_LYRICS = booleanPreferencesKey("use_animated_lyrics")
        val ANIMATED_LYRICS_BLUR_ENABLED = booleanPreferencesKey("animated_lyrics_blur_enabled")
        val ANIMATED_LYRICS_BLUR_STRENGTH = androidx.datastore.preferences.core.floatPreferencesKey("animated_lyrics_blur_strength")

        // Genre View Preference
        val IS_GENRE_GRID_VIEW = booleanPreferencesKey("is_genre_grid_view")

        // Album View Preference
        val IS_ALBUMS_LIST_VIEW = booleanPreferencesKey("is_albums_list_view")

        // Collage Pattern
        val COLLAGE_PATTERN = stringPreferencesKey("collage_pattern")
        val COLLAGE_AUTO_ROTATE = booleanPreferencesKey("collage_auto_rotate")

        // Quick Settings / Last Playlist
        val LAST_PLAYLIST_ID = stringPreferencesKey("last_playlist_id")
        val LAST_PLAYLIST_NAME = stringPreferencesKey("last_playlist_name")

        // Smart Duration Filtering
        val MIN_SONG_DURATION = intPreferencesKey("min_song_duration_ms")

        // Album Tracks Filtering
        val MIN_TRACKS_PER_ALBUM = intPreferencesKey("min_tracks_per_album")

        // ReplayGain
        val REPLAYGAIN_ENABLED = booleanPreferencesKey("replaygain_enabled")
        val REPLAYGAIN_USE_ALBUM_GAIN = booleanPreferencesKey("replaygain_use_album_gain")
    }

    val appRebrandDialogShownFlow: Flow<Boolean> =
            dataStore.data.map { preferences ->
                preferences[PreferencesKeys.APP_REBRAND_DIALOG_SHOWN] ?: false
            }

    suspend fun setAppRebrandDialogShown(wasShown: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.APP_REBRAND_DIALOG_SHOWN] = wasShown
        }
    }

    val beta05CleanInstallDisclaimerDismissedFlow: Flow<Boolean> =
        dataStore.data.map { preferences ->
            preferences[PreferencesKeys.BETA_05_CLEAN_INSTALL_DISCLAIMER_DISMISSED] ?: false
        }

    suspend fun setBeta05CleanInstallDisclaimerDismissed(dismissed: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.BETA_05_CLEAN_INSTALL_DISCLAIMER_DISMISSED] = dismissed
        }
    }

    val isCrossfadeEnabledFlow: Flow<Boolean> =
            dataStore.data.map { preferences ->
                preferences[PreferencesKeys.IS_CROSSFADE_ENABLED] ?: false
            }

    suspend fun setCrossfadeEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.IS_CROSSFADE_ENABLED] = enabled
        }
    }

    val hiFiModeEnabledFlow: Flow<Boolean> =
        dataStore.data.map { preferences ->
            preferences[PreferencesKeys.HI_FI_MODE_ENABLED] ?: false
        }

    suspend fun setHiFiModeEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.HI_FI_MODE_ENABLED] = enabled
        }
    }

    val backupInfoDismissedFlow: Flow<Boolean> = dataStore.data.map { preferences ->
        preferences[PreferencesKeys.BACKUP_INFO_DISMISSED] ?: false
    }

    suspend fun setBackupInfoDismissed(dismissed: Boolean) {
        dataStore.edit { preferences -> preferences[PreferencesKeys.BACKUP_INFO_DISMISSED] = dismissed }
    }

    enum class EqualizerViewMode {
        SLIDERS, GRAPH, HYBRID
    }

    val crossfadeDurationFlow: Flow<Int> =
            dataStore.data.map { preferences ->
                (preferences[PreferencesKeys.CROSSFADE_DURATION] ?: 2000).coerceIn(1000, 12000)
            }

    suspend fun setCrossfadeDuration(duration: Int) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.CROSSFADE_DURATION] = duration.coerceIn(1000, 12000)
        }
    }

    val playbackSpeedFlow: Flow<Float> =
            dataStore.data.map { preferences ->
                (preferences[PreferencesKeys.PLAYBACK_SPEED] ?: 1.0f).coerceIn(0.25f, 3.0f)
            }

    suspend fun setPlaybackSpeed(speed: Float) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.PLAYBACK_SPEED] = speed.coerceIn(0.25f, 3.0f)
        }
    }

    // Custom Genres Names
    val customGenresFlow: Flow<Set<String>> =
        dataStore.data.map { preferences ->
            preferences[PreferencesKeys.CUSTOM_GENRES] ?: emptySet()
        }

    // Custom Genres Icons (JSON Map: Name -> ResId)
    val customGenreIconsFlow: Flow<Map<String, Int>> =
        dataStore.data.map { preferences ->
            val jsonString = preferences[PreferencesKeys.CUSTOM_GENRE_ICONS]
            if (jsonString != null) {
                try {
                    json.decodeFromString<Map<String, Int>>(jsonString)
                } catch (e: Exception) {
                    emptyMap()
                }
            } else {
                emptyMap()
            }
        }

    suspend fun addCustomGenre(genre: String, iconResId: Int? = null) {
        dataStore.edit { preferences ->
            val currentGenres = preferences[PreferencesKeys.CUSTOM_GENRES] ?: emptySet()
            preferences[PreferencesKeys.CUSTOM_GENRES] = currentGenres + genre

            if (iconResId != null) {
                val currentIconsJson = preferences[PreferencesKeys.CUSTOM_GENRE_ICONS]
                val currentIcons = if (currentIconsJson != null) {
                    try {
                        json.decodeFromString<Map<String, Int>>(currentIconsJson)
                    } catch (e: Exception) {
                        emptyMap()
                    }
                } else {
                    emptyMap()
                }

                val newIcons = currentIcons.toMutableMap()
                newIcons[genre] = iconResId
                preferences[PreferencesKeys.CUSTOM_GENRE_ICONS] = json.encodeToString(newIcons)
            }
        }
    }
    val repeatModeFlow: Flow<Int> =
            dataStore.data.map { preferences ->
                preferences[PreferencesKeys.REPEAT_MODE] ?: Player.REPEAT_MODE_OFF
            }

    suspend fun setRepeatMode(@Player.RepeatMode mode: Int) {
        dataStore.edit { preferences -> preferences[PreferencesKeys.REPEAT_MODE] = mode }
    }

    val isShuffleOnFlow: Flow<Boolean> =
            dataStore.data.map { preferences ->
                preferences[PreferencesKeys.IS_SHUFFLE_ON] ?: false
            }

    suspend fun setShuffleOn(on: Boolean) {
        dataStore.edit { preferences -> preferences[PreferencesKeys.IS_SHUFFLE_ON] = on }
    }

    val persistentShuffleEnabledFlow: Flow<Boolean> =
            dataStore.data.map { preferences ->
                preferences[PreferencesKeys.PERSISTENT_SHUFFLE_ENABLED] ?: false
            }

    suspend fun setPersistentShuffleEnabled(enabled: Boolean) {
        dataStore.edit { preferences -> preferences[PreferencesKeys.PERSISTENT_SHUFFLE_ENABLED] = enabled }
    }

    val playbackQueueSnapshotFlow: Flow<PlaybackQueueSnapshot?> =
            dataStore.data.map { preferences ->
                preferences[PreferencesKeys.PLAYBACK_QUEUE_SNAPSHOT]?.let { raw ->
                    runCatching { json.decodeFromString<PlaybackQueueSnapshot>(raw) }.getOrNull()
                }
            }

    suspend fun getPlaybackQueueSnapshotOnce(): PlaybackQueueSnapshot? {
        return playbackQueueSnapshotFlow.first()
    }

    suspend fun setPlaybackQueueSnapshot(snapshot: PlaybackQueueSnapshot?) {
        dataStore.edit { preferences ->
            if (snapshot == null || snapshot.items.isEmpty()) {
                preferences.remove(PreferencesKeys.PLAYBACK_QUEUE_SNAPSHOT)
            } else {
                preferences[PreferencesKeys.PLAYBACK_QUEUE_SNAPSHOT] = json.encodeToString(snapshot)
            }
        }
    }

    // ===== Multi-Artist Settings =====

    val artistDelimitersFlow: Flow<List<String>> =
            dataStore.data.map { preferences ->
                val stored = preferences[PreferencesKeys.ARTIST_DELIMITERS]
                if (stored != null) {
                    try {
                        json.decodeFromString<List<String>>(stored)
                    } catch (e: Exception) {
                        DEFAULT_ARTIST_DELIMITERS
                    }
                } else {
                    DEFAULT_ARTIST_DELIMITERS
                }
            }

    suspend fun setArtistDelimiters(delimiters: List<String>) {
        // Ensure at least one delimiter is always maintained
        if (delimiters.isEmpty()) {
            return
        }

        dataStore.edit { preferences ->
            preferences[PreferencesKeys.ARTIST_DELIMITERS] = json.encodeToString(delimiters)
            // Mark rescan as required when delimiters change
            preferences[PreferencesKeys.ARTIST_SETTINGS_RESCAN_REQUIRED] = true
        }
    }

    suspend fun resetArtistDelimitersToDefault() {
        setArtistDelimiters(DEFAULT_ARTIST_DELIMITERS)
    }

    val artistWordDelimitersFlow: Flow<List<String>> =
        dataStore.data.map { preferences ->
            val stored = preferences[PreferencesKeys.ARTIST_WORD_DELIMITERS]
            if (stored != null) {
                try {
                    json.decodeFromString<List<String>>(stored)
                } catch (e: Exception) {
                    DEFAULT_ARTIST_WORD_DELIMITERS
                }
            } else {
                DEFAULT_ARTIST_WORD_DELIMITERS
            }
        }

    suspend fun setArtistWordDelimiters(delimiters: List<String>) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.ARTIST_WORD_DELIMITERS] = json.encodeToString(delimiters)
            preferences[PreferencesKeys.ARTIST_SETTINGS_RESCAN_REQUIRED] = true
        }
    }

    suspend fun resetArtistWordDelimitersToDefault() {
        setArtistWordDelimiters(DEFAULT_ARTIST_WORD_DELIMITERS)
    }

    val extractArtistsFromTitleFlow: Flow<Boolean> =
        dataStore.data.map { preferences ->
            preferences[PreferencesKeys.EXTRACT_ARTISTS_FROM_TITLE] ?: true // Enabled by default
        }

    suspend fun setExtractArtistsFromTitle(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.EXTRACT_ARTISTS_FROM_TITLE] = enabled
            preferences[PreferencesKeys.ARTIST_SETTINGS_RESCAN_REQUIRED] = true
        }
    }

    val groupByAlbumArtistFlow: Flow<Boolean> =
            dataStore.data.map { preferences ->
                preferences[PreferencesKeys.GROUP_BY_ALBUM_ARTIST] ?: false
            }

    suspend fun setGroupByAlbumArtist(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.GROUP_BY_ALBUM_ARTIST] = enabled
            // Mark rescan as required when this setting changes
            preferences[PreferencesKeys.ARTIST_SETTINGS_RESCAN_REQUIRED] = true
        }
    }

    val artistSettingsRescanRequiredFlow: Flow<Boolean> =
            dataStore.data.map { preferences ->
                preferences[PreferencesKeys.ARTIST_SETTINGS_RESCAN_REQUIRED] ?: false
            }

    suspend fun clearArtistSettingsRescanRequired() {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.ARTIST_SETTINGS_RESCAN_REQUIRED] = false
        }
    }

    // ===== Library Sync Settings =====

    val lastSyncTimestampFlow: Flow<Long> =
            dataStore.data.map { preferences ->
                preferences[PreferencesKeys.LAST_SYNC_TIMESTAMP] ?: 0L
            }

    val directoryRulesVersionFlow: Flow<Int> =
            dataStore.data.map { preferences ->
                preferences[PreferencesKeys.DIRECTORY_RULES_VERSION] ?: 0
            }

    val lastAppliedDirectoryRulesVersionFlow: Flow<Int> =
            dataStore.data.map { preferences ->
                preferences[PreferencesKeys.LAST_APPLIED_DIRECTORY_RULES_VERSION] ?: 0
            }

    suspend fun getLastSyncTimestamp(): Long {
        return lastSyncTimestampFlow.first()
    }

    suspend fun getDirectoryRulesVersion(): Int {
        return directoryRulesVersionFlow.first()
    }

    suspend fun getLastAppliedDirectoryRulesVersion(): Int {
        return lastAppliedDirectoryRulesVersionFlow.first()
    }

    suspend fun setLastSyncTimestamp(timestamp: Long) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.LAST_SYNC_TIMESTAMP] = timestamp
        }
    }

    suspend fun markDirectoryRulesVersionApplied(version: Int) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.LAST_APPLIED_DIRECTORY_RULES_VERSION] = version
        }
    }

    // ===== End Library Sync Settings =====

    // ===== Lyrics Sync Offset Settings =====

    /**
     * Lyrics sync offset per song in milliseconds.
     * Stored as a JSON map: { "songId": offsetMs, ... }
     * Positive values = lyrics appear later (use when lyrics are ahead of audio)
     * Negative values = lyrics appear earlier (use when lyrics are behind audio)
     */
    private val lyricsSyncOffsetsFlow: Flow<Map<String, Int>> =
            dataStore.data.map { preferences ->
                preferences[PreferencesKeys.LYRICS_SYNC_OFFSETS]?.let { jsonString ->
                    try {
                        json.decodeFromString<Map<String, Int>>(jsonString)
                    } catch (e: Exception) {
                        emptyMap()
                    }
                } ?: emptyMap()
            }

    fun getLyricsSyncOffsetFlow(songId: String): Flow<Int> {
        return lyricsSyncOffsetsFlow.map { offsets -> offsets[songId] ?: 0 }
    }

    suspend fun getLyricsSyncOffset(songId: String): Int {
        return getLyricsSyncOffsetFlow(songId).first()
    }

    suspend fun setLyricsSyncOffset(songId: String, offsetMs: Int) {
        dataStore.edit { preferences ->
            val currentOffsets = preferences[PreferencesKeys.LYRICS_SYNC_OFFSETS]?.let { jsonString ->
                try {
                    json.decodeFromString<Map<String, Int>>(jsonString).toMutableMap()
                } catch (e: Exception) {
                    mutableMapOf()
                }
            } ?: mutableMapOf()

            if (offsetMs == 0) {
                currentOffsets.remove(songId) // Don't store default value
            } else {
                currentOffsets[songId] = offsetMs
            }

            preferences[PreferencesKeys.LYRICS_SYNC_OFFSETS] = json.encodeToString(currentOffsets)
        }
    }

    // ===== End Lyrics Sync Offset Settings =====

    // ===== Lyrics Source Preference Settings =====

    val lyricsSourcePreferenceFlow: Flow<LyricsSourcePreference> =
            dataStore.data.map { preferences ->
                LyricsSourcePreference.fromName(preferences[PreferencesKeys.LYRICS_SOURCE_PREFERENCE])
            }

    suspend fun setLyricsSourcePreference(preference: LyricsSourcePreference) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.LYRICS_SOURCE_PREFERENCE] = preference.name
        }
    }

    val externalLyricsEnabledFlow: Flow<Boolean> =
            dataStore.data.map { preferences ->
                preferences[PreferencesKeys.EXTERNAL_LYRICS_ENABLED] ?: false
            }.distinctUntilChanged()

    suspend fun setExternalLyricsEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.EXTERNAL_LYRICS_ENABLED] = enabled
            if (!enabled && preferences[PreferencesKeys.LYRICS_SOURCE_PREFERENCE] == LyricsSourcePreference.API_FIRST.name) {
                preferences[PreferencesKeys.LYRICS_SOURCE_PREFERENCE] = LyricsSourcePreference.EMBEDDED_FIRST.name
            }
        }
    }

    val externalArtistImagesEnabledFlow: Flow<Boolean> =
            dataStore.data.map { preferences ->
                preferences[PreferencesKeys.EXTERNAL_ARTIST_IMAGES_ENABLED] ?: false
            }.distinctUntilChanged()

    suspend fun setExternalArtistImagesEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.EXTERNAL_ARTIST_IMAGES_ENABLED] = enabled
        }
    }

    val autoScanLrcFilesFlow: Flow<Boolean> =
            dataStore.data.map { preferences ->
                preferences[PreferencesKeys.AUTO_SCAN_LRC_FILES] ?: false
            }

    suspend fun setAutoScanLrcFiles(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.AUTO_SCAN_LRC_FILES] = enabled
        }
    }

    val immersiveLyricsEnabledFlow: Flow<Boolean> =
            dataStore.data.map { preferences ->
                preferences[PreferencesKeys.IMMERSIVE_LYRICS_ENABLED] ?: false
            }

    val immersiveLyricsTimeoutFlow: Flow<Long> =
            dataStore.data.map { preferences ->
                preferences[PreferencesKeys.IMMERSIVE_LYRICS_TIMEOUT] ?: 4000L
            }

    suspend fun setImmersiveLyricsEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.IMMERSIVE_LYRICS_ENABLED] = enabled
        }
    }

    suspend fun setImmersiveLyricsTimeout(timeout: Long) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.IMMERSIVE_LYRICS_TIMEOUT] = timeout
        }
    }

    // ===== End Lyrics Source Preference Settings =====

    // ===== End Multi-Artist Settings =====

    val globalTransitionSettingsFlow: Flow<TransitionSettings> =
            dataStore.data.map { preferences ->
                val duration = (preferences[PreferencesKeys.CROSSFADE_DURATION] ?: 2000).coerceIn(1000, 12000)
                val settings =
                        preferences[PreferencesKeys.GLOBAL_TRANSITION_SETTINGS]?.let { jsonString ->
                            try {
                                json.decodeFromString<TransitionSettings>(jsonString)
                            } catch (e: Exception) {
                                TransitionSettings() // Return default on error
                            }
                        }
                                ?: TransitionSettings() // Return default if not set

                settings.copy(durationMs = duration)
            }

    suspend fun saveGlobalTransitionSettings(settings: TransitionSettings) {
        dataStore.edit { preferences ->
            val jsonString = json.encodeToString(settings)
            preferences[PreferencesKeys.GLOBAL_TRANSITION_SETTINGS] = jsonString
        }
    }

    val dailyMixSongIdsFlow: Flow<List<String>> =
            dataStore.data.map { preferences ->
                val jsonString = preferences[PreferencesKeys.DAILY_MIX_SONG_IDS]
                if (jsonString != null) {
                    try {
                        json.decodeFromString<List<String>>(jsonString)
                    } catch (e: Exception) {
                        emptyList()
                    }
                } else {
                    emptyList()
                }
            }

    suspend fun saveDailyMixSongIds(songIds: List<String>) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.DAILY_MIX_SONG_IDS] = json.encodeToString(songIds)
        }
    }

    val yourMixSongIdsFlow: Flow<List<String>> =
            dataStore.data.map { preferences ->
                val jsonString = preferences[PreferencesKeys.YOUR_MIX_SONG_IDS]
                if (jsonString != null) {
                    try {
                        json.decodeFromString<List<String>>(jsonString)
                    } catch (e: Exception) {
                        emptyList()
                    }
                } else {
                    emptyList()
                }
            }

    suspend fun saveYourMixSongIds(songIds: List<String>) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.YOUR_MIX_SONG_IDS] = json.encodeToString(songIds)
        }
    }

    val isGenreGridViewFlow: Flow<Boolean> =
        dataStore.data.map { preferences ->
            preferences[PreferencesKeys.IS_GENRE_GRID_VIEW] ?: true // Default to Grid (true)
        }

    suspend fun setGenreGridView(isGrid: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.IS_GENRE_GRID_VIEW] = isGrid
        }
    }

    val isAlbumsListViewFlow: Flow<Boolean> =
        dataStore.data.map { preferences ->
            preferences[PreferencesKeys.IS_ALBUMS_LIST_VIEW] ?: false // Default to Grid (false)
        }

    suspend fun setAlbumsListView(isList: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.IS_ALBUMS_LIST_VIEW] = isList
        }
    }

    val lastDailyMixUpdateFlow: Flow<Long> =
            dataStore.data.map { preferences ->
                preferences[PreferencesKeys.LAST_DAILY_MIX_UPDATE] ?: 0L
            }

    suspend fun saveLastDailyMixUpdateTimestamp(timestamp: Long) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.LAST_DAILY_MIX_UPDATE] = timestamp
        }
    }

    // ===== Smart Duration Filtering =====

    /** Minimum song duration in milliseconds. Default 10000ms (10 seconds). */
    val minSongDurationFlow: Flow<Int> =
        dataStore.data.map { preferences ->
            (preferences[PreferencesKeys.MIN_SONG_DURATION] ?: 10000).coerceIn(0, 120000)
        }

    suspend fun setMinSongDuration(durationMs: Int) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.MIN_SONG_DURATION] = durationMs.coerceIn(0, 120000)
        }
    }

    suspend fun getMinSongDuration(): Int {
        return minSongDurationFlow.first()
    }

    // ===== End Smart Duration Filtering =====

    // ===== Album Tracks Filtering =====

    val minTracksPerAlbumFlow: Flow<Int> =
        dataStore.data.map { preferences ->
            preferences[PreferencesKeys.MIN_TRACKS_PER_ALBUM] ?: 1
        }

    suspend fun setMinTracksPerAlbum(minTracks: Int) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.MIN_TRACKS_PER_ALBUM] = minTracks
        }
    }

    // ===== End Album Tracks Filtering =====

    // ===== ReplayGain =====

    val replayGainEnabledFlow: Flow<Boolean> =
        dataStore.data.map { preferences ->
            preferences[PreferencesKeys.REPLAYGAIN_ENABLED] ?: false
        }

    val replayGainUseAlbumGainFlow: Flow<Boolean> =
        dataStore.data.map { preferences ->
            preferences[PreferencesKeys.REPLAYGAIN_USE_ALBUM_GAIN] ?: false
        }

    suspend fun setReplayGainEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.REPLAYGAIN_ENABLED] = enabled
        }
    }

    suspend fun setReplayGainUseAlbumGain(useAlbumGain: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.REPLAYGAIN_USE_ALBUM_GAIN] = useAlbumGain
        }
    }

    // ===== End ReplayGain =====

    val allowedDirectoriesFlow: Flow<Set<String>> =
            dataStore.data.map { preferences ->
                preferences[PreferencesKeys.ALLOWED_DIRECTORIES] ?: emptySet()
            }.distinctUntilChanged()

    val blockedDirectoriesFlow: Flow<Set<String>> =
            dataStore.data.map { preferences ->
                preferences[PreferencesKeys.BLOCKED_DIRECTORIES] ?: emptySet()
            }.distinctUntilChanged()

    val initialSetupDoneFlow: Flow<Boolean> =
            dataStore.data.map { preferences ->
                preferences[PreferencesKeys.INITIAL_SETUP_DONE] ?: false
            }

    val keepPlayingInBackgroundFlow: Flow<Boolean> =
            dataStore.data.map { preferences ->
                preferences[PreferencesKeys.KEEP_PLAYING_IN_BACKGROUND] ?: true
            }

    val resumeOnHeadsetReconnectFlow: Flow<Boolean> =
            dataStore.data.map { preferences ->
                preferences[PreferencesKeys.RESUME_ON_HEADSET_RECONNECT] ?: false
            }

    val showQueueHistoryFlow: Flow<Boolean> =
            dataStore.data.map { preferences ->
                preferences[PreferencesKeys.SHOW_QUEUE_HISTORY] ?: false  // Default to false for performance
            }

    suspend fun setShowQueueHistory(show: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.SHOW_QUEUE_HISTORY] = show
        }
    }

    val showPlayerFileInfoFlow: Flow<Boolean> =
            dataStore.data.map { preferences ->
                preferences[PreferencesKeys.FULL_PLAYER_SHOW_FILE_INFO] ?: true
            }

    suspend fun setShowPlayerFileInfo(show: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.FULL_PLAYER_SHOW_FILE_INFO] = show
        }
    }

    val fullPlayerLoadingTweaksFlow: Flow<FullPlayerLoadingTweaks> = dataStore.data
        .map { preferences ->
            val delayAlbum = preferences[PreferencesKeys.FULL_PLAYER_DELAY_ALBUM] ?: true
            val delayMetadata = preferences[PreferencesKeys.FULL_PLAYER_DELAY_METADATA] ?: true
            val delayProgress = preferences[PreferencesKeys.FULL_PLAYER_DELAY_PROGRESS] ?: true
            val delayControls = preferences[PreferencesKeys.FULL_PLAYER_DELAY_CONTROLS] ?: true

            val delayAll = delayAlbum && delayMetadata && delayProgress && delayControls

            FullPlayerLoadingTweaks(
                delayAll = delayAll,
                delayAlbumCarousel = delayAlbum,
                delaySongMetadata = delayMetadata,
                delayProgressBar = delayProgress,
                delayControls = delayControls,
                showPlaceholders = preferences[PreferencesKeys.FULL_PLAYER_PLACEHOLDERS] ?: true,
                transparentPlaceholders = preferences[PreferencesKeys.FULL_PLAYER_PLACEHOLDER_TRANSPARENT] ?: false,
                applyPlaceholdersOnClose = preferences[PreferencesKeys.FULL_PLAYER_PLACEHOLDERS_ON_CLOSE] ?: false,
                switchOnDragRelease = preferences[PreferencesKeys.FULL_PLAYER_SWITCH_ON_DRAG_RELEASE] ?: true,
                contentAppearThresholdPercent = preferences[PreferencesKeys.FULL_PLAYER_DELAY_THRESHOLD] ?: 98,
                contentCloseThresholdPercent = preferences[PreferencesKeys.FULL_PLAYER_CLOSE_THRESHOLD] ?: 0
            )
        }

    val favoriteSongIdsFlow: Flow<Set<String>> =
            dataStore.data // New flow for favorites
                    .map { preferences ->
                preferences[PreferencesKeys.FAVORITE_SONG_IDS] ?: emptySet()
            }

    val playlistSongOrderModesFlow: Flow<Map<String, String>> =
            dataStore.data.map { preferences ->
                val serializedModes = preferences[PreferencesKeys.PLAYLIST_SONG_ORDER_MODES]
                if (serializedModes.isNullOrBlank()) {
                    emptyMap()
                } else {
                    runCatching { json.decodeFromString<Map<String, String>>(serializedModes) }
                            .getOrDefault(emptyMap())
                }
            }

    // Legacy DataStore playlist payload kept only for one-time migration and old backup compatibility.
    val legacyUserPlaylistsFlow: Flow<List<Playlist>> =
            dataStore.data.map { preferences ->
                val jsonString = preferences[PreferencesKeys.USER_PLAYLISTS]
                if (jsonString != null) {
                    runCatching { json.decodeFromString<List<Playlist>>(jsonString) }
                        .getOrDefault(emptyList())
                } else {
                    emptyList()
                }
            }

    suspend fun getLegacyUserPlaylistsOnce(): List<Playlist> = legacyUserPlaylistsFlow.first()

    suspend fun clearLegacyUserPlaylists() {
        dataStore.edit { preferences ->
            preferences.remove(PreferencesKeys.USER_PLAYLISTS)
        }
    }

    suspend fun setPlaylistSongOrderMode(playlistId: String, modeValue: String) {
        dataStore.edit { preferences ->
            val existingModes =
                    preferences[PreferencesKeys.PLAYLIST_SONG_ORDER_MODES]?.let { raw ->
                        runCatching { json.decodeFromString<Map<String, String>>(raw) }
                                .getOrDefault(emptyMap())
                    }
                            ?: emptyMap()

            val updated = existingModes.toMutableMap()
            updated[playlistId] = modeValue

            preferences[PreferencesKeys.PLAYLIST_SONG_ORDER_MODES] = json.encodeToString(updated)
        }
    }

    suspend fun setPlaylistSongOrderModes(modes: Map<String, String>) {
        dataStore.edit { preferences ->
            if (modes.isEmpty()) {
                preferences.remove(PreferencesKeys.PLAYLIST_SONG_ORDER_MODES)
            } else {
                preferences[PreferencesKeys.PLAYLIST_SONG_ORDER_MODES] = json.encodeToString(modes)
            }
        }
    }

    suspend fun clearPlaylistSongOrderMode(playlistId: String) {
        dataStore.edit { preferences ->
            val existingModes =
                    preferences[PreferencesKeys.PLAYLIST_SONG_ORDER_MODES]?.let { raw ->
                        runCatching { json.decodeFromString<Map<String, String>>(raw) }
                                .getOrDefault(emptyMap())
                    }
                            ?: emptyMap()

            if (!existingModes.containsKey(playlistId)) return@edit

            val updated = existingModes.toMutableMap()
            updated.remove(playlistId)

            preferences[PreferencesKeys.PLAYLIST_SONG_ORDER_MODES] = json.encodeToString(updated)
        }
    }

    suspend fun updateAllowedDirectories(allowedPaths: Set<String>) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.ALLOWED_DIRECTORIES] = allowedPaths
            // Directory rules changed: force next sync to fetch full library again.
            preferences[PreferencesKeys.LAST_SYNC_TIMESTAMP] = 0L
            val currentVersion = preferences[PreferencesKeys.DIRECTORY_RULES_VERSION] ?: 0
            preferences[PreferencesKeys.DIRECTORY_RULES_VERSION] =
                if (currentVersion == Int.MAX_VALUE) 0 else currentVersion + 1
        }
    }

    suspend fun updateDirectorySelections(allowedPaths: Set<String>, blockedPaths: Set<String>) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.ALLOWED_DIRECTORIES] = allowedPaths
            preferences[PreferencesKeys.BLOCKED_DIRECTORIES] = blockedPaths
            // Directory rules changed: force next sync to fetch full library again.
            preferences[PreferencesKeys.LAST_SYNC_TIMESTAMP] = 0L
            val currentVersion = preferences[PreferencesKeys.DIRECTORY_RULES_VERSION] ?: 0
            preferences[PreferencesKeys.DIRECTORY_RULES_VERSION] =
                if (currentVersion == Int.MAX_VALUE) 0 else currentVersion + 1
        }
    }

    suspend fun toggleFavoriteSong(
            songId: String,
            removing: Boolean = false
    ) { // New function for favorites
        dataStore.edit { preferences ->
            val currentFavorites = preferences[PreferencesKeys.FAVORITE_SONG_IDS] ?: emptySet()
            val contains = currentFavorites.contains(songId)

            if (contains) preferences[PreferencesKeys.FAVORITE_SONG_IDS] = currentFavorites - songId
            else {
                if (removing)
                        preferences[PreferencesKeys.FAVORITE_SONG_IDS] = currentFavorites - songId
                else preferences[PreferencesKeys.FAVORITE_SONG_IDS] = currentFavorites + songId
            }
        }
    }

    suspend fun setFavoriteSong(songId: String, isFavorite: Boolean) {
        dataStore.edit { preferences ->
            val currentFavorites = preferences[PreferencesKeys.FAVORITE_SONG_IDS] ?: emptySet()
            preferences[PreferencesKeys.FAVORITE_SONG_IDS] = if (isFavorite) {
                currentFavorites + songId
            } else {
                currentFavorites - songId
            }
        }
    }

    suspend fun clearFavoriteSongIds() {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.FAVORITE_SONG_IDS] = emptySet()
        }
    }

    suspend fun setInitialSetupDone(isDone: Boolean) {
        dataStore.edit { preferences -> preferences[PreferencesKeys.INITIAL_SETUP_DONE] = isDone }
    }

    // Flows for Sort Options
    val songsSortOptionFlow: Flow<String> =
            dataStore.data.map { preferences ->
                SortOption.fromStorageKey(
                                preferences[PreferencesKeys.SONGS_SORT_OPTION],
                                SortOption.SONGS,
                                SortOption.SongTitleAZ
                        )
                        .storageKey
            }

    val albumsSortOptionFlow: Flow<String> =
            dataStore.data.map { preferences ->
                SortOption.fromStorageKey(
                                preferences[PreferencesKeys.ALBUMS_SORT_OPTION],
                                SortOption.ALBUMS,
                                SortOption.AlbumTitleAZ
                        )
                        .storageKey
            }

    val artistsSortOptionFlow: Flow<String> =
            dataStore.data.map { preferences ->
                SortOption.fromStorageKey(
                                preferences[PreferencesKeys.ARTISTS_SORT_OPTION],
                                SortOption.ARTISTS,
                                SortOption.ArtistNameAZ
                        )
                        .storageKey
            }

    val playlistsSortOptionFlow: Flow<String> =
            dataStore.data.map { preferences ->
                SortOption.fromStorageKey(
                                preferences[PreferencesKeys.PLAYLISTS_SORT_OPTION],
                                SortOption.PLAYLISTS,
                                SortOption.PlaylistNameAZ
                        )
                        .storageKey
            }

    val foldersSortOptionFlow: Flow<String> =
            dataStore.data.map { preferences ->
                SortOption.fromStorageKey(
                                preferences[PreferencesKeys.FOLDERS_SORT_OPTION],
                                SortOption.FOLDERS,
                                SortOption.FolderNameAZ
                        )
                        .storageKey
            }

    val likedSongsSortOptionFlow: Flow<String> =
            dataStore.data.map { preferences ->
                SortOption.fromStorageKey(
                                preferences[PreferencesKeys.LIKED_SONGS_SORT_OPTION],
                                SortOption.LIKED,
                                SortOption.LikedSongDateLiked
                        )
                        .storageKey
            }

    // Functions to update Sort Options
    suspend fun setSongsSortOption(optionKey: String) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.SONGS_SORT_OPTION] = optionKey
            preferences[PreferencesKeys.SONGS_SORT_OPTION_MIGRATED] = true
        }
    }

    suspend fun setAlbumsSortOption(optionKey: String) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.ALBUMS_SORT_OPTION] = optionKey
        }
    }

    suspend fun setArtistsSortOption(optionKey: String) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.ARTISTS_SORT_OPTION] = optionKey
        }
    }

    suspend fun setPlaylistsSortOption(optionKey: String) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.PLAYLISTS_SORT_OPTION] = optionKey
        }
    }

    suspend fun setFoldersSortOption(optionKey: String) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.FOLDERS_SORT_OPTION] = optionKey
        }
    }

    suspend fun setLikedSongsSortOption(optionKey: String) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.LIKED_SONGS_SORT_OPTION] = optionKey
        }
    }

    suspend fun ensureLibrarySortDefaults() {
        dataStore.edit { preferences ->
            val songsMigrated = preferences[PreferencesKeys.SONGS_SORT_OPTION_MIGRATED] ?: false
            val rawSongSort = preferences[PreferencesKeys.SONGS_SORT_OPTION]
            val resolvedSongSort =
                    SortOption.fromStorageKey(rawSongSort, SortOption.SONGS, SortOption.SongTitleAZ)
            val shouldForceSongDefault =
                    !songsMigrated &&
                            (rawSongSort.isNullOrBlank() ||
                                    rawSongSort == SortOption.SongTitleZA.storageKey ||
                                    rawSongSort == SortOption.SongTitleZA.displayName)

            preferences[PreferencesKeys.SONGS_SORT_OPTION] =
                    if (shouldForceSongDefault) {
                        SortOption.SongTitleAZ.storageKey
                    } else {
                        resolvedSongSort.storageKey
                    }
            if (!songsMigrated) {
                preferences[PreferencesKeys.SONGS_SORT_OPTION_MIGRATED] = true
            }

            migrateSortPreference(
                    preferences,
                    PreferencesKeys.SONGS_SORT_OPTION,
                    SortOption.SONGS,
                    SortOption.SongTitleAZ
            )
            migrateSortPreference(
                    preferences,
                    PreferencesKeys.ALBUMS_SORT_OPTION,
                    SortOption.ALBUMS,
                    SortOption.AlbumTitleAZ
            )
            migrateSortPreference(
                    preferences,
                    PreferencesKeys.ARTISTS_SORT_OPTION,
                    SortOption.ARTISTS,
                    SortOption.ArtistNameAZ
            )
            migrateSortPreference(
                    preferences,
                    PreferencesKeys.PLAYLISTS_SORT_OPTION,
                    SortOption.PLAYLISTS,
                    SortOption.PlaylistNameAZ
            )
            migrateSortPreference(
                    preferences,
                    PreferencesKeys.FOLDERS_SORT_OPTION,
                    SortOption.FOLDERS,
                    SortOption.FolderNameAZ
            )
            migrateSortPreference(
                    preferences,
                    PreferencesKeys.LIKED_SONGS_SORT_OPTION,
                    SortOption.LIKED,
                    SortOption.LikedSongDateLiked
            )
        }
    }

    private fun migrateSortPreference(
            preferences: MutablePreferences,
            key: Preferences.Key<String>,
            allowed: Collection<SortOption>,
            fallback: SortOption
    ) {
        val resolved = SortOption.fromStorageKey(preferences[key], allowed, fallback)
        if (preferences[key] != resolved.storageKey) {
            preferences[key] = resolved.storageKey
        }
    }

    // --- Library UI State ---
    val lastLibraryTabIndexFlow: Flow<Int> =
            dataStore.data.map { preferences ->
                preferences[PreferencesKeys.LAST_LIBRARY_TAB_INDEX] ?: 0 // Default to 0 (Songs tab)
            }

    suspend fun saveLastLibraryTabIndex(tabIndex: Int) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.LAST_LIBRARY_TAB_INDEX] = tabIndex
        }
    }

    val lastStorageFilterFlow: Flow<StorageFilter> =
        dataStore.data.map { preferences ->
            when (preferences[PreferencesKeys.LAST_STORAGE_FILTER]) {
                "ONLINE"  -> StorageFilter.ONLINE
                "OFFLINE" -> StorageFilter.OFFLINE
                else      -> StorageFilter.ALL
            }
        }

    suspend fun saveLastStorageFilter(filter: StorageFilter) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.LAST_STORAGE_FILTER] = filter.name
        }
    }

    val mockGenresEnabledFlow: Flow<Boolean> =
            dataStore.data.map { preferences ->
                preferences[PreferencesKeys.MOCK_GENRES_ENABLED] ?: false // Default to false
            }

    suspend fun setMockGenresEnabled(enabled: Boolean) {
        dataStore.edit { preferences -> preferences[PreferencesKeys.MOCK_GENRES_ENABLED] = enabled }
    }

    companion object {
        /** Default character delimiters for splitting multi-artist tags */
        val DEFAULT_ARTIST_DELIMITERS = listOf("/", ";", ",", "+", "&")
        /** Default word-based delimiters (matched case-insensitively with whitespace boundaries) */
        val DEFAULT_ARTIST_WORD_DELIMITERS = listOf("featuring", "feat.", "feat", "ft.", "ft", "vs.", "vs", "versus", "with", "prod.", "prod")
        const val DEFAULT_ALBUM_ART_CACHE_LIMIT_MB = 200
    }

    val navBarCornerRadiusFlow: Flow<Int> =
            dataStore.data.map { preferences ->
                sanitizeNavBarCornerRadius(preferences[PreferencesKeys.NAV_BAR_CORNER_RADIUS] ?: 32)
            }

    suspend fun setNavBarCornerRadius(radius: Int) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.NAV_BAR_CORNER_RADIUS] = sanitizeNavBarCornerRadius(radius)
        }
    }

    val navBarStyleFlow: Flow<String> =
            dataStore.data.map { preferences ->
                preferences[PreferencesKeys.NAV_BAR_STYLE] ?: NavBarStyle.DEFAULT
            }

    suspend fun setNavBarStyle(style: String) {
        dataStore.edit { preferences -> preferences[PreferencesKeys.NAV_BAR_STYLE] = style }
    }

    val navBarCompactModeFlow: Flow<Boolean> =
            dataStore.data.map { preferences ->
                preferences[PreferencesKeys.NAV_BAR_COMPACT_MODE] ?: false
            }

    suspend fun setNavBarCompactMode(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.NAV_BAR_COMPACT_MODE] = enabled
        }
    }

    val libraryNavigationModeFlow: Flow<String> =
            dataStore.data.map { preferences ->
                preferences[PreferencesKeys.LIBRARY_NAVIGATION_MODE]
                        ?: LibraryNavigationMode.TAB_ROW
            }

    suspend fun setLibraryNavigationMode(mode: String) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.LIBRARY_NAVIGATION_MODE] = mode
        }
    }

    val carouselStyleFlow: Flow<String> =
            dataStore.data.map { preferences ->
                preferences[PreferencesKeys.CAROUSEL_STYLE] ?: CarouselStyle.NO_PEEK
            }

    suspend fun setCarouselStyle(style: String) {
        dataStore.edit { preferences -> preferences[PreferencesKeys.CAROUSEL_STYLE] = style }
    }

    val launchTabFlow: Flow<String> =
            dataStore.data.map { preferences ->
                preferences[PreferencesKeys.LAUNCH_TAB] ?: LaunchTab.HOME
            }

    suspend fun setLaunchTab(tab: String) {
        dataStore.edit { preferences -> preferences[PreferencesKeys.LAUNCH_TAB] = tab }
    }

    suspend fun setKeepPlayingInBackground(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.KEEP_PLAYING_IN_BACKGROUND] = enabled
        }
    }

    suspend fun setResumeOnHeadsetReconnect(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.RESUME_ON_HEADSET_RECONNECT] = enabled
        }
    }

    suspend fun setDelayAllFullPlayerContent(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.FULL_PLAYER_DELAY_ALL] = enabled

            preferences[PreferencesKeys.FULL_PLAYER_DELAY_ALBUM] = enabled
            preferences[PreferencesKeys.FULL_PLAYER_DELAY_METADATA] = enabled
            preferences[PreferencesKeys.FULL_PLAYER_DELAY_PROGRESS] = enabled
            preferences[PreferencesKeys.FULL_PLAYER_DELAY_CONTROLS] = enabled
        }
    }

    suspend fun setDelayAlbumCarousel(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.FULL_PLAYER_DELAY_ALBUM] = enabled
        }
    }

    suspend fun setDelaySongMetadata(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.FULL_PLAYER_DELAY_METADATA] = enabled
        }
    }

    suspend fun setDelayProgressBar(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.FULL_PLAYER_DELAY_PROGRESS] = enabled
        }
    }

    suspend fun setDelayControls(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.FULL_PLAYER_DELAY_CONTROLS] = enabled
        }
    }

    suspend fun setFullPlayerPlaceholders(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.FULL_PLAYER_PLACEHOLDERS] = enabled
            if (!enabled) {
                preferences[PreferencesKeys.FULL_PLAYER_PLACEHOLDER_TRANSPARENT] = false
            }
        }
    }

    suspend fun setTransparentPlaceholders(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.FULL_PLAYER_PLACEHOLDER_TRANSPARENT] = enabled
        }
    }

    suspend fun setFullPlayerPlaceholdersOnClose(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.FULL_PLAYER_PLACEHOLDERS_ON_CLOSE] = enabled
        }
    }

    suspend fun setFullPlayerSwitchOnDragRelease(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.FULL_PLAYER_SWITCH_ON_DRAG_RELEASE] = enabled
        }
    }

    suspend fun setFullPlayerAppearThreshold(thresholdPercent: Int) {
        val coercedValue = thresholdPercent.coerceIn(0, 100)
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.FULL_PLAYER_DELAY_THRESHOLD] = coercedValue
        }
    }

    suspend fun setFullPlayerCloseThreshold(thresholdPercent: Int) {
        val coercedValue = thresholdPercent.coerceIn(0, 100)
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.FULL_PLAYER_CLOSE_THRESHOLD] = coercedValue
        }
    }

    suspend fun clearDeprecatedPlayerSheetPreference() {
        dataStore.edit { preferences ->
            preferences.remove(PreferencesKeys.USE_PLAYER_SHEET_V2)
        }
    }

    val useAnimatedLyricsFlow: Flow<Boolean> = dataStore.data
        .map { preferences ->
            preferences[PreferencesKeys.USE_ANIMATED_LYRICS] ?: false
        }

    suspend fun setUseAnimatedLyrics(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.USE_ANIMATED_LYRICS] = enabled
        }
    }

    val animatedLyricsBlurEnabledFlow: Flow<Boolean> = dataStore.data
        .map { preferences ->
            preferences[PreferencesKeys.ANIMATED_LYRICS_BLUR_ENABLED] ?: true
        }

    suspend fun setAnimatedLyricsBlurEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.ANIMATED_LYRICS_BLUR_ENABLED] = enabled
        }
    }

    // Range should ideally be 0.0f to 5.0f (or similar)
    val animatedLyricsBlurStrengthFlow: Flow<Float> = dataStore.data
        .map { preferences ->
            preferences[PreferencesKeys.ANIMATED_LYRICS_BLUR_STRENGTH] ?: 2.5f
        }

    suspend fun setAnimatedLyricsBlurStrength(strength: Float) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.ANIMATED_LYRICS_BLUR_STRENGTH] = strength
        }
    }

    val libraryTabsOrderFlow: Flow<String?> = dataStore.data
        .map { preferences ->
            preferences[PreferencesKeys.LIBRARY_TABS_ORDER]
        }

    suspend fun saveLibraryTabsOrder(order: String) {
        dataStore.edit { preferences -> preferences[PreferencesKeys.LIBRARY_TABS_ORDER] = order }
    }

    suspend fun resetLibraryTabsOrder() {
        dataStore.edit { preferences -> preferences.remove(PreferencesKeys.LIBRARY_TABS_ORDER) }
    }

    suspend fun migrateTabOrder() {
        dataStore.edit { preferences ->
            val orderJson = preferences[PreferencesKeys.LIBRARY_TABS_ORDER]
            if (orderJson != null) {
                try {
                    val order = json.decodeFromString<MutableList<String>>(orderJson)
                    if (!order.contains("FOLDERS")) {
                        val likedIndex = order.indexOf("LIKED")
                        if (likedIndex != -1) {
                            order.add(likedIndex + 1, "FOLDERS")
                        } else {
                            order.add("FOLDERS") // Fallback
                        }
                        preferences[PreferencesKeys.LIBRARY_TABS_ORDER] = json.encodeToString(order)
                    }
                } catch (e: Exception) {
                    // If deserialization fails, do nothing to avoid overwriting the
                    // user's data.
                }
            }
            // If orderJson is null, it means the user has never reordered,
            // so the default order that already includes FOLDERS will be used.
        }
    }

    val isFolderFilterActiveFlow: Flow<Boolean> =
            dataStore.data.map { preferences ->
                preferences[PreferencesKeys.IS_FOLDER_FILTER_ACTIVE] ?: false
            }

    suspend fun setFolderFilterActive(isActive: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.IS_FOLDER_FILTER_ACTIVE] = isActive
        }
    }

    val isFoldersPlaylistViewFlow: Flow<Boolean> = dataStore.data
        .map { preferences ->
            preferences[PreferencesKeys.IS_FOLDERS_PLAYLIST_VIEW] ?: false
        }

    val hideLocalMediaFlow: Flow<Boolean> = dataStore.data
        .map { preferences ->
            preferences[PreferencesKeys.HIDE_LOCAL_MEDIA] ?: false
        }.distinctUntilChanged()

    val foldersSourceFlow: Flow<FolderSource> = dataStore.data
        .map { preferences ->
            FolderSource.fromStorageKey(preferences[PreferencesKeys.FOLDERS_SOURCE])
        }

    val folderBackGestureNavigationFlow: Flow<Boolean> = dataStore.data
        .map { preferences ->
            preferences[PreferencesKeys.FOLDER_BACK_GESTURE_NAVIGATION] ?: true
        }

    val useSmoothCornersFlow: Flow<Boolean> = dataStore.data
        .map { preferences ->
            preferences[PreferencesKeys.USE_SMOOTH_CORNERS] ?: false
        }

    suspend fun setUseSmoothCorners(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.USE_SMOOTH_CORNERS] = enabled
        }
    }

    suspend fun setFoldersPlaylistView(isPlaylistView: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.IS_FOLDERS_PLAYLIST_VIEW] = isPlaylistView
        }
    }

    suspend fun setHideLocalMedia(hide: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.HIDE_LOCAL_MEDIA] = hide
        }
    }

    suspend fun setFoldersSource(source: FolderSource) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.FOLDERS_SOURCE] = source.storageKey
        }
    }

    suspend fun setFolderBackGestureNavigation(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.FOLDER_BACK_GESTURE_NAVIGATION] = enabled
        }
    }

    // ===== Developer Options =====

    /**
     * Album art quality for player view.
     * Controls the maximum resolution for album artwork displayed in the full player.
     * Thumbnails in lists always use low resolution (256px) for optimal performance.
     */
    val albumArtQualityFlow: Flow<AlbumArtQuality> =
        dataStore.data.map { preferences ->
            preferences[PreferencesKeys.ALBUM_ART_QUALITY]
                ?.let {
                    try { AlbumArtQuality.valueOf(it) }
                    catch (e: Exception) { AlbumArtQuality.MEDIUM }
                }
                ?: AlbumArtQuality.MEDIUM
        }

    suspend fun setAlbumArtQuality(quality: AlbumArtQuality) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.ALBUM_ART_QUALITY] = quality.name
        }
    }

    val albumArtCacheLimitMbFlow: Flow<Int> =
        dataStore.data.map { preferences ->
            preferences[PreferencesKeys.ALBUM_ART_CACHE_LIMIT_MB] ?: DEFAULT_ALBUM_ART_CACHE_LIMIT_MB
        }

    suspend fun setAlbumArtCacheLimitMb(limitMb: Int) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.ALBUM_ART_CACHE_LIMIT_MB] = limitMb.coerceIn(50, 1500)
        }
    }

    /**
     * Whether tapping the background area of the player sheet closes it.
     * Default is false to avoid accidental dismissals while interacting with the full player.
     */
    val tapBackgroundClosesPlayerFlow: Flow<Boolean> =
        dataStore.data.map { preferences ->
            preferences[PreferencesKeys.TAP_BACKGROUND_CLOSES_PLAYER] ?: false
        }

    suspend fun setTapBackgroundClosesPlayer(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.TAP_BACKGROUND_CLOSES_PLAYER] = enabled
        }
    }

    val hapticsEnabledFlow: Flow<Boolean> =
        dataStore.data.map { preferences ->
            preferences[PreferencesKeys.HAPTICS_ENABLED] ?: true
        }

    suspend fun setHapticsEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.HAPTICS_ENABLED] = enabled
        }
    }

    suspend fun clearPreferencesByKeys(keyNames: Set<String>) {
        if (keyNames.isEmpty()) return
        dataStore.edit { preferences ->
            preferences.asMap().keys
                .filter { key -> key.name in keyNames && key.name !in backupExcludedKeyNames }
                .forEach { key ->
                    @Suppress("UNCHECKED_CAST")
                    preferences.remove(key as Preferences.Key<Any>)
                }
        }
    }

    suspend fun clearPreferencesExceptKeys(excludedKeyNames: Set<String>) {
        val protectedKeyNames = excludedKeyNames + backupExcludedKeyNames + restorePreservedKeyNames
        dataStore.edit { preferences ->
            preferences.asMap().keys
                .filterNot { key -> key.name in protectedKeyNames }
                .forEach { key ->
                    @Suppress("UNCHECKED_CAST")
                    preferences.remove(key as Preferences.Key<Any>)
                }
        }
    }

    suspend fun exportPreferencesForBackup(): List<PreferenceBackupEntry> {
        val snapshot = dataStore.data.first().asMap()
        return snapshot.mapNotNull { (key, value) ->
            val keyName = key.name
            if (keyName in backupExcludedKeyNames) {
                return@mapNotNull null
            }
            // Redact secret/endpoint-bearing keys (token/secret/password/url) from exports. None of
            // the registered keys above match these substrings, so legitimate settings are kept;
            // this only guards against a future credential key leaking into a shareable backup file.
            if (isLikelySecretKey(keyName)) {
                return@mapNotNull null
            }
            when (value) {
                is String -> PreferenceBackupEntry(
                    key = keyName,
                    type = "string",
                    stringValue = value
                )
                is Int -> PreferenceBackupEntry(
                    key = keyName,
                    type = "int",
                    intValue = value
                )
                is Long -> PreferenceBackupEntry(
                    key = keyName,
                    type = "long",
                    longValue = value
                )
                is Boolean -> PreferenceBackupEntry(
                    key = keyName,
                    type = "boolean",
                    booleanValue = value
                )
                is Float -> PreferenceBackupEntry(
                    key = keyName,
                    type = "float",
                    floatValue = value
                )
                is Double -> PreferenceBackupEntry(
                    key = keyName,
                    type = "double",
                    doubleValue = value
                )
                is Set<*> -> {
                    val stringSet = value.filterIsInstance<String>().toSet()
                    PreferenceBackupEntry(
                        key = keyName,
                        type = "string_set",
                        stringSetValue = stringSet
                    )
                }
                else -> null
            }
        }
    }

    suspend fun importPreferencesFromBackup(
        entries: List<PreferenceBackupEntry>,
        clearExisting: Boolean = true
    ) {
        dataStore.edit { preferences ->
            if (clearExisting) {
                val protectedKeyNames = backupExcludedKeyNames + restorePreservedKeyNames
                preferences.asMap().keys
                    // Also preserve secret/endpoint keys: export redacts them, so a restore must not
                    // wipe live credentials that the backup deliberately omitted.
                    .filterNot { key -> key.name in protectedKeyNames || isLikelySecretKey(key.name) }
                    .forEach { key ->
                        @Suppress("UNCHECKED_CAST")
                        preferences.remove(key as Preferences.Key<Any>)
                    }
            }

            entries.forEach { entry ->
                if (entry.key in backupExcludedKeyNames) {
                    return@forEach
                }

                // Symmetric with export's redaction: never restore secret/endpoint keys from a backup.
                if (isLikelySecretKey(entry.key)) {
                    return@forEach
                }

                // Per-key type validation: for any key we know the live type of, ignore the
                // backup-supplied `entry.type` and write under the EXPECTED type. This repairs an
                // entry whose declared type drifted from what the app reads it as (which would
                // otherwise throw ClassCastException on the next typed read). Numeric expected
                // types can repair across the other numeric carriers; a fundamentally
                // incompatible value (e.g. string expected but only a boolean supplied) is skipped.
                // Keys NOT in the registry fall back to the backup-supplied type so newer/unknown
                // keys are still restored.
                val expectedType = PreferenceTypeRegistry.expectedTypes[entry.key]
                val effectiveType = expectedType?.let { PreferenceTypeRegistry.wireName(it) } ?: entry.type
                if (expectedType != null && entry.type != effectiveType) {
                    Timber.w(
                        "Backup entry '%s' declared type '%s' but expected '%s'; coercing to expected type",
                        entry.key, entry.type, effectiveType
                    )
                }

                when (effectiveType) {
                    "string" -> {
                        val value = entry.stringValue ?: return@forEach
                        preferences[stringPreferencesKey(entry.key)] = value
                    }
                    "int" -> {
                        val value = entry.intValue
                            ?: entry.doubleValue?.toIntExactPrefOrNull()
                            ?: entry.longValue?.toIntRangedPrefOrNull()
                            ?: return@forEach
                        preferences[intPreferencesKey(entry.key)] = value
                    }
                    "long" -> {
                        val value = entry.longValue
                            ?: entry.doubleValue?.toLongExactPrefOrNull()
                            ?: entry.intValue?.toLong()
                            ?: return@forEach
                        preferences[longPreferencesKey(entry.key)] = value
                    }
                    "boolean" -> {
                        val value = entry.booleanValue ?: return@forEach
                        preferences[booleanPreferencesKey(entry.key)] = value
                    }
                    "float" -> {
                        val value = entry.floatValue
                            ?: entry.doubleValue?.toFloatFinitePrefOrNull()
                            ?: entry.intValue?.toFloat()
                            ?: entry.longValue?.toFloat()
                            ?: return@forEach
                        preferences[androidx.datastore.preferences.core.floatPreferencesKey(entry.key)] = value
                    }
                    "double" -> {
                        val value = entry.doubleValue
                            ?: entry.floatValue?.toDouble()
                            ?: entry.intValue?.toDouble()
                            ?: entry.longValue?.toDouble()
                            ?: return@forEach
                        preferences[androidx.datastore.preferences.core.doublePreferencesKey(entry.key)] = value
                    }
                    "string_set" -> {
                        val value = entry.stringSetValue ?: return@forEach
                        preferences[stringSetPreferencesKey(entry.key)] = value
                    }
                }
            }
        }
    }

    // --- Collage Pattern ---

    val collagePatternFlow: Flow<CollagePattern> =
        dataStore.data.map { preferences ->
            CollagePattern.fromStorageKey(preferences[PreferencesKeys.COLLAGE_PATTERN])
        }

    suspend fun setCollagePattern(pattern: CollagePattern) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.COLLAGE_PATTERN] = pattern.storageKey
        }
    }

    val collageAutoRotateFlow: Flow<Boolean> =
        dataStore.data.map { preferences ->
            preferences[PreferencesKeys.COLLAGE_AUTO_ROTATE] ?: false
        }

    suspend fun setCollageAutoRotate(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.COLLAGE_AUTO_ROTATE] = enabled
        }
    }

    // --- Quick Settings: Last Playlist ---

    val lastPlaylistIdFlow: Flow<String?> =
        dataStore.data.map { preferences ->
            preferences[PreferencesKeys.LAST_PLAYLIST_ID]?.takeIf { it.isNotBlank() }
        }

    val lastPlaylistNameFlow: Flow<String?> =
        dataStore.data.map { it[PreferencesKeys.LAST_PLAYLIST_NAME] }

    suspend fun setLastPlaylist(playlistId: String, playlistName: String) {
        dataStore.edit {
            it[PreferencesKeys.LAST_PLAYLIST_ID] = playlistId
            it[PreferencesKeys.LAST_PLAYLIST_NAME] = playlistName
        }
    }

    suspend fun clearLastPlaylist() {
        dataStore.edit {
            it.remove(PreferencesKeys.LAST_PLAYLIST_ID)
            it.remove(PreferencesKeys.LAST_PLAYLIST_NAME)
        }
    }
}
