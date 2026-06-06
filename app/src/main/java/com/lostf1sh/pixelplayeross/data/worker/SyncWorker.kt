package com.lostf1sh.pixelplayeross.data.worker

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.os.Build
import android.os.Trace // Import Trace
import android.provider.MediaStore
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.lostf1sh.pixelplayeross.data.database.AlbumEntity
import com.lostf1sh.pixelplayeross.data.database.ArtistEntity
import com.lostf1sh.pixelplayeross.data.database.MusicDao
import com.lostf1sh.pixelplayeross.data.database.SongArtistCrossRef
import com.lostf1sh.pixelplayeross.data.database.SongEntity
import com.lostf1sh.pixelplayeross.data.database.SourceType
import com.lostf1sh.pixelplayeross.data.database.serializeArtistRefs
import com.lostf1sh.pixelplayeross.data.model.ArtistRef
import com.lostf1sh.pixelplayeross.PixelPlayerApplication
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.media.AudioMetadataReader
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.data.preferences.UserPreferencesRepository
import com.lostf1sh.pixelplayeross.data.repository.LyricsRepository
import com.lostf1sh.pixelplayeross.data.service.PlaybackActivityTracker
import com.lostf1sh.pixelplayeross.utils.AlbumArtCacheManager
import com.lostf1sh.pixelplayeross.utils.AlbumArtUtils
import com.lostf1sh.pixelplayeross.utils.AudioMetaUtils.getAudioMetadata
import com.lostf1sh.pixelplayeross.utils.DirectoryRuleResolver
import com.lostf1sh.pixelplayeross.utils.LocalArtworkUri
import com.lostf1sh.pixelplayeross.utils.buildLocalAudioSelection
import com.lostf1sh.pixelplayeross.utils.normalizeMetadataTextOrEmpty
import com.lostf1sh.pixelplayeross.utils.splitArtistsByDelimiters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import timber.log.Timber

enum class SyncMode {
    INCREMENTAL,
    FULL,
    REBUILD
}

@HiltWorker
class SyncWorker
@AssistedInject
constructor(
        @Assisted appContext: Context,
        @Assisted workerParams: WorkerParameters,
        private val musicDao: MusicDao,
        private val userPreferencesRepository: UserPreferencesRepository,
        private val lyricsRepository: LyricsRepository,
        private val cloudSyncCoordinator: CloudSyncCoordinator
) : CoroutineWorker(appContext, workerParams) {

    private val contentResolver: ContentResolver = appContext.contentResolver
    private var minSongDurationMs: Int = 10000
    private var minTracksPerAlbum: Int = 1

    override suspend fun doWork(): Result =
            withContext(Dispatchers.IO) {
                Trace.beginSection("SyncWorker.doWork")
                try {
                    val syncModeName =
                            inputData.getString(INPUT_SYNC_MODE) ?: SyncMode.INCREMENTAL.name
                    val syncMode = SyncMode.valueOf(syncModeName)
                    val requestedForceMetadata = inputData.getBoolean(INPUT_FORCE_METADATA, false)
                    val requestedRunMaintenance = inputData.getBoolean(INPUT_RUN_MAINTENANCE, true)

                    // Battery / thermal: defer background INCREMENTAL syncs while
                    // music is playing. FULL and REBUILD are skipped from this
                    // gate because they're either user-initiated from settings
                    // (user is waiting for the result) or required for first-run
                    // population. INCREMENTAL is opportunistic and safe to push
                    // back until playback ends.
                    if (
                        syncMode == SyncMode.INCREMENTAL &&
                        PlaybackActivityTracker.isPlaybackActive &&
                        runAttemptCount < MAX_PLAYBACK_DEFERRALS
                    ) {
                        Timber.tag(TAG).d(
                            "SyncWorker deferring INCREMENTAL sync (playback active, attempt=$runAttemptCount)"
                        )
                        return@withContext Result.retry()
                    }

                    Timber.tag(TAG)
                        .i("Starting MediaStore synchronization (Mode: $syncMode, ForceMetadata: $requestedForceMetadata)...")

                    // Promote long full rescans / rebuilds to a foreground service so Android does not
                    // kill them when the app is backgrounded (F138). Best-effort: if the app cannot start
                    // an FGS (background restrictions), keep running as ordinary background work.
                    if (syncMode == SyncMode.FULL || syncMode == SyncMode.REBUILD) {
                        try {
                            setForeground(buildSyncForegroundInfo())
                        } catch (e: Exception) {
                            Timber.tag(TAG).w(e, "Could not promote SyncWorker to foreground")
                        }
                    }

                    val startTime = System.currentTimeMillis()

                    val artistDelimiters = userPreferencesRepository.artistDelimitersFlow.first()
                    val artistWordDelimiters = userPreferencesRepository.artistWordDelimitersFlow.first()
                    val extractArtistsFromTitle = userPreferencesRepository.extractArtistsFromTitleFlow.first()
                    val groupByAlbumArtist =
                            userPreferencesRepository.groupByAlbumArtistFlow.first()
                    val rescanRequired =
                            userPreferencesRepository.artistSettingsRescanRequiredFlow.first()
                    val directoryRulesVersion =
                        userPreferencesRepository.getDirectoryRulesVersion()
                    val lastAppliedDirectoryRulesVersion =
                        userPreferencesRepository.getLastAppliedDirectoryRulesVersion()
                    val directoryRulesChanged =
                        directoryRulesVersion != lastAppliedDirectoryRulesVersion

                    // Feature: Directory Filtering
                    val allowedDirs = userPreferencesRepository.allowedDirectoriesFlow.first()
                    val blockedDirs = userPreferencesRepository.blockedDirectoriesFlow.first()
                    val directoryResolver = DirectoryRuleResolver(allowedDirs, blockedDirs)
                    
                    val lastSyncTimestamp = userPreferencesRepository.getLastSyncTimestamp()

                    // Smart Duration Filtering
                    minSongDurationMs = userPreferencesRepository.getMinSongDuration()
                    minTracksPerAlbum = userPreferencesRepository.minTracksPerAlbumFlow.first()

                    Timber.tag(TAG)
                        .d(
                            "Artist delimiters=$artistDelimiters, groupByAlbumArtist=$groupByAlbumArtist, " +
                                "rescanRequired=$rescanRequired, directoryRulesChanged=$directoryRulesChanged " +
                                "(current=$directoryRulesVersion, applied=$lastAppliedDirectoryRulesVersion)"
                        )

                    // --- DELETION DETECTION PHASE ---
                    // Detect deleted songs efficiently using ID comparison. We do this for
                    // INCREMENTAL and FULL modes. REBUILD clears everything anyway.
                    //
                    // NOTE: detection only — the actual removal is deferred and folded into the
                    // same @Transaction as the upsert below (incrementalSyncMusicData accepts a
                    // deletedSongIds parameter). Deleting here in a separate transaction risked a
                    // failure between the committed delete and the upsert, leaving rows deleted but
                    // their replacements unwritten until the next successful sync.
                    val deletedIds: List<Long> =
                        if (syncMode != SyncMode.REBUILD) {
                            // Only compare MediaStore-backed songs; cloud sources are excluded.
                            val localSongIds = musicDao.getAllMediaStoreSongIds().toHashSet()
                            val mediaStoreIds = fetchMediaStoreIds(directoryResolver)

                            // Identify IDs that are in local DB but not in MediaStore
                            val ids = (localSongIds - mediaStoreIds).toList()
                            if (ids.isNotEmpty()) {
                                Timber.tag(TAG)
                                    .i("Found ${ids.size} deleted songs. Removal deferred to upsert transaction...")
                            } else {
                                Timber.tag(TAG).d("No deleted songs found.")
                            }
                            ids
                        } else {
                            emptyList()
                        }

                    // --- FETCH PHASE ---
                    // Determine what to fetch based on mode
                    val isFreshInstall = musicDao.getSongCount().first() == 0
                    val syncPlan = buildSyncExecutionPlan(
                        requestedMode = syncMode,
                        requestedForceMetadata = requestedForceMetadata,
                        requestedRunMaintenance = requestedRunMaintenance,
                        rescanRequired = rescanRequired,
                        directoryRulesChanged = directoryRulesChanged,
                        isFreshInstall = isFreshInstall
                    )

                    // If REBUILD or FULL or RescanRequired or Fresh Install -> Fetch EVERYTHING
                    // (timestamp = 0)
                    // If INCREMENTAL -> Fetch only changes since lastSyncTimestamp
                    val fetchTimestamp = if (!syncPlan.forceProcessAll) {
                        incrementalFetchTimestampSeconds(lastSyncTimestamp)
                    } else {
                        0L
                    }

                    Timber.tag(TAG)
                        .i("Fetching music from MediaStore (plan=${syncPlan.localScanMode}, since=$fetchTimestamp seconds)...")

                    // Update every 50 songs or ~5% of library
                    val progressBatchSize = 50

                    val songsToInsert = fetchMusicFromMediaStore(
                        fetchTimestamp,
                        syncPlan.forceMetadata,
                        directoryResolver,
                        syncPlan.forceProcessAll,
                        syncPlan.resetExistingLocalData,
                        progressBatchSize
                    ) { current, total, phaseOrdinal ->
                        setProgress(
                            workDataOf(
                                PROGRESS_CURRENT to current,
                                PROGRESS_TOTAL to total,
                                PROGRESS_PHASE to phaseOrdinal
                            )
                        )
                    }

                    Timber.tag(TAG)
                        .i("Fetched ${songsToInsert.size} new/modified songs from MediaStore.")

                    // --- PROCESSING PHASE ---
                    val anySongsFetched = songsToInsert.isNotEmpty()
                    if (anySongsFetched) {

                        val allExistingArtists =
                                if (syncMode == SyncMode.REBUILD) {
                                    emptyList()
                                } else {
                                    musicDao.getAllArtistsListRaw()
                                }
                        val allExistingAlbums =
                                if (syncMode == SyncMode.REBUILD) {
                                    emptyList()
                                } else {
                                    musicDao.getAllAlbumsList(emptyList(), false, 0)
                                }

                        val existingArtistMetadata =
                                allExistingArtists.associate { it.id to (it.imageUrl to it.customImageUri) }
                        
                        // Load all existing artist IDs to ensure stability across incremental syncs
                        val existingArtistIdMap = allExistingArtists.associate { it.name to it.id }.toMutableMap()
                        val maxArtistId = (musicDao.getMaxArtistId() ?: 0L).coerceAtLeast(0L)

                        Timber.tag(TAG)
                            .i("Processing ${songsToInsert.size} songs for upsert. Hash: ${songsToInsert.hashCode()}")

                        val (correctedSongs, albums, artists, crossRefs) =
                                preProcessAndDeduplicateWithMultiArtist(
                                        songs = songsToInsert,
                                        artistDelimiters = artistDelimiters,
                                        wordDelimiters = artistWordDelimiters,
                                        extractFromTitle = extractArtistsFromTitle,
                                        groupByAlbumArtist = groupByAlbumArtist,
                                        existingArtistMetadata = existingArtistMetadata,
                                        existingAlbums = allExistingAlbums,
                                        existingArtistIdMap = existingArtistIdMap,
                                        initialMaxArtistId = maxArtistId
                                )

                        // Use incrementalSyncMusicData for all modes except REBUILD
                        // Even for FULL sync, we can just upsert the values
                        if (syncMode == SyncMode.REBUILD) {
                            setProgress(
                                workDataOf(
                                    PROGRESS_CURRENT to 0,
                                    PROGRESS_TOTAL to correctedSongs.size,
                                    PROGRESS_PHASE to SyncProgress.SyncPhase.SAVING_TO_DATABASE.ordinal
                                )
                            )
                            // Keep clear + insert in one transaction to avoid partial clears
                            // if this worker gets cancelled/replaced mid-rebuild.
                            musicDao.rebuildLocalMusicDataWithCrossRefs(
                                correctedSongs,
                                albums,
                                artists,
                                crossRefs
                            )
                        } else {
                            setProgress(
                                workDataOf(
                                    PROGRESS_CURRENT to 0,
                                    PROGRESS_TOTAL to correctedSongs.size,
                                    PROGRESS_PHASE to SyncProgress.SyncPhase.SAVING_TO_DATABASE.ordinal
                                )
                            )
                            // incrementalSyncMusicData applies the deletions and the upsert
                            // inside a single @Transaction, so an interrupted run can never
                            // leave songs deleted without their replacements being written.
                            musicDao.incrementalSyncMusicData(
                                    songs = correctedSongs,
                                    albums = albums,
                                    artists = artists,
                                    crossRefs = crossRefs,
                                    deletedSongIds = deletedIds
                            )
                        }
                    } else if (syncMode == SyncMode.REBUILD) {
                        setProgress(
                            workDataOf(
                                PROGRESS_CURRENT to 0,
                                PROGRESS_TOTAL to 0,
                                PROGRESS_PHASE to SyncProgress.SyncPhase.SAVING_TO_DATABASE.ordinal
                            )
                        )
                        musicDao.rebuildLocalMusicDataWithCrossRefs(
                            songs = emptyList(),
                            albums = emptyList(),
                            artists = emptyList(),
                            crossRefs = emptyList()
                        )
                    } else if (deletedIds.isNotEmpty()) {
                        // No new/modified songs to upsert, but there are deletions to apply.
                        // Run them through the same atomic path (its own @Transaction) instead
                        // of a standalone delete, keeping the deletion semantics consistent.
                        setProgress(
                            workDataOf(
                                PROGRESS_CURRENT to 0,
                                PROGRESS_TOTAL to deletedIds.size,
                                PROGRESS_PHASE to SyncProgress.SyncPhase.SAVING_TO_DATABASE.ordinal
                            )
                        )
                        musicDao.incrementalSyncMusicData(
                            songs = emptyList(),
                            albums = emptyList(),
                            artists = emptyList(),
                            crossRefs = emptyList(),
                            deletedSongIds = deletedIds
                        )
                    }

                    if (rescanRequired) {
                        userPreferencesRepository.clearArtistSettingsRescanRequired()
                    }

                    if (directoryRulesChanged) {
                        userPreferencesRepository.markDirectoryRulesVersionApplied(
                            directoryRulesVersion
                        )
                    }

                    // Persist the timestamp captured at the START of this run (not "now"): any file
                    // MediaStore indexes while the worker is still running is then re-detected by the
                    // next incremental query instead of being skipped. Always updated on success, even
                    // with no new songs, so we don't re-sync on every launch.
                    userPreferencesRepository.setLastSyncTimestamp(startTime)

                    val endTime = System.currentTimeMillis()
                    Timber.tag(TAG)
                        .i("Synchronization finished successfully in ${endTime - startTime}ms.")

                    // Count total songs for the output
                    val totalSongs = musicDao.getSongCount().first()
                    if (!syncPlan.runMaintenance) {
                        Timber.tag(TAG).d("Skipping library maintenance phases for local-only sync.")
                        return@withContext Result.success(
                            workDataOf(OUTPUT_TOTAL_SONGS to totalSongs)
                        )
                    }

                    // --- LRC SCANNING PHASE ---
                    val autoScanLrc = userPreferencesRepository.autoScanLrcFilesFlow.first()
                    if (autoScanLrc) {
                        Timber.tag(TAG)
                            .i("Auto-scan LRC files enabled. Starting scan phase in chunks...")

                        // Get ALL media store song IDs to scan in manageable chunks
                        val mediaStoreSongIds = musicDao.getAllMediaStoreSongIds()
                        val totalToScan = mediaStoreSongIds.size
                        var totalScannedCount = 0

                        mediaStoreSongIds.chunked(1000).forEach { idBatch ->
                            val batchEntities = musicDao.getSongsByIdsListSimple(idBatch)
                            val batchSongs =
                                    batchEntities.map { entity ->
                                        Song(
                                                id = entity.id.toString(),
                                                title = entity.title,
                                                artist = entity.artistName,
                                                artistId = entity.artistId,
                                                album = entity.albumName,
                                                albumId = entity.albumId,
                                                path = entity.filePath,
                                                contentUriString = entity.contentUriString,
                                                albumArtUriString = entity.albumArtUriString,
                                                duration = entity.duration,
                                                lyrics = entity.lyrics,
                                                dateAdded = entity.dateAdded,
                                                trackNumber = entity.trackNumber,
                                                year = entity.year,
                                                mimeType = entity.mimeType,
                                                bitrate = entity.bitrate,
                                                sampleRate = entity.sampleRate
                                        )
                                    }

                            val batchScannedCount =
                                    lyricsRepository.scanAndAssignLocalLrcFiles(batchSongs) {
                                            current,
                                            total ->
                                        // Progress within the current batch
                                        val overallCurrent = totalScannedCount + current
                                        setProgress(
                                                workDataOf(
                                                        PROGRESS_CURRENT to overallCurrent,
                                                        PROGRESS_TOTAL to totalToScan,
                                                        PROGRESS_PHASE to
                                                                SyncProgress.SyncPhase.SCANNING_LRC
                                                                        .ordinal
                                                )
                                        )
                                    }
                            totalScannedCount += idBatch.size
                            Timber.tag(TAG).d(
                                    "LRC Scan: Processed batch of ${idBatch.size}, total assigned so far: $batchScannedCount"
                            )
                        }

                        Timber.tag(TAG).i("LRC Scan finished for $totalToScan songs.")
                    }

                    // Clean orphaned album art cache files
                    setProgress(
                        workDataOf(
                            PROGRESS_PHASE to SyncProgress.SyncPhase.CLEANING_CACHE.ordinal
                        )
                    )
                    val allSongIds = musicDao.getAllSongIds().toSet()
                    AlbumArtCacheManager.cleanOrphanedCacheFiles(applicationContext, allSongIds)

                    if (cloudSyncCoordinator.needsActiveCloudSync) {
                        setProgress(
                            workDataOf(
                                PROGRESS_PHASE to SyncProgress.SyncPhase.SYNCING_CLOUD.ordinal
                            )
                        )
                    }

                    cloudSyncCoordinator.syncUnifiedCloudLibraries()

                    // Recalculate total
                    val finalTotalSongs = musicDao.getSongCount().first()

                    Result.success(workDataOf(OUTPUT_TOTAL_SONGS to finalTotalSongs))
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e, "Error during MediaStore synchronization")
                    Result.failure()
                } finally {
                    Trace.endSection() // End SyncWorker.doWork
                }
            }

    /** Data class to hold the result of multi-artist preprocessing. */
    private data class MultiArtistProcessResult(
        val songs: List<SongEntity>,
        val albums: List<AlbumEntity>,
        val artists: List<ArtistEntity>,
        val crossRefs: List<SongArtistCrossRef>
    )

    /**
     * Process songs with multi-artist support. Splits artist names by delimiters and creates proper
     * cross-references.
     */
    private fun preProcessAndDeduplicateWithMultiArtist(
            songs: List<SongEntity>,
            artistDelimiters: List<String>,
            wordDelimiters: List<String> = emptyList(),
            extractFromTitle: Boolean = true,
            groupByAlbumArtist: Boolean,
            existingArtistMetadata: Map<Long, Pair<String?, String?>>,
            existingAlbums: List<AlbumEntity>,
            existingArtistIdMap: MutableMap<String, Long>,
            initialMaxArtistId: Long
    ): MultiArtistProcessResult {
        
        val nextArtistId = AtomicLong(initialMaxArtistId + 1)
        val artistNameToId = existingArtistIdMap // Re-use the map passed in
        
        val allCrossRefs = mutableListOf<SongArtistCrossRef>()
        val artistTrackCounts = mutableMapOf<Long, Int>()
        val albumMap = mutableMapOf<AlbumGroupingKey, Long>()
        val artistSplitCache = mutableMapOf<String, List<String>>()
        val correctedSongs = ArrayList<SongEntity>(songs.size)

        existingAlbums
            .sortedBy { it.id }
            .forEach { album ->
                buildAlbumGroupingKeys(album).forEach { key ->
                    albumMap.putIfAbsent(key, album.id)
                }
            }

        songs.forEach { song ->
            val rawArtistName = song.artistName
            val songArtistNameTrimmed = rawArtistName.trim()

            // Split artist field by character + word delimiters
            val allArtistsForSong =
                    artistSplitCache.getOrPut("$rawArtistName\u0000${song.title}\u0000$extractFromTitle") {
                        collectArtistNames(
                            rawArtistName = rawArtistName,
                            title = song.title,
                            artistDelimiters = artistDelimiters,
                            wordDelimiters = wordDelimiters,
                            extractFromTitle = extractFromTitle
                        )
                    }

            allArtistsForSong.forEach { artistName ->
                val normalizedName = artistName.trim()
                if (normalizedName.isNotEmpty() && !artistNameToId.containsKey(normalizedName)) {
                     val id = nextArtistId.getAndIncrement()
                     artistNameToId[normalizedName] = id
                }
            }

            val primaryArtistName =
                    allArtistsForSong.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
                            ?: songArtistNameTrimmed
            val primaryArtistId = artistNameToId[primaryArtistName] ?: song.artistId

            allArtistsForSong.forEachIndexed { index, artistName ->
                val normalizedName = artistName.trim()
                val artistId = artistNameToId[normalizedName]
                if (artistId != null) {
                    val isPrimary = (index == 0) // First artist is primary
                    allCrossRefs.add(
                            SongArtistCrossRef(
                                    songId = song.id,
                                    artistId = artistId,
                                    isPrimary = isPrimary
                            )
                    )
                    artistTrackCounts[artistId] = (artistTrackCounts[artistId] ?: 0) + 1
                }
            }

            // --- Album Logic ---
            val albumKey = buildAlbumGroupingKey(song)
            val finalAlbumId = albumMap.getOrPut(albumKey) { song.albumId }

            // Build serialized artists JSON for efficient loading without JOINs
            val artistRefsForJson = allArtistsForSong.mapIndexed { index, name ->
                val normalizedName = name.trim()
                val artistId = artistNameToId[normalizedName] ?: 0L
                ArtistRef(id = artistId, name = normalizedName, isPrimary = index == 0)
            }.filter { it.name.isNotEmpty() }

            correctedSongs.add(
                    song.copy(
                            artistId = primaryArtistId,
                            artistName = rawArtistName, // Preserving full artist string for display
                            albumId = finalAlbumId,
                            artistsJson = serializeArtistRefs(artistRefsForJson)
                    )
            )
        }

        // Build Entities
        val artistEntities = artistNameToId.map { (name, id) ->
            val count = artistTrackCounts[id] ?: 0
            val metadata = existingArtistMetadata[id]
            ArtistEntity(
                id = id,
                name = name,
                trackCount = count,
                imageUrl = metadata?.first,
                customImageUri = metadata?.second
            )
        }
        
        // Re-calculate Album Entities from the corrected songs to ensure we have valid metadata (Art, Year)
        // which isn't available in the simple albumMap (which only has ID)
        val albumEntities = correctedSongs.groupBy { it.albumId }.map { (catAlbumId, songsInAlbum) ->
             val firstSong = songsInAlbum.first()
             val representativeAlbumArt = songsInAlbum.firstNotNullOfOrNull { it.albumArtUriString }
             val determinedAlbumArtist = chooseAlbumDisplayArtist(
                 songs = songsInAlbum,
                 preferAlbumArtist = groupByAlbumArtist
             )
             val determinedAlbumArtistId = resolveAlbumDisplayArtistId(
                 displayArtist = determinedAlbumArtist,
                 songs = songsInAlbum,
                 artistNameToId = artistNameToId,
                 artistDelimiters = artistDelimiters,
                 wordDelimiters = wordDelimiters
             )
             val metadataAlbumArtist = songsInAlbum
                 .mapNotNull { song ->
                     song.albumArtist?.takeIf { it.isNotBlank() }
                 }
                 .groupingBy { it }
                 .eachCount()
                 .maxByOrNull { it.value }
                 ?.key

             AlbumEntity(
                 id = catAlbumId,
                 title = firstSong.albumName,
                 artistName = determinedAlbumArtist,
                 artistId = determinedAlbumArtistId,
                 albumArtUriString = representativeAlbumArt,
                 songCount = songsInAlbum.size,
                 dateAdded = firstSong.dateAdded,
                 year = firstSong.year,
                 albumArtist = metadataAlbumArtist
             )
        }

        return MultiArtistProcessResult(
                songs = correctedSongs, // Corrected songs have the right Album IDs now
                albums = albumEntities,
                artists = artistEntities,
                crossRefs = allCrossRefs
        )
    }

    /**
     * Fetches a map of Song ID -> Genre Name using the MediaStore.Audio.Genres table. This is
     * necessary because the GENRE column in MediaStore.Audio.Media is not reliably available or
     * populated on all Android versions (especially pre-API 30).
     * 
     * Optimized: 
     * 1. Caches results for 1 hour to avoid refetching on incremental syncs
     * 2. Fetches all genres first, then queries members in parallel with controlled concurrency
     */
    private suspend fun fetchGenreMap(forceRefresh: Boolean = false): Map<Long, String> = coroutineScope {
        // Optimization: Skip genre map on Android 11+ because we can query GENRE directly.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return@coroutineScope emptyMap()
        }

        // Check cache first (valid for 1 hour)
        val now = System.currentTimeMillis()
        val cacheAge = now - genreMapCacheTimestamp
        if (!forceRefresh && genreMapCache.isNotEmpty() && cacheAge < GENRE_CACHE_TTL_MS) {
            Timber.tag(TAG).d("Using cached genre map (${genreMapCache.size} entries, age: ${cacheAge/1000}s)")
            return@coroutineScope genreMapCache
        }
        
        val genreMap = ConcurrentHashMap<Long, String>()
        val genreProjection = arrayOf(MediaStore.Audio.Genres._ID, MediaStore.Audio.Genres.NAME)
        
        // Semaphore to limit concurrent queries (avoid overwhelming ContentResolver)
        val querySemaphore = Semaphore(4)

        try {
            // Step 1: Fetch all genres (single query)
            val genres = mutableListOf<Pair<Long, String>>()
            
            contentResolver.query(
                            MediaStore.Audio.Genres.EXTERNAL_CONTENT_URI,
                            genreProjection,
                            null,
                            null,
                            null
                    )
                    ?.use { cursor ->
                        val idCol = cursor.getColumnIndex(MediaStore.Audio.Genres._ID)
                        val nameCol = cursor.getColumnIndex(MediaStore.Audio.Genres.NAME)

                        if (idCol >= 0 && nameCol >= 0) {
                            while (cursor.moveToNext()) {
                                val genreId = cursor.getLong(idCol)
                                val genreName = cursor.getString(nameCol)

                                if (!genreName.isNullOrBlank() &&
                                                !genreName.equals("unknown", ignoreCase = true)
                                ) {
                                    genres.add(genreId to genreName)
                                }
                            }
                        }
                    }
            
            // Step 2: Fetch members for each genre in parallel (controlled concurrency)
            genres.map { (genreId, genreName) ->
                async(Dispatchers.IO) {
                    querySemaphore.withPermit {
                        val membersUri =
                                MediaStore.Audio.Genres.Members.getContentUri(
                                        "external",
                                        genreId
                                )
                        val membersProjection =
                                arrayOf(MediaStore.Audio.Genres.Members.AUDIO_ID)

                        contentResolver.query(
                                        membersUri,
                                        membersProjection,
                                        null,
                                        null,
                                        null
                                )
                                ?.use { membersCursor ->
                                    val audioIdCol =
                                            membersCursor.getColumnIndex(
                                                    MediaStore.Audio.Genres.Members.AUDIO_ID
                                            )
                                    if (audioIdCol >= 0) {
                                        while (membersCursor.moveToNext()) {
                                            val audioId = membersCursor.getLong(audioIdCol)
                                            // If a song has multiple genres, the last one processed wins.
                                            // This is acceptable as a primary genre for display.
                                            genreMap[audioId] = genreName
                                        }
                                    }
                                }
                    }
                }
            }.awaitAll()
            
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error fetching genre map")
        }
        
        // Update cache
        if (genreMap.isNotEmpty()) {
            genreMapCache = genreMap.toMap()
            genreMapCacheTimestamp = System.currentTimeMillis()
            Timber.tag(TAG).d("Genre map cache updated with ${genreMap.size} entries")
        }
        
        genreMap
    }

    /** Raw data extracted from cursor - lightweight class for fast iteration */
    private data class RawSongData(
            val id: Long,
            val albumId: Long,
            val artistId: Long,
            val filePath: String,
            val mimeType: String?,
            val title: String,
            val artist: String,
            val album: String,
            val albumArtist: String?,
            val duration: Long,
            val trackNumber: Int,
            val discNumber: Int?,
            val year: Int,
            val dateAdded: Long,
            val dateModified: Long,
            val genre: String?
    )

    private fun isSongUnchanged(raw: RawSongData, existing: SongEntity?): Boolean {
        if (existing == null) return false

        val lastSlash = raw.filePath.lastIndexOf('/')
        val parentDir = if (lastSlash > 0) raw.filePath.substring(0, lastSlash) else ""
        val existingDateModifiedSeconds = existing.mediaStoreDateModified.takeIf { it > 0 }
            ?: TimeUnit.MILLISECONDS.toSeconds(existing.dateAdded)
        val existingDateAddedSeconds = existing.mediaStoreDateAdded.takeIf { it > 0 }
            ?: existingDateModifiedSeconds

        return existing.filePath == raw.filePath &&
            existing.parentDirectoryPath == parentDir &&
            (existing.titleUserEdited || existing.title == raw.title) &&
            (existing.artistUserEdited || existing.artistName == raw.artist) &&
            (existing.albumUserEdited || existing.albumName == raw.album) &&
            (existing.albumUserEdited || existing.albumId == raw.albumId) &&
            (existing.artistUserEdited || existing.artistId == raw.artistId) &&
            existing.duration == raw.duration &&
            existing.trackNumber == raw.trackNumber &&
            existing.discNumber == raw.discNumber &&
            existing.year == raw.year &&
            // Pick up external genre/album-artist edits that don't bump DATE_MODIFIED.
            // Genre is only a change signal when the cursor actually supplied one
            // (raw.genre != null, i.e. the API 30+ GENRE column); on older APIs genre
            // is sourced from a separate query/cache and raw.genre is null, so skip it
            // there to avoid spuriously reprocessing every song each incremental sync.
            // User-edited genres are honored via genreUserEdited, like title/artist/album.
            (existing.genreUserEdited || raw.genre == null || existing.genre == raw.genre) &&
            // Like genre: only a change signal when the cursor actually supplied an album artist.
            // MediaStore's ALBUM_ARTIST is often null/blank, which would otherwise spuriously
            // reprocess songs whose existing album artist was resolved from tag metadata.
            (raw.albumArtist == null || existing.albumArtist == raw.albumArtist) &&
            existingDateAddedSeconds == raw.dateAdded &&
            existingDateModifiedSeconds == raw.dateModified
    }

    private suspend fun fetchMusicFromMediaStore(
            sinceTimestamp: Long, // Seconds
            forceMetadata: Boolean,
            directoryResolver: DirectoryRuleResolver,
            forceProcessAll: Boolean,
            resetExistingLocalData: Boolean,
            progressBatchSize: Int,
            onProgress: suspend (current: Int, total: Int, phaseOrdinal: Int) -> Unit
    ): List<SongEntity> {
        Trace.beginSection("SyncWorker.fetchMusicFromMediaStore")

        val deepScan = forceMetadata
        val genreMap = fetchGenreMap() // Load genres upfront

        val projectionList = mutableListOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ARTIST_ID,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.ALBUM_ID,
                MediaStore.Audio.Media.ALBUM_ARTIST,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.DATA,
                MediaStore.Audio.Media.MIME_TYPE,
                MediaStore.Audio.Media.TRACK,
                MediaStore.Audio.Media.YEAR,
                MediaStore.Audio.Media.DATE_ADDED,
                MediaStore.Audio.Media.DATE_MODIFIED
        )

        // API 30+ supports GENRE in the main audio table
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            projectionList.add(MediaStore.Audio.Media.GENRE)
        }

        val projection = projectionList.toTypedArray()

        val (baseSelection, baseArgs) = buildLocalAudioSelection(minSongDurationMs)
        val selectionBuilder = StringBuilder(baseSelection)
        val selectionArgsList = baseArgs.toMutableList()

        // Incremental selection
        if (sinceTimestamp > 0) {
            selectionBuilder.append(
                    " AND (${MediaStore.Audio.Media.DATE_MODIFIED} > ? OR ${MediaStore.Audio.Media.DATE_ADDED} > ?)"
            )
            selectionArgsList.add(sinceTimestamp.toString())
            selectionArgsList.add(sinceTimestamp.toString())
        }

        val selection = selectionBuilder.toString()
        val selectionArgs = selectionArgsList.toTypedArray()

        // Phase 1: Fast cursor iteration to collect raw data
        val rawDataList = mutableListOf<RawSongData>()

        contentResolver.query(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                        projection,
                        selection,
                        selectionArgs,
                        null
                )
                ?.use { cursor ->
                    val totalCount = cursor.count
                    onProgress(0, totalCount, SyncProgress.SyncPhase.FETCHING_MEDIASTORE.ordinal)

                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                    val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                    val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                    val artistIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST_ID)
                    val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                    val albumIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
                    val albumArtistCol = cursor.getColumnIndex(MediaStore.Audio.Media.ALBUM_ARTIST)
                    val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                    val dataCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
                    val mimeTypeCol = cursor.getColumnIndex(MediaStore.Audio.Media.MIME_TYPE)
                    val trackCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK)
                    val yearCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.YEAR)
                    val dateAddedCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
                    val dateModifiedCol =
                            cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED)
                    val genreCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        cursor.getColumnIndex(MediaStore.Audio.Media.GENRE)
                    } else -1

                    while (cursor.moveToNext()) {
                        try {
                            val data = cursor.getString(dataCol) ?: continue
                            val lastSlash = data.lastIndexOf('/')
                            if (lastSlash > 0) {
                                val normalizedParent = data.substring(0, lastSlash)
                                if (directoryResolver.isBlocked(normalizedParent)) {
                                    continue
                                }
                            }
                        } catch (e: Exception) {
                            // Proceed on error
                        }

                        rawDataList.add(
                                RawSongData(
                                        id = cursor.getLong(idCol),
                                        albumId = cursor.getLong(albumIdCol),
                                        artistId = cursor.getLong(artistIdCol),
                                        filePath = cursor.getString(dataCol) ?: "",
                                        mimeType = if (mimeTypeCol >= 0) cursor.getString(mimeTypeCol) else null,
                                        title =
                                                cursor.getString(titleCol)
                                                        .normalizeMetadataTextOrEmpty()
                                                        .ifEmpty { "Unknown Title" },
                                        artist =
                                                cursor.getString(artistCol)
                                                        .normalizeMetadataTextOrEmpty()
                                                        .ifEmpty { "Unknown Artist" },
                                        album =
                                                cursor.getString(albumCol)
                                                        .normalizeMetadataTextOrEmpty()
                                                        .ifEmpty { "Unknown Album" },
                                        albumArtist =
                                                if (albumArtistCol >= 0)
                                                        cursor.getString(albumArtistCol)
                                                                ?.normalizeMetadataTextOrEmpty()
                                                                ?.takeIf { it.isNotBlank() }
                                                else null,
                                        duration = cursor.getLong(durationCol),
                                        trackNumber = cursor.getInt(trackCol) % 1000,
                                        discNumber = (cursor.getInt(trackCol) / 1000).takeIf { it > 0 },
                                        year = cursor.getInt(yearCol),
                                        dateAdded = cursor.getLong(dateAddedCol),
                                        dateModified = cursor.getLong(dateModifiedCol),
                                        genre = if (genreCol >= 0) cursor.getString(genreCol) else null
                                )
                        )
                    }
                }

        if (rawDataList.isEmpty()) {
            Timber.tag(TAG).i("MediaStore cursor produced 0 raw songs after directory filtering")
            Trace.endSection()
            return emptyList()
        }

        // Phase 2: Identify changed songs and merge with existing data in chunks
        val rawSongCount = rawDataList.size
        val songsToProcess = if (forceProcessAll) {
              rawDataList.toList()
        } else {
            // Find existing data for these songs to avoid unnecessary reprocessing
            // and to preserve user edits.
            val results = mutableListOf<RawSongData>()
            
            rawDataList.chunked(500).forEach { batch ->
                val ids = batch.map { it.id }
                val existingMap = musicDao.getSongsByIdsListSimple(ids).associateBy { it.id }
                
                batch.forEach { raw ->
                    val existing = existingMap[raw.id]
                    if (!isSongUnchanged(raw, existing)) {
                        results.add(raw)
                    }
                }
            }
            results
        }

        // rawDataList is no longer needed — release its memory before the processing phase,
        // which may allocate large existingMap objects and metadata ByteArrays.
        rawDataList.clear()

        val totalCount = songsToProcess.size
        Timber.tag(TAG).i(
            "MediaStore raw=$rawSongCount, songsToProcess=$totalCount, forceProcessAll=$forceProcessAll, resetExistingLocalData=$resetExistingLocalData"
        )
        if (totalCount == 0) {
            Trace.endSection()
            return emptyList()
        }

        // Phase 3: Parallel processing of songs with metadata merging
        onProgress(0, totalCount, SyncProgress.SyncPhase.PROCESSING_FILES.ordinal)
        val processedCount = AtomicInteger(0)
        val concurrencyLimit = 4 // Reduced concurrency to save memory
        val semaphore = Semaphore(concurrencyLimit)

        // Process batches sequentially so each batch's existingMap can be GC'd before the next
        // batch is loaded. The semaphore still limits concurrency within each batch.
        val songs = mutableListOf<SongEntity>()
        for (batch in songsToProcess.chunked(200)) {
            val ids = batch.map { it.id }
            val existingMap = if (resetExistingLocalData) emptyMap() else musicDao.getSongsByIdsListSimple(ids).associateBy { it.id }
            val batchResults = coroutineScope {
                batch.map { raw ->
                    async {
                        semaphore.withPermit {
                            val localSong = existingMap[raw.id]
                            val mediaStoreSong =
                                processSongData(
                                    raw = raw,
                                    genreMap = genreMap,
                                    deepScan = deepScan,
                                    forceAlbumArtRefresh = deepScan || localSong != null
                                )

                            val song = if (localSong != null) {
                                // Preserve user-edited fields
                                mediaStoreSong.copy(
                                    dateAdded = if (
                                        localSong.mediaStoreDateAdded > 0 &&
                                        localSong.mediaStoreDateAdded == mediaStoreSong.mediaStoreDateAdded
                                    ) {
                                        localSong.dateAdded
                                    } else {
                                        mediaStoreSong.dateAdded
                                    },
                                    lyrics = localSong.lyrics,
                                    title = if (localSong.titleUserEdited) localSong.title else mediaStoreSong.title,
                                    artistName = if (localSong.artistUserEdited) localSong.artistName else mediaStoreSong.artistName,
                                    albumName = if (localSong.albumUserEdited) localSong.albumName else mediaStoreSong.albumName,
                                    genre = if (localSong.genreUserEdited) localSong.genre else mediaStoreSong.genre,
                                    trackNumber = if (localSong.trackNumber != 0) localSong.trackNumber else mediaStoreSong.trackNumber,
                                    discNumber = localSong.discNumber ?: mediaStoreSong.discNumber,
                                    albumArtUriString = mediaStoreSong.albumArtUriString,
                                    titleUserEdited = localSong.titleUserEdited,
                                    artistUserEdited = localSong.artistUserEdited,
                                    albumUserEdited = localSong.albumUserEdited,
                                    genreUserEdited = localSong.genreUserEdited
                                )
                            } else {
                                mediaStoreSong
                            }

                            val count = processedCount.incrementAndGet()
                            if (count % progressBatchSize == 0 || count == totalCount) {
                                onProgress(count, totalCount, SyncProgress.SyncPhase.PROCESSING_FILES.ordinal)
                            }
                            song
                        }
                    }
                }.awaitAll()
            }
            songs.addAll(batchResults)
        }

        Trace.endSection()
        return songs
    }

    /**
     * Checks if a metadata field from MediaStore is a default/unknown placeholder.
     * MediaStore uses `<unknown>` for unreadable fields, and our normalization
     * may fall back to `"Unknown Artist"` / `"Unknown Album"` etc.
     */
    private fun isDefaultMetadata(value: String): Boolean {
        val lower = value.trim().lowercase()
        return lower.isEmpty() ||
            lower == "<unknown>" ||
            lower == "unknown" ||
            lower == "unknown artist" ||
            lower == "unknown album"
    }

    /**
     * Process a single song's raw data into a SongEntity. This is the CPU/IO intensive work that
     * benefits from parallelization.
     */
    private suspend fun processSongData(
            raw: RawSongData,
            genreMap: Map<Long, String>,
            deepScan: Boolean,
            forceAlbumArtRefresh: Boolean
    ): SongEntity {
        val parentDir = java.io.File(raw.filePath).parent ?: ""
        val contentUriString =
                ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, raw.id)
                        .toString()

        var albumArtUriString =
                AlbumArtUtils.getAlbumArtUri(
                        applicationContext,
                        raw.filePath,
                        raw.id,
                        forceAlbumArtRefresh
                )
        val audioMetadata =
                if (deepScan) getAudioMetadata(musicDao, raw.id, raw.filePath, true) else null

        var title = raw.title
        var artist = raw.artist
        var album = raw.album
        var albumArtist = resolveAlbumArtist(
            rawAlbumArtist = raw.albumArtist,
            metadataAlbumArtist = null
        )
        var trackNumber = raw.trackNumber
        var discNumber = raw.discNumber
        var year = raw.year
        var genre: String? = genreMap[raw.id] ?: raw.genre // Use mapped genre as default, or direct genre from main cursor

        val shouldAugmentMetadata =
                deepScan ||
                        raw.filePath.endsWith(".wav", true) ||
                        raw.filePath.endsWith(".opus", true) ||
                        raw.filePath.endsWith(".ogg", true) ||
                        raw.filePath.endsWith(".oga", true) ||
                        raw.filePath.endsWith(".aiff", true) ||
                        // Fallback: if MediaStore returned default/missing metadata,
                        // try TagLib+JAudioTagger to read actual tags from the file.
                        // MediaStore uses "<unknown>" for unreadable fields;
                        // our normalization may produce "Unknown Artist"/"Unknown Album".
                        isDefaultMetadata(raw.artist) ||
                        isDefaultMetadata(raw.album)

        if (shouldAugmentMetadata) {
            val file = java.io.File(raw.filePath)
            if (file.exists()) {
                try {
                    AudioMetadataReader.read(file)?.let { meta ->
                        if (!meta.title.isNullOrBlank()) title = meta.title
                        if (!meta.artist.isNullOrBlank()) artist = meta.artist
                        if (!meta.album.isNullOrBlank()) album = meta.album
                        albumArtist = resolveAlbumArtist(
                            rawAlbumArtist = albumArtist,
                            metadataAlbumArtist = meta.albumArtist
                        )
                        if (!meta.genre.isNullOrBlank()) genre = meta.genre
                        if (meta.trackNumber != null) trackNumber = meta.trackNumber
                        if (meta.discNumber != null) discNumber = meta.discNumber
                        if (meta.year != null) year = meta.year

                        meta.artwork?.let { art ->
                            albumArtUriString = LocalArtworkUri.buildSongUri(raw.id)
                        }
                    }
                } catch (e: Exception) {
                    Timber.tag(TAG).w(e, "Failed to read metadata via TagLib for ${raw.filePath}")
                }
            }
        }

        return SongEntity(
                id = raw.id,
                title = title,
                artistName = artist,
                artistId = raw.artistId,
                albumArtist = albumArtist,
                albumName = album,
                albumId = raw.albumId,
                contentUriString = contentUriString,
                albumArtUriString = albumArtUriString,
                duration = raw.duration,
                genre = genre,
                filePath = raw.filePath,
                parentDirectoryPath = parentDir,
                trackNumber = trackNumber,
                discNumber = discNumber,
                year = year,
                dateAdded =
                        raw.dateAdded.let { seconds ->
                            if (seconds > 0) TimeUnit.SECONDS.toMillis(seconds)
                            else System.currentTimeMillis()
                        },
                mimeType = audioMetadata?.mimeType ?: raw.mimeType,
                sampleRate = audioMetadata?.sampleRate,
                bitrate = audioMetadata?.bitrate,
                sourceType = SourceType.LOCAL,
                mediaStoreDateAdded = raw.dateAdded,
                mediaStoreDateModified = raw.dateModified
        )
    }

    /**
     * Fetches all IDs currently available in MediaStore to identify deleted songs.
     */
    private fun fetchMediaStoreIds(directoryResolver: DirectoryRuleResolver): Set<Long> {
        val ids = mutableSetOf<Long>()
        val projection = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DATA)
        val (selection, selectionArgs) = buildLocalAudioSelection(minSongDurationMs)

        contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            null
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val dataCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
            while (cursor.moveToNext()) {
                val data = cursor.getString(dataCol)
                if (data != null) {
                    val parentPath = File(data).parent
                    if (parentPath != null && directoryResolver.isBlocked(File(parentPath).absolutePath)) {
                        continue
                    }
                }
                ids.add(cursor.getLong(idCol))
            }
        }
        return ids
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = buildSyncForegroundInfo()

    private fun buildSyncForegroundInfo(): ForegroundInfo {
        val notification = NotificationCompat.Builder(
            applicationContext,
            PixelPlayerApplication.SYNC_NOTIFICATION_CHANNEL_ID
        )
            .setContentTitle(applicationContext.getString(R.string.sync_notification_title))
            .setSmallIcon(R.drawable.monochrome_player)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ForegroundInfo(
                SYNC_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(SYNC_NOTIFICATION_ID, notification)
        }
    }

    companion object {
        const val WORK_NAME = "com.lostf1sh.pixelplayeross.data.worker.SyncWorker"
        private const val SYNC_NOTIFICATION_ID = 47_001
        // Distinct unique name so background maintenance never feeds the WORK_NAME-bound
        // isSyncing/syncProgress flows — the loading indicator stays silent for it.
        const val PERIODIC_MAINTENANCE_WORK_NAME =
            "com.lostf1sh.pixelplayeross.data.worker.SyncWorker.PeriodicMaintenance"
        private const val TAG = "SyncWorker"
        const val INPUT_FORCE_METADATA = "input_force_metadata"
        const val INPUT_RUN_MAINTENANCE = "input_run_maintenance"
        const val INPUT_SYNC_MODE = "input_sync_mode"
        // INCREMENTAL syncs back off this many times while playback is active
        // before running anyway. With WorkManager's exponential backoff
        // (30s -> 60s -> 2m -> 4m -> 8m -> ...), 5 retries cover ~16 minutes.
        private const val MAX_PLAYBACK_DEFERRALS = 5

        // Progress reporting constants
        const val PROGRESS_CURRENT = "progress_current"
        const val PROGRESS_TOTAL = "progress_total"
        const val PROGRESS_PHASE = "progress_phase"
        const val OUTPUT_TOTAL_SONGS = "output_total_songs"

        // Genre cache - shared across worker instances to avoid refetching on incremental syncs
        private const val GENRE_CACHE_TTL_MS = 60 * 60 * 1000L // 1 hour
        @Volatile private var genreMapCache: Map<Long, String> = emptyMap()
        @Volatile private var genreMapCacheTimestamp: Long = 0L
        
        fun invalidateGenreCache() {
            genreMapCache = emptyMap()
            genreMapCacheTimestamp = 0L
            Timber.tag(TAG).d("Genre cache invalidated")
        }

        fun startUpSyncWork(deepScan: Boolean = false) =
                OneTimeWorkRequestBuilder<SyncWorker>()
                        .setInputData(
                                workDataOf(
                                        INPUT_FORCE_METADATA to deepScan,
                                        INPUT_RUN_MAINTENANCE to true,
                                        INPUT_SYNC_MODE to SyncMode.INCREMENTAL.name
                                )
                        )
                        .build()

        fun incrementalSyncWork(
            runMaintenance: Boolean = true
        ) =
                OneTimeWorkRequestBuilder<SyncWorker>()
                        .setInputData(
                                workDataOf(
                                        INPUT_SYNC_MODE to SyncMode.INCREMENTAL.name,
                                        INPUT_RUN_MAINTENANCE to runMaintenance
                                )
                        )
                        .build()

        // Full rescans and rebuilds do heavy bulk writes to Room + the album art cache.
        // Requiring non-critical storage prevents partial/corrupt syncs when the device is
        // nearly full. Not applied to incremental/startup sync so the library still appears
        // immediately when the user opens the app.
        private val heavySyncConstraints: Constraints =
                Constraints.Builder()
                        .setRequiresStorageNotLow(true)
                        .build()

        fun fullSyncWork(
            deepScan: Boolean = false
        ) =
                OneTimeWorkRequestBuilder<SyncWorker>()
                        .setInputData(
                                workDataOf(
                                        INPUT_SYNC_MODE to SyncMode.FULL.name,
                                        INPUT_FORCE_METADATA to deepScan,
                                        INPUT_RUN_MAINTENANCE to true
                                )
                        )
                        .setConstraints(heavySyncConstraints)
                        .build()

        fun rebuildDatabaseWork() =
                OneTimeWorkRequestBuilder<SyncWorker>()
                        .setInputData(
                                workDataOf(
                                        INPUT_SYNC_MODE to SyncMode.REBUILD.name,
                                        INPUT_RUN_MAINTENANCE to true
                                )
                        )
                        .setConstraints(heavySyncConstraints)
                        .build()

        // Daily "heavy cleanup" (LRC scan, album-art cache, cloud sync). Runs a FULL sync
        // with maintenance on, but only while charging on an unmetered network so it stays
        // invisible to the user.
        fun periodicMaintenanceWork(): PeriodicWorkRequest {
            val constraints = Constraints.Builder()
                .setRequiresCharging(true)
                .setRequiredNetworkType(NetworkType.UNMETERED)
                .setRequiresStorageNotLow(true)
                .build()
            return PeriodicWorkRequestBuilder<SyncWorker>(24, TimeUnit.HOURS)
                .setInputData(
                    workDataOf(
                        INPUT_SYNC_MODE to SyncMode.FULL.name,
                        INPUT_RUN_MAINTENANCE to true
                    )
                )
                .setConstraints(constraints)
                .build()
        }
    }
    
}
