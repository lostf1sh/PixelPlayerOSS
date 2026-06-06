package com.lostf1sh.pixelplayeross.ui.glancewidget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
// import androidx.datastore.preferences.protobuf.ByteString // No longer needed
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.lostf1sh.pixelplayeross.MainActivity
import com.lostf1sh.pixelplayeross.data.model.PlayerInfo
import com.lostf1sh.pixelplayeross.R
import androidx.core.graphics.scale
import androidx.glance.unit.ColorProvider
import com.lostf1sh.pixelplayeross.data.model.QueueItem
import com.lostf1sh.pixelplayeross.utils.createScalableBackgroundBitmap
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

class PixelPlayerGlanceWidget : GlanceAppWidget() {

    companion object {
        // Sizes defined for different widget configurations
        private val VERY_THIN_LAYOUT_SIZE = DpSize(width = 200.dp, height = 60.dp)
        private val THIN_LAYOUT_SIZE = DpSize(width = 250.dp, height = 80.dp)
        private val SMALL_HORIZONTAL_LAYOUT_SIZE = DpSize(width = 110.dp, height = 60.dp)
        private val ONE_BY_ONE_LAYOUT_SIZE = DpSize(width = 110.dp, height = 110.dp)
        private val GABE_LAYOUT_SIZE = DpSize(width = 110.dp, height = 220.dp)
        private val GABE_TWO_HEIGHT_LAYOUT_SIZE = DpSize(width = 110.dp, height = 200.dp)
        private val SMALL_LAYOUT_SIZE = DpSize(width = 120.dp, height = 100.dp)
        private val MEDIUM_LAYOUT_SIZE = DpSize(width = 250.dp, height = 150.dp)
        private val LARGE_LAYOUT_SIZE = DpSize(width = 300.dp, height = 180.dp)
        private val EXTRA_LARGE_LAYOUT_SIZE = DpSize(width = 300.dp, height = 220.dp)
        private val EXTRA_LARGE_PLUS_LAYOUT_SIZE = DpSize(width = 350.dp, height = 260.dp)
        private val HUGE_LAYOUT_SIZE = DpSize(width = 400.dp, height = 300.dp)

        // Number of queue thumbnails the ExtraLarge layout actually renders; prewarm matches it.
        private const val EXTRA_LARGE_QUEUE_THUMBNAIL_COUNT = 4
    }

    override val sizeMode = SizeMode.Exact
    override val stateDefinition = PlayerInfoStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        // Pre-decode album art (URI-backed) off the composition path, before provideContent,
        // so the @Composable body hits AlbumArtBitmapCache instead of performing blocking
        // ContentResolver/file I/O synchronously during Glance composition.
        runCatching {
            getAppWidgetState(context, PlayerInfoStateDefinition, id)
        }.getOrNull()?.let { state ->
            prewarmArtworkCache(context, state)
        }

        provideContent {
            val playerInfo = currentState<PlayerInfo>()
            val currentSize = LocalSize.current

            Timber.tag("PixelPlayerGlanceWidget")
                .d("Providing Glance. PlayerInfo: title='${playerInfo.songTitle}', artist='${playerInfo.artistName}', isPlaying=${playerInfo.isPlaying}, hasBitmap=${playerInfo.albumArtBitmapData != null}, progress=${playerInfo.currentPositionMs}/${playerInfo.totalDurationMs}")

            GlanceTheme {
                WidgetUi(playerInfo = playerInfo, size = currentSize, context = context)
            }
        }
    }

    /**
     * Decodes URI-backed album art into [AlbumArtBitmapCache] off the composition thread.
     * The composable reads the same cache keys ("uri:<rawUri>"), so after this runs its
     * lookups hit the cache and it never performs blocking ContentResolver/file I/O during
     * Glance composition. Embedded [PlayerInfo.albumArtBitmapData] is left to the composable
     * (in-memory decode, no I/O). Failures are swallowed so the widget still renders.
     */
    private suspend fun prewarmArtworkCache(context: Context, playerInfo: PlayerInfo) {
        withContext(Dispatchers.IO) {
            val density = context.resources.displayMetrics.density

            // Primary art only when there is no embedded bitmap (the composable's URI fallback).
            if (playerInfo.albumArtBitmapData == null) {
                playerInfo.albumArtUri?.let { rawUri ->
                    val targetPx = (220f * density).toInt().coerceAtLeast(1)
                    decodeIntoCacheIfAbsent(context, rawUri, targetPx)
                }
            }

            // Queue thumbnails (ExtraLarge layout) decode at 58.dp; match that exactly. Only the
            // first EXTRA_LARGE_QUEUE_THUMBNAIL_COUNT items are ever rendered, so don't prewarm more.
            val queueTargetPx = (58f * density).toInt().coerceAtLeast(1)
            playerInfo.queue.take(EXTRA_LARGE_QUEUE_THUMBNAIL_COUNT).forEach { item ->
                item.albumArtUri?.let { rawUri ->
                    decodeIntoCacheIfAbsent(context, rawUri, queueTargetPx)
                }
            }
        }
    }

    private fun decodeIntoCacheIfAbsent(context: Context, rawUri: String, targetPx: Int) {
        val cacheKey = "uri:$rawUri"
        if (AlbumArtBitmapCache.getBitmap(cacheKey) != null) return
        runCatching {
            decodeWidgetAlbumArtBitmap(
                context = context,
                rawUri = rawUri,
                targetWidthPx = targetPx,
                targetHeightPx = targetPx,
            )
        }.getOrNull()?.let { bitmap ->
            AlbumArtBitmapCache.putBitmap(cacheKey, bitmap)
        }
    }

    @Composable
    private fun WidgetUi(
        playerInfo: PlayerInfo,
        size: DpSize,
        context: Context
    ) {
        val title = playerInfo.songTitle.ifEmpty { context.getString(R.string.app_name) }
        val artist = playerInfo.artistName.ifEmpty { context.getString(R.string.widget_tap_to_open) }
        val isPlaying = playerInfo.isPlaying
        val isFavorite = playerInfo.isFavorite
        val albumArtBitmapData = playerInfo.albumArtBitmapData
        val albumArtUri = playerInfo.albumArtUri

        Timber.tag("PixelPlayerGlanceWidget")
            .d("WidgetUi: PlayerInfo received. Title: $title, Artist: $artist, HasBitmapData: ${albumArtBitmapData != null}, BitmapDataSize: ${albumArtBitmapData?.size ?: "N/A"}")

        // Source colors from the album-art theme palette computed by MusicService
        // (PlayerInfo.themeColors), falling back to GlanceTheme when it is null. This
        // mirrors the sibling widgets (BarWidget4x1, ControlWidget4x2, GridWidget2x2)
        // so the primary widget honors the same accent instead of discarding it.
        val colors = playerInfo.getWidgetColors()
        val actualBackgroundColor = colors.surface
        val onBackgroundColor = colors.onSurface

        val baseModifier = GlanceModifier
            .fillMaxSize()
            .clickable(actionStartActivity<MainActivity>())

        Box(
            GlanceModifier.fillMaxSize()
        ) {
            val isOneColumn = size.width < SMALL_LAYOUT_SIZE.width
            val isSmallHeight = size.height < SMALL_LAYOUT_SIZE.height

            if (isOneColumn) {
                when {
                    size.height <= ONE_BY_ONE_LAYOUT_SIZE.height -> OneByOneWidgetLayout(
                        modifier = baseModifier,
                        backgroundColor = actualBackgroundColor,
                        bgCornerRadius = 60.dp,
                        isPlaying = isPlaying,
                        colors = colors
                    )
                    size.height <= GABE_TWO_HEIGHT_LAYOUT_SIZE.height -> GabeTwoHeightWidgetLayout(
                        modifier = baseModifier,
                        backgroundColor = actualBackgroundColor,
                        bgCornerRadius = 60.dp,
                        albumArtBitmapData = albumArtBitmapData,
                        albumArtUri = albumArtUri,
                        isPlaying = isPlaying,
                        context = context,
                        colors = colors
                    )
                    else -> GabeWidgetLayout(
                        modifier = baseModifier,
                        backgroundColor = actualBackgroundColor,
                        bgCornerRadius = 360.dp,
                        albumArtBitmapData = albumArtBitmapData,
                        albumArtUri = albumArtUri,
                        isPlaying = isPlaying,
                        context = context,
                        colors = colors
                    )
                }
            } else if (isSmallHeight) {
                when {
                    size.width < VERY_THIN_LAYOUT_SIZE.width -> SmallHorizontalWidgetLayout(
                        modifier = baseModifier,
                        backgroundColor = actualBackgroundColor,
                        bgCornerRadius = 60.dp,
                        albumArtBitmapData = albumArtBitmapData,
                        albumArtUri = albumArtUri,
                        isPlaying = isPlaying,
                        context = context,
                        colors = colors
                    )
                    size.width < THIN_LAYOUT_SIZE.width -> VeryThinWidgetLayout(
                        modifier = baseModifier,
                        title = title,
                        artist = artist,
                        albumArtBitmapData = albumArtBitmapData,
                        albumArtUri = albumArtUri,
                        isPlaying = isPlaying,
                        textColor = onBackgroundColor,
                        context = context,
                        backgroundColor = actualBackgroundColor,
                        bgCornerRadius = 60.dp,
                        colors = colors
                    )
                    else -> ThinWidgetLayout(
                        modifier = baseModifier,
                        backgroundColor = actualBackgroundColor,
                        bgCornerRadius = 60.dp,
                        title = title,
                        artist = artist,
                        albumArtBitmapData = albumArtBitmapData,
                        albumArtUri = albumArtUri,
                        isPlaying = isPlaying,
                        textColor = onBackgroundColor,
                        context = context,
                        colors = colors
                    )
                }
            } else {
                when {
                    size.width < MEDIUM_LAYOUT_SIZE.width || size.height < MEDIUM_LAYOUT_SIZE.height -> SmallWidgetLayout(
                        modifier = baseModifier,
                        backgroundColor = actualBackgroundColor,
                        bgCornerRadius = 28.dp,
                        albumArtBitmapData = albumArtBitmapData,
                        albumArtUri = albumArtUri,
                        isPlaying = isPlaying,
                        context = context,
                        colors = colors
                    )
                    size.width < LARGE_LAYOUT_SIZE.width || size.height < LARGE_LAYOUT_SIZE.height -> MediumWidgetLayout(
                        modifier = baseModifier,
                        title = title,
                        artist = artist,
                        albumArtBitmapData = albumArtBitmapData,
                        albumArtUri = albumArtUri,
                        isPlaying = isPlaying,
                        textColor = onBackgroundColor,
                        context = context,
                        backgroundColor = actualBackgroundColor,
                        bgCornerRadius = 28.dp,
                        colors = colors
                    )
                    size.width < EXTRA_LARGE_LAYOUT_SIZE.width || size.height < EXTRA_LARGE_LAYOUT_SIZE.height -> LargeWidgetLayout(
                        modifier = baseModifier,
                        title = title,
                        artist = artist,
                        albumArtBitmapData = albumArtBitmapData,
                        albumArtUri = albumArtUri,
                        backgroundColor = actualBackgroundColor,
                        bgCornerRadius = 28.dp,
                        isPlaying = isPlaying,
                        isFavorite = isFavorite,
                        textColor = onBackgroundColor,
                        context = context,
                        colors = colors
                    )
                    else -> ExtraLargeWidgetLayout(
                        modifier = baseModifier,
                        title = title,
                        artist = artist,
                        albumArtBitmapData = albumArtBitmapData,
                        albumArtUri = albumArtUri,
                        isPlaying = isPlaying,
                        backgroundColor = actualBackgroundColor,
                        bgCornerRadius = 28.dp,
                        textColor = onBackgroundColor,
                        context = context,
                        queue = playerInfo.queue,
                        colors = colors
                    )
                }
            }
        }
    }

    @Composable
    fun VeryThinWidgetLayout(
        modifier: GlanceModifier,
        title: String,
        backgroundColor: ColorProvider,
        bgCornerRadius: Dp,
        artist: String,
        albumArtBitmapData: ByteArray?,
        albumArtUri: String?,
        isPlaying: Boolean,
        textColor: ColorProvider,
        context: Context,
        colors: WidgetColors
    ) {
        val secondaryColor = colors.prevNextBackground
        val onSecondaryColor = colors.prevNextIcon
        val primaryContainerColor = colors.playPauseBackground
        val onPrimaryContainerColor = colors.playPauseIcon
        val size = LocalSize.current
        val albumArtSize = size.height - 32.dp

        Box(
            modifier = modifier
                .background(backgroundColor)
                .cornerRadius(bgCornerRadius)
                .padding(16.dp) // Padding applied to the outer box
        ) {
            Row(
                modifier = GlanceModifier
                    .fillMaxSize()
                    .cornerRadius(bgCornerRadius),
                verticalAlignment = Alignment.CenterVertically,
                horizontalAlignment = Alignment.Horizontal.CenterHorizontally
            ) {

                AlbumArtImageGlance(
                    modifier = GlanceModifier
                        .size(albumArtSize),
                    bitmapData = albumArtBitmapData,
                    albumArtUri = albumArtUri,
                    context = context,
                    cornerRadius = bgCornerRadius
                )
                Spacer(GlanceModifier.width(10.dp))
                Column(modifier = GlanceModifier.defaultWeight()) {
                    Text(text = title, style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Bold, color = textColor), maxLines = 1)
                    if (artist.isNotEmpty() && artist != context.getString(R.string.widget_tap_to_open)) {
                        Text(text = artist, style = TextStyle(fontSize = 14.sp, color = colors.artist), maxLines = 1)
                    }
                }
                Spacer(GlanceModifier.width(8.dp))
                PlayPauseButtonGlance(
                    modifier = GlanceModifier
                        .defaultWeight()
                        .size(48.dp, 48.dp)
                        .fillMaxHeight(),
                    backgroundColor = primaryContainerColor,
                    iconColor = onPrimaryContainerColor,
                    isPlaying = isPlaying,
                    iconSize = 26.dp,
                    cornerRadius = 10.dp
                )
                Spacer(GlanceModifier.width(10.dp))
                NextButtonGlance(
                    modifier = GlanceModifier
                        .defaultWeight()
                        .size(48.dp, 48.dp)
                        .fillMaxHeight(),
                    iconColor = onSecondaryColor,
                    iconSize = 26.dp,
                    backgroundColor = secondaryColor,
                    cornerRadius = 10.dp
                )
            }
        }
    }


    @Composable
    fun ThinWidgetLayout(
        modifier: GlanceModifier,
        backgroundColor: ColorProvider,
        bgCornerRadius: Dp,
        title: String,
        artist: String,
        albumArtBitmapData: ByteArray?,
        albumArtUri: String?,
        isPlaying: Boolean,
        textColor: ColorProvider,
        context: Context,
        colors: WidgetColors
    ) {
        val secondaryColor = colors.prevNextBackground
        val onSecondaryColor = colors.prevNextIcon
        val primaryContainerColor = colors.playPauseBackground
        val onPrimaryContainerColor = colors.playPauseIcon
        val size = LocalSize.current
        val albumArtSize = size.height - 32.dp

        Box(
            modifier = modifier
                .background(backgroundColor)
                .cornerRadius(bgCornerRadius)
                .padding(16.dp) // Padding applied to the outer box
        ) {
            Row(
                modifier = GlanceModifier
                    .fillMaxSize()
                    .cornerRadius(bgCornerRadius)
                ,
                verticalAlignment = Alignment.CenterVertically,
                horizontalAlignment = Alignment.Horizontal.CenterHorizontally
            ) {

                AlbumArtImageGlance(
                    modifier = GlanceModifier
                        .size(albumArtSize),
                    bitmapData = albumArtBitmapData,
                    albumArtUri = albumArtUri,
                    context = context,
                    cornerRadius = bgCornerRadius
                )
                Spacer(GlanceModifier.width(14.dp))
                Column(modifier = GlanceModifier.defaultWeight()) {
                    Text(text = title, style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Bold, color = textColor), maxLines = 1)
                    if (artist.isNotEmpty() && artist != context.getString(R.string.widget_tap_to_open)) {
                        Text(text = artist, style = TextStyle(fontSize = 14.sp, color = colors.artist), maxLines = 1)
                    }
                }
                Spacer(GlanceModifier.width(8.dp))
                PlayPauseButtonGlance(
                    modifier = GlanceModifier
                        .defaultWeight()
                        .size(48.dp, 48.dp)
                        .fillMaxHeight(),
                    backgroundColor = primaryContainerColor,
                    iconColor = onPrimaryContainerColor,
                    isPlaying = isPlaying,
                    iconSize = 26.dp,
                    cornerRadius = 10.dp
                )
                Spacer(GlanceModifier.width(10.dp))
                NextButtonGlance(
                    modifier = GlanceModifier
                        .defaultWeight()
                        .size(48.dp, 48.dp)
                        .fillMaxHeight(),
                    iconColor = onSecondaryColor,
                    iconSize = 26.dp,
                    backgroundColor = secondaryColor,
                    cornerRadius = 10.dp
                )
            }
        }
    }

    @Composable
    fun GabeTwoHeightWidgetLayout(
        modifier: GlanceModifier,
        backgroundColor: ColorProvider,
        bgCornerRadius: Dp,
        albumArtBitmapData: ByteArray?,
        albumArtUri: String?,
        isPlaying: Boolean,
        context: Context,
        colors: WidgetColors
    ) {
        val secondaryColor = colors.prevNextBackground
        val onSecondaryColor = colors.prevNextIcon
        val primaryContainerColor = colors.playPauseBackground
        val onPrimaryContainerColor = colors.playPauseIcon

        Box(
            modifier = modifier
                .background(backgroundColor)
                .cornerRadius(bgCornerRadius)
                .padding(16.dp)
        ) {
            Column(
                modifier = GlanceModifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalAlignment = Alignment.Vertical.CenterVertically
            ) {

                AlbumArtImageGlance(
                    modifier = GlanceModifier
                        .defaultWeight()
                        .height(48.dp)
                        //.padding(4.dp)
                    ,
                    bitmapData = albumArtBitmapData,
                    albumArtUri = albumArtUri,
                    //size = 48.dp,
                    context = context,
                    cornerRadius = 64.dp
                )
                Spacer(GlanceModifier.height(14.dp))
                Column(
                    modifier = GlanceModifier
                        .defaultWeight()
                        .cornerRadius(bgCornerRadius)
                ) {
                    PlayPauseButtonGlance(
                        modifier = GlanceModifier
                            .defaultWeight()
                            .fillMaxWidth(),
                        backgroundColor = primaryContainerColor,
                        iconColor = onPrimaryContainerColor,
                        isPlaying = isPlaying,
                        iconSize = 26.dp,
                        cornerRadius = 10.dp
                    )
                    Spacer(GlanceModifier.height(10.dp))
                    NextButtonGlance(
                        modifier = GlanceModifier
                            .defaultWeight()
                            .fillMaxWidth(),
                        iconColor = onSecondaryColor,
                        iconSize = 26.dp,
                        backgroundColor = secondaryColor,
                        cornerRadius = 10.dp
                    )
                }
            }
        }
    }

    @Composable
    fun GabeWidgetLayout(
        modifier: GlanceModifier,
        backgroundColor: ColorProvider,
        bgCornerRadius: Dp,
        albumArtBitmapData: ByteArray?,
        albumArtUri: String?,
        isPlaying: Boolean,
        context: Context,
        colors: WidgetColors
    ) {
        val secondaryColor = colors.prevNextBackground
        val onSecondaryColor = colors.prevNextIcon
        val primaryContainerColor = colors.playPauseBackground
        val onPrimaryContainerColor = colors.playPauseIcon

        Box(
            modifier = modifier
                .background(backgroundColor)
                .cornerRadius(bgCornerRadius)
                .padding(16.dp)
        ) {
            Column(
                modifier = GlanceModifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalAlignment = Alignment.Vertical.CenterVertically
            ) {
                AlbumArtImageGlance(
                    modifier = GlanceModifier
                        .defaultWeight()
                        .fillMaxWidth()
                        .height(48.dp),
                    bitmapData = albumArtBitmapData,
                    albumArtUri = albumArtUri,
                    context = context,
                    cornerRadius = 64.dp
                )
                Spacer(GlanceModifier.height(14.dp))
                Column(
                    modifier = GlanceModifier
                        .defaultWeight()
                        .cornerRadius(bgCornerRadius)
                ) {
                    PreviousButtonGlance(
                        modifier = GlanceModifier
                            .defaultWeight()
                            .fillMaxWidth(),
                        iconColor = onSecondaryColor,
                        iconSize = 26.dp,
                        backgroundColor = secondaryColor,
                        cornerRadius = 10.dp
                    )
                    Spacer(GlanceModifier.height(10.dp))
                    PlayPauseButtonGlance(
                        modifier = GlanceModifier
                            .defaultWeight()
                            .fillMaxWidth(),
                        backgroundColor = primaryContainerColor,
                        iconColor = onPrimaryContainerColor,
                        isPlaying = isPlaying,
                        iconSize = 26.dp,
                        cornerRadius = 10.dp
                    )
                    Spacer(GlanceModifier.height(10.dp))
                    NextButtonGlance(
                        modifier = GlanceModifier
                            .defaultWeight()
                            .fillMaxWidth(),
                        iconColor = onSecondaryColor,
                        iconSize = 26.dp,
                        backgroundColor = secondaryColor,
                        cornerRadius = 10.dp
                    )
                }
            }
        }
    }

    @Composable
    fun OneByOneWidgetLayout(
        modifier: GlanceModifier,
        backgroundColor: ColorProvider,
        bgCornerRadius: Dp,
        isPlaying: Boolean,
        colors: WidgetColors
    ) {
        val primaryContainerColor = colors.playPauseBackground
        val onPrimaryContainerColor = colors.playPauseIcon

        Box(
            modifier = modifier
                .background(backgroundColor)
                .cornerRadius(bgCornerRadius)
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            PlayPauseButtonGlance(
                modifier = GlanceModifier.fillMaxSize(),
                backgroundColor = primaryContainerColor,
                iconColor = onPrimaryContainerColor,
                isPlaying = isPlaying,
                iconSize = 36.dp,
                cornerRadius = 30.dp
            )
        }
    }

    @Composable
    fun SmallHorizontalWidgetLayout(
        modifier: GlanceModifier,
        backgroundColor: ColorProvider,
        bgCornerRadius: Dp,
        albumArtBitmapData: ByteArray?,
        albumArtUri: String?,
        isPlaying: Boolean,
        context: Context,
        colors: WidgetColors
    ) {
        val primaryContainerColor = colors.playPauseBackground
        val onPrimaryContainerColor = colors.playPauseIcon

        Box(
            modifier = modifier
                .background(backgroundColor)
                .cornerRadius(bgCornerRadius)
                .padding(16.dp)
        ) {
            Row(
                modifier = GlanceModifier
                    .fillMaxSize()
                    .cornerRadius(bgCornerRadius),
                verticalAlignment = Alignment.CenterVertically,
                horizontalAlignment = Alignment.Horizontal.CenterHorizontally
            ) {
                AlbumArtImageGlance(
                    modifier = GlanceModifier.padding(vertical = 6.dp),
                    bitmapData = albumArtBitmapData,
                    albumArtUri = albumArtUri,
                    size = 58.dp,
                    context = context,
                    cornerRadius = 64.dp
                )
                Spacer(GlanceModifier.width(14.dp))
                PlayPauseButtonGlance(
                    modifier = GlanceModifier
                        .defaultWeight()
                        .fillMaxHeight(),
                    backgroundColor = primaryContainerColor,
                    iconColor = onPrimaryContainerColor,
                    isPlaying = isPlaying,
                    iconSize = 26.dp,
                    cornerRadius = 10.dp
                )
            }
        }
    }

    @Composable
    fun SmallWidgetLayout(
        modifier: GlanceModifier,
        backgroundColor: ColorProvider,
        bgCornerRadius: Dp,
        albumArtBitmapData: ByteArray?,
        albumArtUri: String?,
        isPlaying: Boolean,
        context: Context,
        colors: WidgetColors
    ) {
        val secondaryColor = colors.prevNextBackground
        val onSecondaryColor = colors.prevNextIcon
        val primaryContainerColor = colors.playPauseBackground
        val onPrimaryContainerColor = colors.playPauseIcon
        val buttonCornerRadius = 16.dp
        val playButtonCornerRadius = if (isPlaying) 12.dp else 60.dp

        Box(
            modifier = modifier
                .background(backgroundColor)
                .cornerRadius(bgCornerRadius)
                .padding(12.dp) // Using 12dp for this smaller layout
        ) {
            Column(
                modifier = GlanceModifier.fillMaxSize(),
                verticalAlignment = Alignment.Top,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    modifier = GlanceModifier
                        .defaultWeight()
                        .fillMaxWidth(),
                    verticalAlignment = Alignment.Vertical.Top,
                    horizontalAlignment = Alignment.Start
                ) {
                    AlbumArtImageGlance(
                        modifier = GlanceModifier.defaultWeight(),
                        bitmapData = albumArtBitmapData,
                        albumArtUri = albumArtUri,
                        context = context,
                        cornerRadius = 64.dp
                    )
                    //Spacer(GlanceModifier.width(10.dp))
                }
                Spacer(GlanceModifier.height(8.dp))
                PlayPauseButtonGlance(
                    modifier = GlanceModifier
                        .defaultWeight()
                        .fillMaxWidth()
                        .height(50.dp),
                    isPlaying = isPlaying,
                    cornerRadius = playButtonCornerRadius,
                    iconSize = 26.dp,
                    backgroundColor = primaryContainerColor,
                    iconColor = onPrimaryContainerColor
                )
                Spacer(GlanceModifier.height(8.dp))
                Row(
                    modifier = GlanceModifier
                        .defaultWeight()
                        .fillMaxWidth()
                        .height(50.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    PreviousButtonGlance(
                        modifier = GlanceModifier
                            .defaultWeight()
                            .fillMaxHeight(),
                        iconSize = 26.dp,
                        cornerRadius = buttonCornerRadius,
                        backgroundColor = secondaryColor,
                        iconColor = onSecondaryColor
                    )
                    Spacer(GlanceModifier.width(8.dp))
                    NextButtonGlance(
                        modifier = GlanceModifier
                            .defaultWeight()
                            .fillMaxHeight(),
                        iconSize = 26.dp,
                        cornerRadius = buttonCornerRadius,
                        backgroundColor = secondaryColor,
                        iconColor = onSecondaryColor
                    )
                }
            }
        }
    }

    @Composable
    fun MediumWidgetLayout(
        modifier: GlanceModifier,
        title: String,
        artist: String,
        backgroundColor: ColorProvider,
        bgCornerRadius: Dp,
        albumArtBitmapData: ByteArray?,
        albumArtUri: String?,
        isPlaying: Boolean,
        textColor: ColorProvider,
        context: Context,
        colors: WidgetColors
    ) {
        val secondaryColor = colors.prevNextBackground
        val onSecondaryColor = colors.prevNextIcon
        val primaryContainerColor = colors.playPauseBackground
        val onPrimaryContainerColor = colors.playPauseIcon
        val buttonCornerRadius = 60.dp
        val playButtonCornerRadius = if (isPlaying) 14.dp else 60.dp

        // *** FIX: Apply padding to the outer Box for consistency ***
        Box(
            modifier = modifier
                .background(backgroundColor)
                .cornerRadius(bgCornerRadius)
                .padding(16.dp)
        ) {
            // *** FIX: Removed padding from the inner Column ***
            Column(
                modifier = GlanceModifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Top part: Album Art + Title/Artist
                Row(
                    modifier = GlanceModifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AlbumArtImageGlance(
                        bitmapData = albumArtBitmapData,
                        albumArtUri = albumArtUri,
                        size = 80.dp,
                        context = context,
                        cornerRadius = 16.dp
                    )
                    Spacer(GlanceModifier.width(12.dp))
                    Column(modifier = GlanceModifier.defaultWeight()) {
                        Text(
                            text = title,
                            style = TextStyle(
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = textColor
                            ),
                            maxLines = 2
                        )
                        Spacer(GlanceModifier.height(4.dp))
                        Text(
                            text = artist,
                            style = TextStyle(fontSize = 13.sp, color = colors.artist),
                            maxLines = 2
                        )
                    }
                }

                // Spacer to push buttons down
                Spacer(GlanceModifier.height(12.dp))

                // Bottom part: Control Buttons
                Row(
                    modifier = GlanceModifier
                        .defaultWeight()
                        .fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    PreviousButtonGlance(
                        modifier = GlanceModifier
                            .defaultWeight()
                            .fillMaxHeight(),
                        iconColor = onSecondaryColor,
                        backgroundColor = secondaryColor,
                        cornerRadius = buttonCornerRadius
                    )
                    Spacer(GlanceModifier.width(8.dp))
                    PlayPauseButtonGlance(
                        modifier = GlanceModifier
                            .defaultWeight()
                            .fillMaxHeight(),
                        isPlaying = isPlaying,
                        iconColor = onPrimaryContainerColor,
                        backgroundColor = primaryContainerColor,
                        cornerRadius = playButtonCornerRadius
                    )
                    Spacer(GlanceModifier.width(8.dp))
                    NextButtonGlance(
                        modifier = GlanceModifier
                            .defaultWeight()
                            .fillMaxHeight(),
                        iconColor = onSecondaryColor,
                        backgroundColor = secondaryColor,
                        cornerRadius = buttonCornerRadius
                    )
                }
            }
        }
    }

    @Composable
    fun LargeWidgetLayout(
        modifier: GlanceModifier,
        title: String,
        artist: String,
        albumArtBitmapData: ByteArray?,
        albumArtUri: String?,
        backgroundColor: ColorProvider,
        bgCornerRadius: Dp,
        isPlaying: Boolean,
        isFavorite: Boolean,
        textColor: ColorProvider,
        context: Context,
        colors: WidgetColors
    ) {
        // *** FIX: Apply padding to the outer Box for consistency ***
        Box(
            modifier = modifier
                .background(backgroundColor)
                .cornerRadius(bgCornerRadius)
                .padding(16.dp)
        ) {
            // *** FIX: Removed padding from the inner Column ***
            Column(modifier = GlanceModifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = GlanceModifier.fillMaxWidth().padding(bottom = 8.dp)) {
                    AlbumArtImageGlance(
                        bitmapData = albumArtBitmapData,
                        albumArtUri = albumArtUri,
                        size = 64.dp,
                        context = context,
                        cornerRadius = 18.dp
                    )
                    Spacer(GlanceModifier.width(12.dp))
                    Column(modifier = GlanceModifier.defaultWeight()) {
                        Text(text = title, style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Bold, color = textColor), maxLines = 1)
                        Text(text = artist, style = TextStyle(fontSize = 13.sp, color = colors.artist), maxLines = 1)
                    }
                    Spacer(GlanceModifier.width(4.dp))
                    Image(
                        provider = ImageProvider(if (isFavorite) R.drawable.round_favorite_24 else R.drawable.rounded_favorite_24),
                        contentDescription = context.getString(R.string.cd_favorite),
                        modifier = GlanceModifier
                            .size(28.dp)
                            .clickable(actionRunCallback<PlayerControlActionCallback>(actionParametersOf(PlayerActions.key to PlayerActions.FAVORITE)))
                            .padding(2.dp),
                        colorFilter = ColorFilter.tint(textColor)
                    )
                    Spacer(GlanceModifier.width(8.dp))
                }
                Spacer(GlanceModifier.height(4.dp))
                // Progress bar commented out as in original code
                Spacer(GlanceModifier.height(10.dp))

                // Control Buttons Row
                Row(
                    modifier = GlanceModifier
                        .fillMaxWidth()
                        .fillMaxHeight(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val secondaryColor = colors.prevNextBackground
                    val onSecondaryColor = colors.prevNextIcon
                    val primaryContainerColor = colors.playPauseBackground
                    val onPrimaryContainerColor = colors.playPauseIcon
                    val buttonCornerRadius = 60.dp
                    val playButtonCornerRadius = if (isPlaying) 14.dp else 60.dp

                    PreviousButtonGlance(
                        modifier = GlanceModifier
                            .defaultWeight()
                            .fillMaxHeight(),
                        iconColor = onSecondaryColor,
                        backgroundColor = secondaryColor,
                        cornerRadius = buttonCornerRadius
                    )
                    Spacer(GlanceModifier.width(8.dp))
                    PlayPauseButtonGlance(
                        modifier = GlanceModifier
                            .defaultWeight()
                            .fillMaxHeight(),
                        isPlaying = isPlaying,
                        iconColor = onPrimaryContainerColor,
                        backgroundColor = primaryContainerColor,
                        cornerRadius = playButtonCornerRadius
                    )
                    Spacer(GlanceModifier.width(8.dp))
                    NextButtonGlance(
                        modifier = GlanceModifier
                            .defaultWeight()
                            .fillMaxHeight(),
                        iconColor = onSecondaryColor,
                        backgroundColor = secondaryColor,
                        cornerRadius = buttonCornerRadius
                    )
                }
            }
        }
    }

    @Composable
    fun ExtraLargeWidgetLayout(
        modifier: GlanceModifier, title: String, artist: String, albumArtBitmapData: ByteArray?,
        albumArtUri: String?,
        isPlaying: Boolean, backgroundColor: ColorProvider, bgCornerRadius: Dp,
        textColor: ColorProvider,
        context: Context,
        queue: List<QueueItem>,
        colors: WidgetColors
    ) {
        val playButtonCornerRadius = if (isPlaying) 16.dp else 60.dp

        // *** FIX: Apply padding to the outer Box for consistency ***
        Box(
            modifier = modifier
                .background(backgroundColor)
                .cornerRadius(bgCornerRadius)
                .padding(16.dp)
        ) {
            // *** FIX: Removed padding from the inner Column ***
            Column(modifier = GlanceModifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                // Top Row: Album Art & Info
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = GlanceModifier.fillMaxWidth()
                ) {
                    AlbumArtImageGlance(
                        bitmapData = albumArtBitmapData,
                        albumArtUri = albumArtUri,
                        size = 68.dp,
                        context = context,
                        cornerRadius = 16.dp
                    )
                    Spacer(GlanceModifier.width(16.dp))
                    Column(modifier = GlanceModifier.defaultWeight()) {
                        Text(
                            text = title,
                            style = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold, color = textColor),
                            maxLines = 2
                        )
                        Text(
                            text = artist,
                            style = TextStyle(fontSize = 16.sp, color = colors.artist),
                            maxLines = 1
                        )
                    }
                }

                Spacer(GlanceModifier.height(14.dp))

                // Bottom Row: Controls
                Row(
                    modifier = GlanceModifier
                        .defaultWeight()
                        .fillMaxWidth()
                        .height(56.dp)
                    ,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val secondaryColor = colors.prevNextBackground
                    val onSecondaryColor = colors.prevNextIcon
                    val primaryContainerColor = colors.playPauseBackground
                    val onPrimaryContainerColor = colors.playPauseIcon
                    val buttonCornerRadius = 60.dp

                    PreviousButtonGlance(
                        modifier = GlanceModifier
                            .defaultWeight()
                            .fillMaxHeight(),
                        iconColor = onSecondaryColor,
                        backgroundColor = secondaryColor,
                        iconSize = 28.dp,
                        cornerRadius = buttonCornerRadius
                    )
                    Spacer(GlanceModifier.width(10.dp))
                    PlayPauseButtonGlance(
                        modifier = GlanceModifier
                            .defaultWeight()
                            .fillMaxHeight(),
                        isPlaying = isPlaying,
                        iconColor = onPrimaryContainerColor,
                        backgroundColor = primaryContainerColor,
                        iconSize = 30.dp,
                        cornerRadius = playButtonCornerRadius
                    )
                    Spacer(GlanceModifier.width(10.dp))
                    NextButtonGlance(
                        modifier = GlanceModifier
                            .defaultWeight()
                            .fillMaxHeight(),
                        iconColor = onSecondaryColor,
                        backgroundColor = secondaryColor,
                        iconSize = 28.dp,
                        cornerRadius = buttonCornerRadius
                    )
                }

                Spacer(GlanceModifier.defaultWeight()) // Pushes the content down
                //Spacer(GlanceModifier.height(16.dp))

//                Text(
//                    text = "Next Up",
//                    style = TextStyle(
//                        fontSize = 16.sp,
//                        fontWeight = FontWeight.Bold,
//                        color = textColor
//                    ),
//                    modifier = GlanceModifier.padding(bottom = 8.dp)
//                )
                Box(
                    modifier = GlanceModifier
                        .fillMaxWidth()
                        .padding(horizontal = 30.dp)
                        .background(textColor.getColor(context).copy(alpha = 0.15f))
                        .height(2.dp)
                        .cornerRadius(60.dp)
                ) {

                }

                Spacer(GlanceModifier.height(12.dp))

                Row(
                    modifier = GlanceModifier
                        .fillMaxWidth()
                        .height(58.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val items = queue.take(EXTRA_LARGE_QUEUE_THUMBNAIL_COUNT)
                    val itemSize = 58.dp
                    val cornerRadius = 14.dp

                    for (i in 0 until EXTRA_LARGE_QUEUE_THUMBNAIL_COUNT) {
                        Box(
                            modifier = GlanceModifier.defaultWeight(),
                            contentAlignment = Alignment.Center
                        ) {
                            if (i < items.size) {
                                val queueItem = items[i]
                                AlbumArtImageGlance(
                                    modifier = GlanceModifier.clickable(
                                        actionRunCallback<PlayerControlActionCallback>(
                                            actionParametersOf(
                                                PlayerActions.key to PlayerActions.PLAY_FROM_QUEUE,
                                                PlayerActions.songIdKey to queueItem.id
                                            )
                                        )
                                    ),
                                    bitmapData = null,
                                    albumArtUri = queueItem.albumArtUri,
                                    size = itemSize,
                                    context = context,
                                    cornerRadius = cornerRadius
                                )
                            } else {
                                EndOfQueuePlaceholder(
                                    size = itemSize,
                                    cornerRadius = cornerRadius
                                )
                            }
                        }

                        if (i < 3) {
                            Spacer(GlanceModifier.width(8.dp))
                        }
                    }
                }
            }
        }
    }

    @Composable
    fun AlbumArtImageGlance(
        bitmapData: ByteArray?,
        albumArtUri: String? = null,
        size: Dp? = null,
        context: Context,
        modifier: GlanceModifier = GlanceModifier,
        cornerRadius: Dp = 16.dp
    ) {
        val TAG_AAIG = "AlbumArtImageGlance"
        Timber.tag(TAG_AAIG)
            .d("Init. bitmapData is null: ${bitmapData == null}. Requested Dp size: $size")
        if (bitmapData != null) Timber.tag(TAG_AAIG).d("bitmapData size: ${bitmapData.size} bytes")

        val sizingModifier = if (size != null) modifier.size(size) else modifier
        val widgetDpSize = LocalSize.current // Get the actual size of the composable

        val imageProvider = bitmapData?.let { data ->
            // Compute the target size first so it can be folded into the cache key — otherwise the
            // same bytes decoded for differently-sized widgets would collide on one entry and a
            // smaller cached bitmap could be served to a larger widget.
            val (targetWidthPx, targetHeightPx) = with(context.resources.displayMetrics) {
                if (size != null) {
                    // Square logic when an explicit size is provided.
                    val targetSizePx = (size.value * density).toInt().coerceAtLeast(1)
                    targetSizePx to targetSizePx
                } else {
                    // Otherwise use the actual widget size.
                    (widgetDpSize.width.value * density).toInt().coerceAtLeast(1) to
                        (widgetDpSize.height.value * density).toInt().coerceAtLeast(1)
                }
            }
            val cacheKey = AlbumArtBitmapCache.getKey(data, targetWidthPx, targetHeightPx)
            var bitmap = AlbumArtBitmapCache.getBitmap(cacheKey)

            if (bitmap != null) {
                Timber.tag(TAG_AAIG).d("Bitmap cache HIT for key: $cacheKey. Using cached bitmap.")
            } else {
                Timber.tag(TAG_AAIG).d("Bitmap cache MISS for key: $cacheKey. Decoding new bitmap.")
                try {
                    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(data, 0, data.size, options)
                    Timber.tag(TAG_AAIG)
                        .d("Initial bounds: ${options.outWidth}x${options.outHeight}")

                    val imageHeight = options.outHeight
                    val imageWidth = options.outWidth
                    var inSampleSize = 1

                    if (imageHeight > targetHeightPx || imageWidth > targetWidthPx) {
                        val halfHeight: Int = imageHeight / 2
                        val halfWidth: Int = imageWidth / 2
                        // Calculate the largest inSampleSize value that is a power of 2 and keeps both
                        // height and width larger than the requested height and width.
                        while (halfHeight / inSampleSize >= targetHeightPx && halfWidth / inSampleSize >= targetWidthPx) {
                            inSampleSize *= 2
                        }
                    }
                    Timber.tag(TAG_AAIG).d("Calculated inSampleSize: $inSampleSize")

                    options.inSampleSize = inSampleSize
                    options.inJustDecodeBounds = false
                    val sampledBitmap = BitmapFactory.decodeByteArray(data, 0, data.size, options)

                    if (sampledBitmap == null) {
                        Timber.tag(TAG_AAIG)
                            .e("BitmapFactory.decodeByteArray returned null after sampling.")
                        return@let null
                    }
                    Timber.tag(TAG_AAIG)
                        .d("Sampled bitmap size: ${sampledBitmap.width}x${sampledBitmap.height}")

                    bitmap = sampledBitmap

                    Timber.tag(TAG_AAIG)
                        .d("Final bitmap size: ${bitmap.width}x${bitmap.height}. Putting into cache with key: $cacheKey")
                    bitmap.let { AlbumArtBitmapCache.putBitmap(cacheKey, it) }

                } catch (e: Exception) {
                    Timber.tag(TAG_AAIG).e(e, "Error decoding or scaling bitmap: ${e.message}")
                    bitmap = null
                }
            }
            bitmap?.let { ImageProvider(it) }
        } ?: albumArtUri?.let { rawUri ->
            val cacheKey = "uri:$rawUri"
            var bitmap = AlbumArtBitmapCache.getBitmap(cacheKey)
            if (bitmap == null) {
                bitmap = decodeAlbumArtFromUri(
                    context = context,
                    rawUri = rawUri,
                    requestedSize = size,
                    widgetDpSize = widgetDpSize
                )
                bitmap?.let { AlbumArtBitmapCache.putBitmap(cacheKey, it) }
            }
            bitmap?.let { ImageProvider(it) }
        }

        Box(
            modifier = sizingModifier
        ) {
            if (imageProvider != null) {
                Image(
                    provider = imageProvider,
                    contentDescription = context.getString(R.string.widget_album_art),
                    modifier = GlanceModifier.fillMaxSize().cornerRadius(cornerRadius),
                    contentScale = ContentScale.Crop
                )
            } else {
                val placeholderBaseSize = size ?: minOf(widgetDpSize.width, widgetDpSize.height)
                val resolvedPlaceholderBaseSize = when {
                    placeholderBaseSize == Dp.Unspecified || placeholderBaseSize <= 0.dp -> 72.dp
                    else -> placeholderBaseSize
                }

                val placeholderSize = resolvedPlaceholderBaseSize
                    .times(0.65f)
                    .coerceIn(36.dp, 96.dp)

                Box(
                    modifier = GlanceModifier
                        .fillMaxSize()
                        .cornerRadius(cornerRadius)
                        .background(GlanceTheme.colors.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        provider = ImageProvider(R.drawable.ic_music_placeholder),
                        contentDescription = context.getString(R.string.widget_album_art_placeholder),
                        modifier = GlanceModifier.size(placeholderSize),
                        contentScale = ContentScale.Fit,
                        colorFilter = ColorFilter.tint(GlanceTheme.colors.onSurfaceVariant)
                    )
                }
            }
        }
    }

    private fun decodeAlbumArtFromUri(
        context: Context,
        rawUri: String,
        requestedSize: Dp?,
        widgetDpSize: DpSize
    ): Bitmap? {
        val (targetWidthPx, targetHeightPx) = with(context.resources.displayMetrics) {
            if (requestedSize != null) {
                val target = (requestedSize.value * density).toInt().coerceAtLeast(1)
                target to target
            } else {
                val width = (widgetDpSize.width.value * density).toInt().coerceAtLeast(1)
                val height = (widgetDpSize.height.value * density).toInt().coerceAtLeast(1)
                width to height
            }
        }
        return decodeWidgetAlbumArtBitmap(
            context = context,
            rawUri = rawUri,
            targetWidthPx = targetWidthPx,
            targetHeightPx = targetHeightPx,
        )
    }

    @Composable
    fun PlayPauseButtonGlance(
        modifier: GlanceModifier = GlanceModifier,
        isPlaying: Boolean,
        iconColor: ColorProvider = GlanceTheme.colors.onSurfaceVariant,
        backgroundColor: ColorProvider = GlanceTheme.colors.surfaceVariant,
        iconSize: Dp = 24.dp,
        cornerRadius: Dp = 0.dp
    ) {
        val context = LocalContext.current
        val params = actionParametersOf(PlayerActions.key to PlayerActions.PLAY_PAUSE)
        Box(
            modifier = modifier
                .background(backgroundColor)
                .cornerRadius(cornerRadius)
                .clickable(actionRunCallback<PlayerControlActionCallback>(params)),
            contentAlignment = Alignment.Center
        ) {
            Image(
                provider = ImageProvider(if (isPlaying) R.drawable.rounded_pause_24 else R.drawable.rounded_play_arrow_24),
                contentDescription = context.getString(if (isPlaying) R.string.cd_pause else R.string.cd_play),
                modifier = GlanceModifier.size(iconSize),
                colorFilter = ColorFilter.tint(iconColor)
            )
        }
    }

    @Composable
    fun NextButtonDGlance(
        modifier: GlanceModifier = GlanceModifier,
        width: Dp? = null,
        height: Dp? = null,
        backgroundColor: ColorProvider = GlanceTheme.colors.surfaceVariant,
        iconProvider: ImageProvider = ImageProvider(R.drawable.rounded_skip_next_24),
        iconSize: Dp = 24.dp,
        iconColor: ColorProvider = GlanceTheme.colors.onSurfaceVariant,
        topLeftCorner: Dp = 8.dp,
        topRightCorner: Dp = 8.dp,
        bottomLeftCorner: Dp = 8.dp,
        bottomRightCorner: Dp = 8.dp,
    ) {
        val context = LocalContext.current
        val bgColor = backgroundColor.getColor(context)
        val params = actionParametersOf(PlayerActions.key to PlayerActions.NEXT)

        val sizeModifier = modifier.then(
            when {
                width != null && height != null -> GlanceModifier.size(width, height)
                width != null -> GlanceModifier.width(width)
                height != null -> GlanceModifier.height(height)
                else -> GlanceModifier
            }
        )

        val backgroundBitmap = createScalableBackgroundBitmap(
            context = context,
            color = bgColor,
            topLeft = topLeftCorner,
            topRight = topRightCorner,
            bottomLeft = bottomLeftCorner,
            bottomRight = bottomRightCorner,
            width = width,
            height = height
        )

        Box(
            modifier = sizeModifier
                .background(ImageProvider(backgroundBitmap))
                .clickable(actionRunCallback<PlayerControlActionCallback>(params)),
            contentAlignment = Alignment.Center
        ) {
            Image(
                provider = iconProvider,
                contentDescription = context.getString(R.string.next_track),
                modifier = GlanceModifier.size(iconSize),
                colorFilter = ColorFilter.tint(iconColor)
            )
        }
    }

    @Composable
    fun NextButtonGlance(
        modifier: GlanceModifier = GlanceModifier,
        iconColor: ColorProvider = GlanceTheme.colors.onSurfaceVariant,
        backgroundColor: ColorProvider = GlanceTheme.colors.surfaceVariant,
        iconSize: Dp = 24.dp,
        cornerRadius: Dp = 8.dp
    ) {
        val context = LocalContext.current
        val params = actionParametersOf(PlayerActions.key to PlayerActions.NEXT)
        Box(
            modifier = modifier
                .background(backgroundColor)
                .cornerRadius(cornerRadius)
                .clickable(actionRunCallback<PlayerControlActionCallback>(params)),
            contentAlignment = Alignment.Center
        ) {
            Image(
                provider = ImageProvider(R.drawable.rounded_skip_next_24),
                contentDescription = context.getString(R.string.next_track),
                modifier = GlanceModifier.size(iconSize),
                colorFilter = ColorFilter.tint(iconColor)
            )
        }
    }

    @Composable
    fun PreviousButtonGlance(
        modifier: GlanceModifier = GlanceModifier,
        iconColor: ColorProvider = GlanceTheme.colors.onSurfaceVariant,
        backgroundColor: ColorProvider = GlanceTheme.colors.surfaceVariant,
        iconSize: Dp = 24.dp,
        cornerRadius: Dp = 8.dp
    ) {
        val context = LocalContext.current
        val params = actionParametersOf(PlayerActions.key to PlayerActions.PREVIOUS)
        Box(
            modifier = modifier
                .background(backgroundColor)
                .cornerRadius(cornerRadius)
                .clickable(actionRunCallback<PlayerControlActionCallback>(params)),
            contentAlignment = Alignment.Center
        ) {
            Image(
                provider = ImageProvider(R.drawable.rounded_skip_previous_24),
                contentDescription = context.getString(R.string.previous_track),
                modifier = GlanceModifier.size(iconSize),
                colorFilter = ColorFilter.tint(iconColor)
            )
        }
    }

    @Composable
    fun EndOfQueuePlaceholder(
        modifier: GlanceModifier = GlanceModifier,
        size: Dp,
        cornerRadius: Dp
    ) {
        Box(
            modifier = modifier
                .size(size)
                .background(GlanceTheme.colors.surfaceVariant)
                .cornerRadius(cornerRadius)
        ) {

        }
    }
}

// Helper to format duration in Glance (cannot use TimeUnit directly)
private fun formatDurationGlance(millis: Long): String {
    if (millis < 0) return "00:00"
    val totalSeconds = millis / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format(Locale.US, "%02d:%02d", minutes, seconds)
}
