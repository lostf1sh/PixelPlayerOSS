package com.lostf1sh.pixelplayeross.presentation.jellyfin.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lostf1sh.pixelplayeross.data.database.JellyfinPlaylistEntity
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.data.jellyfin.JellyfinRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/** Typed status of the sync banner so the UI can color it without parsing English text (F113). */
enum class JellyfinSyncStatus { InProgress, Success, Partial, Error }

/** Coarse, user-safe failure category so the banner never surfaces raw backend/exception text. */
enum class JellyfinSyncErrorReason { Network, Auth, ServerUnavailable, Unknown }

/**
 * Sync banner state. Carries structured data so the UI renders localized strings via
 * stringResource instead of pre-formatted English text (F112).
 */
sealed interface JellyfinSyncBanner {
    val status: JellyfinSyncStatus

    data object SyncingAll : JellyfinSyncBanner {
        override val status = JellyfinSyncStatus.InProgress
    }

    data object SyncingPlaylists : JellyfinSyncBanner {
        override val status = JellyfinSyncStatus.InProgress
    }

    data object SyncingSongs : JellyfinSyncBanner {
        override val status = JellyfinSyncStatus.InProgress
    }

    data class SyncedSummary(val playlistCount: Int, val songCount: Int, val failedCount: Int) : JellyfinSyncBanner {
        override val status: JellyfinSyncStatus
            get() = if (failedCount == 0) JellyfinSyncStatus.Success else JellyfinSyncStatus.Partial
    }

    data class SyncedPlaylists(val count: Int) : JellyfinSyncBanner {
        override val status = JellyfinSyncStatus.Success
    }

    data class SyncedSongs(val count: Int) : JellyfinSyncBanner {
        override val status = JellyfinSyncStatus.Success
    }

    data class Failed(val reason: JellyfinSyncErrorReason) : JellyfinSyncBanner {
        override val status = JellyfinSyncStatus.Error
    }
}

/**
 * Maps a sync failure to a coarse, user-safe category and logs the original throwable, so the
 * banner shows a localized reason instead of leaking raw backend/OkHttp/server text (F112/F113).
 */
private fun Throwable.toJellyfinSyncErrorReason(): JellyfinSyncErrorReason = when (this) {
    is java.net.UnknownHostException,
    is java.net.ConnectException,
    is java.net.SocketTimeoutException -> JellyfinSyncErrorReason.ServerUnavailable
    is retrofit2.HttpException -> when (code()) {
        401, 403 -> JellyfinSyncErrorReason.Auth
        in 500..599 -> JellyfinSyncErrorReason.ServerUnavailable
        else -> JellyfinSyncErrorReason.Network
    }
    is java.io.IOException -> JellyfinSyncErrorReason.Network
    else -> JellyfinSyncErrorReason.Unknown
}

private fun jellyfinFailedBanner(throwable: Throwable): JellyfinSyncBanner.Failed {
    Timber.e(throwable, "Jellyfin sync failed")
    return JellyfinSyncBanner.Failed(throwable.toJellyfinSyncErrorReason())
}

@HiltViewModel
class JellyfinDashboardViewModel @Inject constructor(
    private val repository: JellyfinRepository
) : ViewModel() {

    val playlists: StateFlow<List<JellyfinPlaylistEntity>> = repository.getPlaylists()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    private val _syncBanner = MutableStateFlow<JellyfinSyncBanner?>(null)
    val syncBanner: StateFlow<JellyfinSyncBanner?> = _syncBanner.asStateFlow()

    private val _selectedPlaylistSongs = MutableStateFlow<List<Song>>(emptyList())
    val selectedPlaylistSongs: StateFlow<List<Song>> = _selectedPlaylistSongs.asStateFlow()

    private var loadPlaylistSongsJob: Job? = null

    val username: String? get() = repository.username
    val serverUrl: String? get() = repository.serverUrl
    val isLoggedIn: StateFlow<Boolean> = repository.isLoggedInFlow

    init {
        // Only auto-sync when the cached library is stale, instead of on every dashboard/home open (F111).
        if (repository.isLibrarySyncStale()) {
            syncAllPlaylistsAndSongs()
        }
    }

    fun syncAllPlaylistsAndSongs() {
        viewModelScope.launch {
            _isSyncing.value = true
            _syncBanner.value = JellyfinSyncBanner.SyncingAll
            val result = repository.syncAllPlaylistsAndSongs()
            result.fold(
                onSuccess = { summary ->
                    _syncBanner.value = JellyfinSyncBanner.SyncedSummary(
                        playlistCount = summary.playlistCount,
                        songCount = summary.syncedSongCount,
                        failedCount = summary.failedPlaylistCount
                    )
                },
                onFailure = { _syncBanner.value = jellyfinFailedBanner(it) }
            )
            _isSyncing.value = false
        }
    }

    fun syncPlaylists() {
        viewModelScope.launch {
            _isSyncing.value = true
            _syncBanner.value = JellyfinSyncBanner.SyncingPlaylists
            val result = repository.syncPlaylists()
            result.fold(
                onSuccess = { _syncBanner.value = JellyfinSyncBanner.SyncedPlaylists(it.size) },
                onFailure = { _syncBanner.value = jellyfinFailedBanner(it) }
            )
            _isSyncing.value = false
        }
    }

    fun syncPlaylistSongs(playlistId: String) {
        viewModelScope.launch {
            _isSyncing.value = true
            _syncBanner.value = JellyfinSyncBanner.SyncingSongs
            val result = repository.syncPlaylistSongs(playlistId)
            result.fold(
                onSuccess = { count ->
                    try {
                        repository.syncUnifiedLibrarySongsFromJellyfin()
                    } catch (e: Exception) {
                        Timber.e(e, "Failed to sync unified library after playlist sync")
                    }
                    _syncBanner.value = JellyfinSyncBanner.SyncedSongs(count)
                },
                onFailure = { _syncBanner.value = jellyfinFailedBanner(it) }
            )
            _isSyncing.value = false
        }
    }

    fun loadPlaylistSongs(playlistId: String) {
        loadPlaylistSongsJob?.cancel()
        loadPlaylistSongsJob = viewModelScope.launch {
            repository.getPlaylistSongs(playlistId).collect { songs ->
                _selectedPlaylistSongs.value = songs
            }
        }
    }

    fun deletePlaylist(playlistId: String) {
        viewModelScope.launch {
            repository.deletePlaylist(playlistId)
        }
    }

    fun clearSyncMessage() {
        _syncBanner.value = null
    }

    fun logout() {
        viewModelScope.launch {
            repository.logout()
        }
    }
}
