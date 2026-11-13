package com.example.mediaredaktor

import android.content.ContentValues
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.app.Dialog
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.text.Editable
import android.text.SpannableString
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.content.res.ResourcesCompat
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.Presentation
import androidx.media3.effect.RgbMatrix
import androidx.media3.effect.TextOverlay
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.ui.PlayerView
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File
import java.io.FileInputStream
import java.util.Collections
import java.util.Locale

@UnstableApi
class MainActivity : AppCompatActivity() {
    private lateinit var playerView: PlayerView
    private lateinit var stickerOverlay: TextView
    private lateinit var timeline: SeekBar
    private lateinit var currentTime: TextView
    private lateinit var totalTime: TextView
    private lateinit var emptyState: View
    private lateinit var playButton: Button
    private lateinit var exportButton: Button
    private lateinit var player: ExoPlayer
    private var overlayPreviewPlayer: ExoPlayer? = null

    private val handler = Handler(Looper.getMainLooper())
    private val progressHandler = Handler(Looper.getMainLooper())
    private var previewRedrawPending = false
    private var previewEffectsApplyPending = false
    private var lastPreviewEffectsApplyMs = 0L
    private var appliedPreviewEffectsKey: String? = null
    private val previewFilterMatrix = MutableIntensityRgbMatrix()
    private var previewFilterPipelineAttached = false
    private val previewEffectsApply = Runnable {
        previewEffectsApplyPending = false
        if (!::player.isInitialized) return@Runnable
        val effectsKey = previewEffectsKey()
        if (effectsKey == appliedPreviewEffectsKey) return@Runnable
        val paused = !player.isPlaying
        previewFilterMatrix.setFilter(
            filterMatrix(selectedFilter),
            if (selectedFilter == 0) 0f else selectedFilterIntensity / 100f
        )
        if (selectedFilter != 0 && !previewFilterPipelineAttached) {
            player.setVideoEffects(listOf(previewFilterMatrix))
            previewFilterPipelineAttached = true
        }
        appliedPreviewEffectsKey = effectsKey
        lastPreviewEffectsApplyMs = SystemClock.elapsedRealtime()
        if (paused && previewFilterPipelineAttached && player.playbackState != androidx.media3.common.Player.STATE_IDLE && !previewRedrawPending) {
            previewRedrawPending = true
            handler.postDelayed(previewEffectRedraw, 55L)
        }
    }
    private val previewEffectRedraw = Runnable {
        previewRedrawPending = false
        if (::player.isInitialized && !player.isPlaying && player.playbackState != androidx.media3.common.Player.STATE_IDLE) {
            player.seekTo(player.currentPosition)
        }
    }
    private var draggingTimeline = false
    private var clipDuration = 0L
    private var trimStart = 0L
    private var trimEnd = 0L
    private var videoUri: Uri? = null
    private var selectedAudioOrdinal: Int? = null
    private var audioSelectionMade = false
    private var audioMuted = false
    private var overlayAudioUri: Uri? = null
    private var overlayAudioName: String? = null
    private var overlayAudioGain = 1f
    private var audioOptions: List<AudioOption> = emptyList()
    private var extractorAudioTracks: List<ExtractorAudioTrack> = emptyList()
    private var selectedFilter = 0
    private var selectedFilterIntensity = 75
    private val filterIntensities = mutableMapOf<Int, Int>()
    private var selectedOutputShortSide = 1080
    private var selectedOverlayText: String? = null
    private var overlayScalePercent = 100
    private var overlayOpacityPercent = 100
    private var overlayPositionX = 0f
    private var overlayPositionY = 0f
    private var overlayDragStartRawX = 0f
    private var overlayDragStartRawY = 0f
    private var overlayDragStartTranslationX = 0f
    private var overlayDragStartTranslationY = 0f
    private var overlayDragMoved = false
    private var transformer: Transformer? = null
    private var progressDialog: AlertDialog? = null
    private var progressBar: ProgressBar? = null
    private var exportStatusLabel: TextView? = null
    private var exporting = false
    private val progressHolder = ProgressHolder()
    private var outputFile: File? = null
    private var extractedAudio: File? = null
    private var stagedInput: File? = null
    private var previewProxyFile: File? = null
    private var previewFallbackAttempted = false
    private var previewDecoderRetryCount = 0
    private var previewGeneration = 0

    private data class AudioOption(
        val group: Tracks.Group,
        val groupTrackIndex: Int,
        val format: Format,
        val ordinal: Int,
        val label: String,
        val isSelected: Boolean
    )

    private data class ExtractorAudioTrack(
        val ordinal: Int,
        val mime: String?,
        val language: String?,
        val channels: Int,
        val sampleRate: Int
    )

    private data class StyledSheet(val dialog: Dialog, val panel: LinearLayout)

    private val videoPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let {
            rememberUri(it)
            loadVideo(it)
        }
    }

    private val audioPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let {
            rememberUri(it)
            overlayAudioUri = it
            overlayAudioName = displayName(it)
            audioMuted = false
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                .build()
            overlayPreviewPlayer?.apply {
                stop()
                setMediaItem(MediaItem.fromUri(it))
                volume = overlayAudioGain
                prepare()
                playWhenReady = player.isPlaying
            }
            updateAudioLaneLabel()
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            if (::player.isInitialized && player.isPlaying && !draggingTimeline) {
                if (trimEnd > 0 && player.currentPosition >= trimEnd) player.seekTo(trimStart)
                val position = player.currentPosition.toInt().coerceAtLeast(0)
                timeline.progress = position
                currentTime.text = formatTime(position)
            }
            syncOverlayPreview()
            handler.postDelayed(this, 100)
        }
    }

    private val progressUpdater = object : Runnable {
        override fun run() {
            val active = transformer
            if (active != null && active.getProgress(progressHolder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                progressBar?.progress = progressHolder.progress
            }
            if (exporting) progressHandler.postDelayed(this, 400)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(view.paddingLeft, bars.top + 4, view.paddingRight, bars.bottom + 4)
            insets
        }

        playerView = findViewById(R.id.videoPlayer)
        stickerOverlay = findViewById(R.id.stickerOverlay)
        installOverlayDragging()
        timeline = findViewById(R.id.timeline)
        currentTime = findViewById(R.id.currentTime)
        totalTime = findViewById(R.id.totalTime)
        emptyState = findViewById(R.id.emptyState)
        playButton = findViewById(R.id.playButton)
        exportButton = findViewById(R.id.exportButton)

        player = ExoPlayer.Builder(this).build()
        overlayPreviewPlayer = ExoPlayer.Builder(this).build()
        playerView.player = player
        player.addListener(object : androidx.media3.common.Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == androidx.media3.common.Player.STATE_READY) {
                    if (player.duration > 0 && player.duration != C.TIME_UNSET) clipDuration = player.duration
                    if (trimEnd == 0L || trimEnd > clipDuration) trimEnd = clipDuration
                    timeline.max = clipDuration.toInt().coerceAtLeast(1)
                    totalTime.text = formatTime(clipDuration.toInt())
                    if (player.currentTracks.groups.any { it.type == C.TRACK_TYPE_VIDEO }) {
                        emptyState.visibility = View.GONE
                    }
                    updatePlayButton()
                }
                if (playbackState == androidx.media3.common.Player.STATE_ENDED && trimEnd < clipDuration) {
                    player.seekTo(trimStart)
                    player.play()
                }
                updatePlayButton()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                updatePlayButton()
                if (!isPlaying) overlayPreviewPlayer?.pause()
            }

            override fun onTracksChanged(tracks: Tracks) {
                refreshAudioOptions(tracks)
                val hasVideo = tracks.groups.any { it.type == C.TRACK_TYPE_VIDEO && it.isSupported }
                val hasAudio = tracks.groups.any { it.type == C.TRACK_TYPE_AUDIO && it.isSupported }
                if (videoUri != null && !hasVideo && hasAudio) {
                    showPreviewMessage("В этом файле нет видео", "Найдены только аудиодорожки")
                }
            }

            override fun onRenderedFirstFrame() {
                emptyState.visibility = View.GONE
                updateOverlayPreview()
            }

            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    emptyState.visibility = View.GONE
                    updateOverlayPreview()
                }
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                val source = videoUri
                if (source != null && previewProxyFile != null && previewDecoderRetryCount < 1) {
                    previewDecoderRetryCount++
                    previewProxyFile?.delete()
                    previewProxyFile = null
                    showPreviewMessage("Готовим совместимый MP4", "Повторная подготовка видео")
                    createCompatiblePreview(source, previewDecoderRetryCount)
                } else if (source != null && !previewFallbackAttempted) {
                    previewFallbackAttempted = true
                    showPreviewMessage("Готовим видео для устройства", "Исходный формат не поддержан декодером")
                    createCompatiblePreview(source)
                } else {
                    showPreviewMessage("Не удалось показать видео", error.message ?: error.errorCodeName)
                }
            }
        })

        findViewById<TextView>(R.id.closeButton).setOnClickListener { finish() }
        findViewById<Button>(R.id.addVideoButton).setOnClickListener { videoPicker.launch(arrayOf("video/*")) }
        playButton.setOnClickListener {
            if (videoUri == null) videoPicker.launch(arrayOf("video/*"))
            else if (player.isPlaying) player.pause() else {
                if (player.currentPosition >= trimEnd) player.seekTo(trimStart)
                player.play()
            }
        }
        timeline.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(seekBar: SeekBar) { draggingTimeline = true }
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) currentTime.text = formatTime(progress)
            }
            override fun onStopTrackingTouch(seekBar: SeekBar) {
                player.seekTo(seekBar.progress.toLong().coerceIn(trimStart, trimEnd.coerceAtLeast(trimStart)))
                draggingTimeline = false
            }
        })

        findViewById<Button>(R.id.trimTool).setOnClickListener { showTrimDialog() }
        findViewById<Button>(R.id.audioTool).setOnClickListener { showAudioActions() }
        findViewById<View>(R.id.audioLaneContent).setOnClickListener { showAudioActions() }
        findViewById<Button>(R.id.filterTool).setOnClickListener { showFilters() }
        findViewById<Button>(R.id.stickerTool).setOnClickListener { showStickers() }
        exportButton.setOnClickListener { showExportSettings() }
        handler.post(ticker)
    }

    private fun rememberUri(uri: Uri) {
        runCatching {
            contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun loadVideo(uri: Uri) {
        previewGeneration++
        handler.removeCallbacks(previewEffectsApply)
        handler.removeCallbacks(previewEffectRedraw)
        previewEffectsApplyPending = false
        previewRedrawPending = false
        player.stop()
        previewProxyFile?.delete()
        previewProxyFile = null
        previewFallbackAttempted = false
        previewDecoderRetryCount = 0
        videoUri = uri
        selectedAudioOrdinal = null
        audioSelectionMade = false
        audioMuted = false
        audioOptions = emptyList()
        overlayAudioUri = null
        overlayAudioName = null
        overlayAudioGain = 1f
        overlayPreviewPlayer?.stop()
        overlayPreviewPlayer?.clearMediaItems()
        extractorAudioTracks = readAudioTracks(uri)
        trimStart = 0L
        trimEnd = 0L
        clipDuration = readDuration(uri)
        selectedFilter = 0
        selectedFilterIntensity = 0
        filterIntensities.clear()
        previewFilterMatrix.setFilter(null, 0f)
        previewFilterPipelineAttached = false
        selectedOverlayText = null
        overlayScalePercent = 100
        overlayOpacityPercent = 100
        overlayPositionX = 0f
        overlayPositionY = 0f
        updateOverlayPreview()
        player.setVideoEffects(emptyList())
        appliedPreviewEffectsKey = previewEffectsKey()
        lastPreviewEffectsApplyMs = SystemClock.elapsedRealtime()
        updateAudioLaneLabel()
        findViewById<TextView>(R.id.clipLabel).text = displayName(uri)
        showPreviewMessage("Загружаем видео", "Подготавливаем предпросмотр")
        currentTime.text = formatTime(0)
        totalTime.text = formatTime(clipDuration.toInt())
        timeline.progress = 0
        timeline.max = clipDuration.toInt().coerceAtLeast(1)
        player.setMediaItem(MediaItem.fromUri(uri))
        player.prepare()
        player.playWhenReady = true
    }

    private fun createCompatiblePreview(source: Uri, startAttempt: Int = 0) {
        val generation = previewGeneration
        Thread {
            try {
                val input = File(cacheDir, "preview-source-${System.currentTimeMillis()}.mp4")
                contentResolver.openInputStream(source)?.use { stream -> input.outputStream().use { output -> stream.copyTo(output) } }
                    ?: error("Не удалось прочитать выбранный файл")
                val proxy = File(cacheDir, "preview-proxy-${System.currentTimeMillis()}.mp4")
                runPreviewTranscodeAttempt(input, proxy, generation, startAttempt)
            } catch (error: Exception) {
                runOnUiThread {
                    showPreviewMessage("Не удалось подготовить видео", error.localizedMessage ?: "Ошибка чтения файла")
                }
            }
        }.start()
    }

    private fun runPreviewTranscodeAttempt(input: File, proxy: File, generation: Int, attempt: Int) {
        val prefix = "-y -i ${quoteFfmpeg(input.absolutePath)} -map 0:v:0 -map 0:a? -map_metadata 0 -map_chapters 0 -vf scale=1280:720:force_original_aspect_ratio=decrease:force_divisible_by=2,fps=30"
        val encoders = listOf(
            "-c:v libopenh264 -profile:v 66 -level 31 -b:v 2500k",
            "-c:v mpeg4 -q:v 4"
        )
        if (attempt >= encoders.size) {
            input.delete()
            proxy.delete()
            runOnUiThread {
                if (generation == previewGeneration) {
                    showPreviewMessage("Не удалось подготовить видео", "Не удалось создать совместимый MP4")
                }
            }
            return
        }

        proxy.delete()
        val command = "$prefix ${encoders[attempt]} -threads 2 -c:a aac -b:a 128k -movflags +faststart ${quoteFfmpeg(proxy.absolutePath)}"
        FFmpegKit.executeAsync(command, { session ->
            if (ReturnCode.isSuccess(session.returnCode) && proxy.length() > 0) {
                input.delete()
                openCompatiblePreview(proxy, generation)
            } else {
                runPreviewTranscodeAttempt(input, proxy, generation, attempt + 1)
            }
        }, null, { statistics ->
            val convertedMs = statistics.time.toInt().coerceAtLeast(0)
            runOnUiThread {
                if (generation == previewGeneration) {
                    val total = clipDuration.toInt().coerceAtLeast(0)
                    val progressText = if (total > 0) "MP4: ${formatTime(convertedMs)} / ${formatTime(total)}" else "MP4: ${formatTime(convertedMs)}"
                    showPreviewMessage("Конвертируем в MP4", progressText)
                }
            }
        })
    }

    private fun openCompatiblePreview(proxy: File, generation: Int) {
        runOnUiThread {
            if (generation != previewGeneration) {
                proxy.delete()
                return@runOnUiThread
            }
            previewProxyFile?.delete()
            previewProxyFile = proxy
            handler.removeCallbacks(previewEffectsApply)
            handler.removeCallbacks(previewEffectRedraw)
            previewEffectsApplyPending = false
            previewRedrawPending = false
            previewFilterMatrix.setFilter(
                filterMatrix(selectedFilter),
                if (selectedFilter == 0) 0f else selectedFilterIntensity / 100f
            )
            player.setVideoEffects(if (selectedFilter == 0) emptyList() else listOf(previewFilterMatrix))
            previewFilterPipelineAttached = selectedFilter != 0
            appliedPreviewEffectsKey = previewEffectsKey()
            lastPreviewEffectsApplyMs = SystemClock.elapsedRealtime()
            player.setMediaItem(MediaItem.fromUri(Uri.fromFile(proxy)))
            player.prepare()
            player.playWhenReady = true
        }
    }

    private fun refreshAudioOptions(tracks: Tracks) {
        val audioGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO && it.isSupported }
        val options = mutableListOf<AudioOption>()
        audioGroups.forEach { group ->
            for (trackIndex in 0 until group.length) {
                if (!group.isTrackSupported(trackIndex)) continue
                val format = group.getTrackFormat(trackIndex)
                val label = buildAudioLabel(format, options.size)
                val extractorTrack = findExtractorTrack(format, options.map { it.ordinal }.toSet())
                val ordinal = extractorTrack?.ordinal ?: options.size
                options.add(AudioOption(group, trackIndex, format, ordinal, label, group.isTrackSelected(trackIndex)))
            }
        }
        audioOptions = options
        if (!audioSelectionMade) {
            selectedAudioOrdinal = options.firstOrNull { it.isSelected }?.ordinal
        }
        updateAudioLaneLabel()
        if (selectedAudioOrdinal != null && options.none { it.ordinal == selectedAudioOrdinal }) {
            selectedAudioOrdinal = null
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                .build()
        }
    }

    private fun buildAudioLabel(format: Format, index: Int): String {
        val language = format.language?.takeIf { it.isNotBlank() && it != "und" }
            ?.let { Locale.forLanguageTag(it).getDisplayName(Locale.getDefault()) }
        val channels = when (format.channelCount) {
            1 -> "моно"
            2 -> "стерео"
            in 3..Int.MAX_VALUE -> "${format.channelCount} канала"
            else -> null
        }
        return listOfNotNull("Дорожка ${index + 1}", language, channels).joinToString(" · ")
    }

    private fun readAudioTracks(uri: Uri): List<ExtractorAudioTrack> {
        val extractor = MediaExtractor()
        val result = mutableListOf<ExtractorAudioTrack>()
        try {
            extractor.setDataSource(this, uri, null)
            var ordinal = 0
            for (trackIndex in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(trackIndex)
                val mime = format.getString(MediaFormat.KEY_MIME)
                if (mime?.startsWith("audio/") == true) {
                    result.add(
                        ExtractorAudioTrack(
                            ordinal = ordinal++,
                            mime = mime,
                            language = runCatching { format.getString(MediaFormat.KEY_LANGUAGE) }.getOrNull(),
                            channels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 0,
                            sampleRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) format.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 0
                        )
                    )
                }
            }
        } catch (_: Exception) {
            // ExoPlayer still exposes selectable tracks if a container has limited extractor support.
        } finally {
            extractor.release()
        }
        return result
    }

    private fun readDuration(uri: Uri): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(this, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (_: Exception) {
            0L
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun showPreviewMessage(title: String, subtitle: String?) {
        findViewById<TextView>(R.id.previewTitle).text = title
        findViewById<TextView>(R.id.previewSubtitle).text = subtitle.orEmpty()
        emptyState.visibility = View.VISIBLE
    }

    private fun findExtractorTrack(format: Format, used: Set<Int>): ExtractorAudioTrack? {
        val mime = format.sampleMimeType
        val language = format.language?.takeUnless { it == "und" }
        val candidates = extractorAudioTracks.filter { it.ordinal !in used }
        return candidates.firstOrNull {
            mime != null && it.mime == mime && language != null && it.language.equals(language, ignoreCase = true)
        } ?: candidates.firstOrNull {
            mime != null && it.mime == mime && (format.channelCount == C.LENGTH_UNSET || it.channels == 0 || it.channels == format.channelCount)
        } ?: candidates.firstOrNull()
    }

    private fun createStyledSheet(title: String, subtitle: String): StyledSheet {
        val dialog = Dialog(this)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(16))
            background = ResourcesCompat.getDrawable(resources, R.drawable.bg_dialog, theme)
        }
        panel.addView(TextView(this).apply {
            text = title
            textSize = 23f
            typeface = ResourcesCompat.getFont(this@MainActivity, R.font.cormorant_semibold)
            setTextColor(Color.rgb(245, 247, 250))
        })
        panel.addView(TextView(this).apply {
            text = subtitle
            textSize = 12f
            typeface = ResourcesCompat.getFont(this@MainActivity, R.font.montserrat_regular)
            setTextColor(Color.rgb(157, 169, 187))
            setLineSpacing(dp(2).toFloat(), 1f)
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(6)
            bottomMargin = dp(16)
        })
        val divider = View(this).apply { setBackgroundColor(Color.rgb(43, 57, 76)) }
        panel.addView(divider, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply { bottomMargin = dp(14) })
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            clipToPadding = false
            addView(panel)
        }
        dialog.setContentView(scroll)
        dialog.setCanceledOnTouchOutside(true)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        return StyledSheet(dialog, panel)
    }

    private fun showStyledSheet(sheet: StyledSheet) {
        sheet.dialog.show()
        sheet.dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout((resources.displayMetrics.widthPixels - dp(36)).coerceAtMost(dp(460)), android.view.WindowManager.LayoutParams.WRAP_CONTENT)
            setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
            addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            attributes = attributes.apply { dimAmount = 0.62f }
        }
    }

    private fun addSheetAction(
        panel: LinearLayout,
        title: String,
        detail: String,
        icon: String = "›",
        selected: Boolean = false,
        selectionMode: Boolean = false,
        onClick: () -> Unit
    ) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            isClickable = true
            isFocusable = true
            val fill = if (selected) Color.rgb(26, 45, 72) else Color.rgb(23, 29, 39)
            background = GradientDrawable().apply {
                setColor(fill)
                setCornerRadius(dp(14).toFloat())
                setStroke(dp(1), if (selected) Color.rgb(67, 137, 255) else Color.rgb(43, 52, 66))
            }
            setOnClickListener { onClick() }
        }
        val glyph = TextView(this).apply {
            text = icon
            textSize = if (icon.length > 2) 14f else 17f
            gravity = android.view.Gravity.CENTER
            typeface = ResourcesCompat.getFont(this@MainActivity, R.font.montserrat_semibold)
            setTextColor(Color.rgb(142, 194, 255))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.rgb(31, 51, 79))
            }
        }
        row.addView(glyph, LinearLayout.LayoutParams(dp(34), dp(34)))
        val textGroup = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        textGroup.addView(TextView(this).apply {
            text = title
            textSize = 13f
            typeface = ResourcesCompat.getFont(this@MainActivity, R.font.montserrat_semibold)
            setTextColor(Color.rgb(239, 243, 249))
        })
        textGroup.addView(TextView(this).apply {
            text = detail
            textSize = 11f
            typeface = ResourcesCompat.getFont(this@MainActivity, R.font.montserrat_regular)
            setTextColor(Color.rgb(151, 163, 181))
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(3) })
        row.addView(textGroup, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(12); marginEnd = dp(8) })
        row.addView(TextView(this).apply {
            text = if (selected) "✓" else if (selectionMode) "○" else "›"
            textSize = 16f
            typeface = ResourcesCompat.getFont(this@MainActivity, R.font.montserrat_medium)
                setTextColor(if (selected) Color.rgb(116, 179, 255) else Color.rgb(129, 145, 166))
        })
        panel.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) })
    }

    private fun sheetButton(label: String, primary: Boolean = false, onClick: () -> Unit): TextView = TextView(this).apply {
        text = label
        gravity = android.view.Gravity.CENTER
        textSize = 12f
        typeface = ResourcesCompat.getFont(this@MainActivity, R.font.montserrat_semibold)
        setTextColor(if (primary) Color.WHITE else Color.rgb(204, 214, 228))
        setPadding(dp(16), 0, dp(16), 0)
        isClickable = true
        isFocusable = true
        background = GradientDrawable().apply {
            setColor(if (primary) Color.rgb(54, 119, 235) else Color.rgb(28, 35, 46))
            setCornerRadius(dp(14).toFloat())
        }
        setOnClickListener { onClick() }
    }

    private fun addSheetFooter(panel: LinearLayout, primary: String, onPrimary: () -> Unit, secondary: String = "Закрыть", onSecondary: () -> Unit = onPrimary) {
        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.END or android.view.Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        footer.addView(sheetButton(secondary, onClick = onSecondary), LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(8) })
        footer.addView(sheetButton(primary, primary = true, onClick = onPrimary), LinearLayout.LayoutParams(0, dp(44), 1f))
        panel.addView(footer)
    }

    private fun showEmbeddedAudioTracks() {
        if (videoUri == null) {
            showMessage("Сначала откройте видео")
            return
        }
        val sheet = createStyledSheet("Звук из видео", "Выберите встроенную дорожку. Она уже содержится в исходном ролике.")
        if (audioOptions.isEmpty()) {
            sheet.panel.addView(TextView(this).apply {
                text = "Поддерживаемых аудиодорожек не найдено. Текущий звук останется без изменений."
                textSize = 12f
                typeface = ResourcesCompat.getFont(this@MainActivity, R.font.montserrat_regular)
                setTextColor(Color.rgb(179, 189, 203))
            })
        } else {
            audioOptions.forEach { option ->
                val isSelected = !audioMuted && selectedAudioOrdinal == option.ordinal
                addSheetAction(sheet.panel, option.label, "Оригинальная дорожка клипа", "♫", isSelected, selectionMode = true) {
                    selectedAudioOrdinal = option.ordinal
                    audioSelectionMade = true
                    audioMuted = false
                    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                        .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                        .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                        .setOverrideForType(TrackSelectionOverride(option.group.mediaTrackGroup, option.groupTrackIndex))
                        .build()
                    updateAudioLaneLabel()
                    sheet.dialog.dismiss()
                }
            }
        }
        addSheetAction(sheet.panel, "Без звука", "Отключить только исходную дорожку", "×", audioMuted, selectionMode = true) {
            selectedAudioOrdinal = null
            audioSelectionMade = true
            audioMuted = true
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                .build()
            updateAudioLaneLabel()
            sheet.dialog.dismiss()
        }
        addSheetFooter(sheet.panel, "Готово", { sheet.dialog.dismiss() })
        showStyledSheet(sheet)
    }

    private fun showAudioActions() {
        if (videoUri == null) {
            showMessage("Сначала откройте видео")
            return
        }
        val sheet = createStyledSheet("Аудиодорожки", "Настройте звук клипа или добавьте отдельную дорожку поверх него.")
        val currentTrack = if (audioMuted) "Сейчас: звук выключен" else "Сейчас: ${audioOptions.firstOrNull { it.ordinal == selectedAudioOrdinal }?.label ?: "исходный звук"}"
        addSheetAction(sheet.panel, "Звук исходного видео", currentTrack, "♫") {
            sheet.dialog.dismiss()
            showEmbeddedAudioTracks()
        }
        addSheetAction(sheet.panel, "Добавить аудио поверх", overlayAudioName ?: "Выбрать музыку или запись", "+") {
            sheet.dialog.dismiss()
            audioPicker.launch(arrayOf("audio/*"))
        }
        if (overlayAudioUri != null) {
            addSheetAction(sheet.panel, "Громкость наложения", "${(overlayAudioGain * 100).toInt()}% · можно оставить оба звука", "◖") {
                sheet.dialog.dismiss()
                showOverlayVolumeDialog()
            }
            addSheetAction(sheet.panel, "Убрать наложение", "Удалить добавленный аудиофайл", "−") {
                overlayAudioUri = null
                overlayAudioName = null
                overlayPreviewPlayer?.stop()
                overlayPreviewPlayer?.clearMediaItems()
                updateAudioLaneLabel()
                sheet.dialog.dismiss()
            }
        }
        addSheetFooter(sheet.panel, "Готово", { sheet.dialog.dismiss() })
        showStyledSheet(sheet)
    }

    private fun showOverlayVolumeDialog() {
        val sheet = createStyledSheet("Громкость наложения", "Регулируйте музыку относительно звука исходного видео.")
        val valueLabel = TextView(this).apply {
            text = "${(overlayAudioGain * 100).toInt()}%"
            textSize = 22f
            typeface = ResourcesCompat.getFont(this@MainActivity, R.font.montserrat_semibold)
            setTextColor(Color.WHITE)
        }
        val volume = SeekBar(this).apply {
            max = 200
            progress = (overlayAudioGain * 100).toInt().coerceIn(0, max)
            progressTintList = ColorStateList.valueOf(Color.rgb(73, 145, 255))
            thumbTintList = ColorStateList.valueOf(Color.rgb(220, 235, 255))
        }
        volume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                valueLabel.text = "$progress%"
            }
            override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
        })
        sheet.panel.addView(valueLabel)
        sheet.panel.addView(volume, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(42)).apply { topMargin = dp(10); bottomMargin = dp(10) })
        val hint = TextView(this).apply {
            text = "0% — без наложения     100% — обычный уровень     200% — усилить"
            textSize = 10f
            typeface = ResourcesCompat.getFont(this@MainActivity, R.font.montserrat_regular)
            setTextColor(Color.rgb(151, 163, 181))
        }
        sheet.panel.addView(hint)
        addSheetFooter(sheet.panel, "Применить", {
            overlayAudioGain = volume.progress / 100f
            overlayPreviewPlayer?.volume = overlayAudioGain
            updateAudioLaneLabel()
            sheet.dialog.dismiss()
        }, "Отмена", { sheet.dialog.dismiss() })
        showStyledSheet(sheet)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun updateAudioLaneLabel() {
        if (!::player.isInitialized) return
        val label = when {
            overlayAudioName != null -> "Сверху: ${overlayAudioName} · ${(overlayAudioGain * 100).toInt()}%"
            audioMuted -> "Без звука"
            selectedAudioOrdinal != null -> audioOptions.firstOrNull { it.ordinal == selectedAudioOrdinal }?.label ?: "Оригинальная дорожка"
            else -> audioOptions.firstOrNull { it.isSelected }?.label ?: "Оригинальный звук"
        }
        findViewById<TextView>(R.id.audioTrackLabel).text = label
    }

    private fun syncOverlayPreview() {
        val overlay = overlayPreviewPlayer ?: return
        if (overlayAudioUri == null || !::player.isInitialized) return
        val sourcePosition = player.currentPosition
        if (!player.isPlaying || sourcePosition < trimStart) {
            if (overlay.isPlaying) overlay.pause()
            return
        }
        val expected = (sourcePosition - trimStart).coerceAtLeast(0L)
        if (overlay.playbackState == androidx.media3.common.Player.STATE_ENDED) overlay.seekTo(0)
        if (overlay.playbackState == androidx.media3.common.Player.STATE_IDLE) return
        if (kotlin.math.abs(overlay.currentPosition - expected) > 750L) overlay.seekTo(expected)
        if (!overlay.isPlaying) overlay.play()
    }

    private fun showTrimDialog() {
        if (clipDuration <= 0L) {
            showMessage("Сначала откройте видео")
            return
        }
        val sheet = createStyledSheet("Обрезка клипа", "Передвиньте курсор, отметьте две границы и сохраните только нужный фрагмент.")
        val cursorLabel = TextView(this).apply {
            textSize = 13f
            typeface = ResourcesCompat.getFont(this@MainActivity, R.font.montserrat_semibold)
            setTextColor(Color.rgb(225, 234, 246))
        }
        val cursor = SeekBar(this).apply {
            max = clipDuration.toInt().coerceAtLeast(1)
            progress = player.currentPosition.toInt().coerceIn(0, max)
            progressTintList = ColorStateList.valueOf(Color.rgb(73, 145, 255))
            thumbTintList = ColorStateList.valueOf(Color.rgb(220, 235, 255))
        }
        fun refreshCursorLabel(position: Int = cursor.progress) {
            cursorLabel.text = "КУРСОР     ${formatTime(position)}"
        }
        refreshCursorLabel()
        cursor.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                refreshCursorLabel(progress)
            }
            override fun onStopTrackingTouch(seekBar: SeekBar) {
                player.seekTo(seekBar.progress.toLong())
                currentTime.text = formatTime(seekBar.progress)
            }
        })
        sheet.panel.addView(cursorLabel)
        sheet.panel.addView(cursor, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(42)).apply { topMargin = dp(4); bottomMargin = dp(12) })
        val markers = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun markerCard(title: String, getTime: () -> Long, setMarker: () -> Unit): LinearLayout {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(12), dp(12), dp(12))
                background = GradientDrawable().apply { setColor(Color.rgb(22, 30, 42)); setCornerRadius(dp(14).toFloat()); setStroke(dp(1), Color.rgb(42, 55, 74)) }
            }
            card.addView(TextView(this).apply {
                text = title.uppercase(Locale.getDefault())
                textSize = 9f
                letterSpacing = 0.06f
                typeface = ResourcesCompat.getFont(this@MainActivity, R.font.montserrat_semibold)
                setTextColor(Color.rgb(143, 163, 190))
            })
            val value = TextView(this).apply {
                text = formatTime(getTime().toInt())
                textSize = 14f
                typeface = ResourcesCompat.getFont(this@MainActivity, R.font.montserrat_semibold)
                setTextColor(Color.rgb(231, 239, 249))
            }
            card.addView(value, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4); bottomMargin = dp(10) })
            card.addView(sheetButton("Задать здесь", primary = false) {
                setMarker()
                value.text = formatTime(getTime().toInt())
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(38)))
            return card
        }
        val startCard = markerCard("Начало", { trimStart }) {
            trimStart = cursor.progress.toLong().coerceAtMost((trimEnd - 250).coerceAtLeast(0))
        }
        val endCard = markerCard("Конец", { trimEnd }) {
            trimEnd = cursor.progress.toLong().coerceAtLeast(trimStart + 250).coerceAtMost(clipDuration)
        }
        markers.addView(startCard, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) })
        markers.addView(endCard, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(6) })
        sheet.panel.addView(markers)
        val reset = TextView(this).apply {
            text = "Сбросить границы"
            textSize = 11f
            typeface = ResourcesCompat.getFont(this@MainActivity, R.font.montserrat_medium)
            setTextColor(Color.rgb(149, 177, 212))
            isClickable = true
            setPadding(0, dp(14), 0, dp(4))
            setOnClickListener {
                trimStart = 0L
                trimEnd = clipDuration
                cursor.progress = 0
                player.seekTo(0)
                currentTime.text = formatTime(0)
                (startCard.getChildAt(1) as TextView).text = formatTime(0)
                (endCard.getChildAt(1) as TextView).text = formatTime(clipDuration.toInt())
            }
        }
        sheet.panel.addView(reset)
        addSheetFooter(sheet.panel, "Сохранить", {
            player.seekTo(trimStart)
            sheet.dialog.dismiss()
        }, "Отмена", { sheet.dialog.dismiss() })
        showStyledSheet(sheet)
    }

    private fun showFilters() {
        if (videoUri == null) {
            showMessage("Сначала откройте видео")
            return
        }
        val names = listOf("Оригинал", "Ч/Б", "Негатив", "Тёплый", "Холодный", "Сепия", "Плёнка", "Яркий")
        val descriptions = listOf("Без изменений", "Мягкий монохром", "Инверсия цвета", "Тёплый солнечный оттенок", "Прохладные синие тона", "Классический сепийный тон", "Приглушённый кинематографичный цвет", "Усиленная насыщенность")
        val sheet = createStyledSheet("Цвет и настроение", "Выберите стиль. Интенсивность можно менять, глядя на видео за окном.")
        lateinit var refreshFilterSheet: () -> Unit
        val chipScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val chipViews = names.mapIndexed { index, name ->
            TextView(this).apply {
                text = name
                gravity = android.view.Gravity.CENTER
                textSize = 11f
                typeface = ResourcesCompat.getFont(this@MainActivity, R.font.montserrat_semibold)
                setPadding(dp(14), 0, dp(14), 0)
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    selectedFilter = index
                    selectedFilterIntensity = filterIntensities.getOrPut(index) { if (index == 0) 0 else 70 }
                    refreshFilterSheet()
                }
            }
        }
        chipViews.forEachIndexed { index, chip ->
            chips.addView(chip, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(38)).apply { marginEnd = dp(7) })
        }
        chipScroll.addView(chips)
        sheet.panel.addView(chipScroll)
        val detail = TextView(this).apply {
            textSize = 12f
            typeface = ResourcesCompat.getFont(this@MainActivity, R.font.montserrat_regular)
            setTextColor(Color.rgb(164, 177, 196))
        }
        sheet.panel.addView(detail, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14) })
        val intensityTitle = TextView(this).apply {
            textSize = 12f
            typeface = ResourcesCompat.getFont(this@MainActivity, R.font.montserrat_semibold)
            setTextColor(Color.rgb(232, 239, 249))
        }
        sheet.panel.addView(intensityTitle, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(18) })
        val intensity = SeekBar(this).apply {
            max = 100
            progressTintList = ColorStateList.valueOf(Color.rgb(73, 145, 255))
            thumbTintList = ColorStateList.valueOf(Color.rgb(220, 235, 255))
        }
        sheet.panel.addView(intensity, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(42)))
        var updating = false
        fun refresh() {
            updating = true
            chipViews.forEachIndexed { index, chip ->
                val active = index == selectedFilter
                chip.background = GradientDrawable().apply {
                    setColor(if (active) Color.rgb(54, 119, 235) else Color.rgb(24, 31, 42))
                    setCornerRadius(dp(20).toFloat())
                    setStroke(dp(1), if (active) Color.rgb(104, 167, 255) else Color.rgb(44, 55, 73))
                }
                chip.setTextColor(if (active) Color.WHITE else Color.rgb(177, 189, 205))
            }
            detail.text = descriptions[selectedFilter]
            val amount = if (selectedFilter == 0) 0 else selectedFilterIntensity
            intensity.progress = amount
            intensity.isEnabled = selectedFilter != 0
            intensity.alpha = if (selectedFilter == 0) 0.45f else 1f
            intensityTitle.text = if (selectedFilter == 0) "Интенсивность · не применяется" else "Интенсивность · $amount%"
            updating = false
            applyPreviewEffects()
        }
        intensity.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser && !updating) {
                    selectedFilterIntensity = progress
                    filterIntensities[selectedFilter] = progress
                    intensityTitle.text = "Интенсивность · $progress%"
                    applyPreviewEffects()
                }
            }
            override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
        })
        refreshFilterSheet = { refresh() }
        refresh()
        addSheetFooter(sheet.panel, "Готово", { sheet.dialog.dismiss() }, "Сбросить", {
            selectedFilter = 0
            selectedFilterIntensity = 0
            filterIntensities.clear()
            applyPreviewEffects()
            sheet.dialog.dismiss()
        })
        showStyledSheet(sheet)
    }

    private fun showStickers() {
        if (videoUri == null) {
            showMessage("Сначала откройте видео")
            return
        }
        val sheet = createStyledSheet("Текст на видео", "Добавьте подпись или эмодзи. После нажатия «Готово» зажмите надпись в кадре и перетащите её.")
        sheet.panel.addView(TextView(this).apply {
            text = "НАДПИСЬ"
            textSize = 10f
            letterSpacing = 0.08f
            typeface = ResourcesCompat.getFont(this@MainActivity, R.font.montserrat_semibold)
            setTextColor(Color.rgb(151, 163, 181))
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(6) })
        val textInput = EditText(this).apply {
            setText(selectedOverlayText.orEmpty())
            hint = "Напишите текст для видео"
            setHintTextColor(Color.rgb(114, 126, 144))
            setTextColor(Color.WHITE)
            textSize = 14f
            typeface = ResourcesCompat.getFont(this@MainActivity, R.font.montserrat_medium)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
                android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 1
            maxLines = 3
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply {
                setColor(Color.rgb(22, 29, 40))
                setCornerRadius(dp(12).toFloat())
                setStroke(dp(1), Color.rgb(43, 57, 76))
            }
        }
        sheet.panel.addView(textInput, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(78)).apply { bottomMargin = dp(12) })
        textInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                selectedOverlayText = s?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                updateOverlayPreview()
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })

        sheet.panel.addView(TextView(this).apply {
            text = "БЫСТРО ДОБАВИТЬ"
            textSize = 10f
            letterSpacing = 0.08f
            typeface = ResourcesCompat.getFont(this@MainActivity, R.font.montserrat_semibold)
            setTextColor(Color.rgb(151, 163, 181))
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(7) })
        val stickers = listOf("✨" to "Блеск", "❤️" to "Сердце", "🔥" to "Огонь", "😊" to "Улыбка", "🎉" to "Праздник", "⭐" to "Звезда")
        stickers.chunked(3).forEach { line ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            line.forEach { (emoji, label) ->
                val tile = TextView(this).apply {
                    text = "$emoji\n$label"
                    gravity = android.view.Gravity.CENTER
                    textSize = 12f
                    typeface = ResourcesCompat.getFont(this@MainActivity, R.font.montserrat_medium)
                    setTextColor(Color.rgb(224, 233, 245))
                    background = GradientDrawable().apply { setColor(Color.rgb(23, 30, 41)); setCornerRadius(dp(14).toFloat()); setStroke(dp(1), Color.rgb(42, 53, 70)) }
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        textInput.setText(emoji)
                        textInput.setSelection(textInput.text.length)
                    }
                }
                row.addView(tile, LinearLayout.LayoutParams(0, dp(76), 1f).apply { marginEnd = dp(7) })
            }
            sheet.panel.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) })
        }

        val sizeTitle = TextView(this).apply {
            textSize = 12f
            typeface = ResourcesCompat.getFont(this@MainActivity, R.font.montserrat_semibold)
            setTextColor(Color.rgb(232, 239, 249))
        }
        sheet.panel.addView(sizeTitle, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(5) })
        val sizeSlider = SeekBar(this).apply {
            max = 150
            progress = (overlayScalePercent - 50).coerceIn(0, max)
            progressTintList = ColorStateList.valueOf(Color.rgb(73, 145, 255))
            thumbTintList = ColorStateList.valueOf(Color.rgb(220, 235, 255))
        }
        sheet.panel.addView(sizeSlider, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(38)))
        sizeTitle.text = "Размер · ${overlayScalePercent}%"
        sizeSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                overlayScalePercent = progress + 50
                sizeTitle.text = "Размер · ${overlayScalePercent}%"
                updateOverlayPreview()
            }
            override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
        })

        val opacityTitle = TextView(this).apply {
            textSize = 12f
            typeface = ResourcesCompat.getFont(this@MainActivity, R.font.montserrat_semibold)
            setTextColor(Color.rgb(232, 239, 249))
        }
        sheet.panel.addView(opacityTitle, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
        val opacitySlider = SeekBar(this).apply {
            max = 100
            progress = overlayOpacityPercent.coerceIn(0, max)
            progressTintList = ColorStateList.valueOf(Color.rgb(73, 145, 255))
            thumbTintList = ColorStateList.valueOf(Color.rgb(220, 235, 255))
        }
        sheet.panel.addView(opacitySlider, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(38)))
        opacityTitle.text = "Видимость · ${overlayOpacityPercent}%"
        opacitySlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                overlayOpacityPercent = progress
                opacityTitle.text = "Видимость · ${overlayOpacityPercent}%"
                updateOverlayPreview()
            }
            override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
        })

        sheet.panel.addView(TextView(this).apply {
            text = "0% — невидимо · 100% — полностью видно"
            textSize = 10f
            typeface = ResourcesCompat.getFont(this@MainActivity, R.font.montserrat_regular)
            setTextColor(Color.rgb(142, 154, 173))
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(1) })
        addSheetFooter(sheet.panel, "Готово", {
            sheet.dialog.dismiss()
            if (!selectedOverlayText.isNullOrBlank()) showMessage("Зажмите текст в кадре и перетащите")
        }, "Убрать", {
            selectedOverlayText = null
            overlayPositionX = 0f
            overlayPositionY = 0f
            textInput.setText("")
            updateOverlayPreview()
            sheet.dialog.dismiss()
        })
        showStyledSheet(sheet)
    }

    private fun updateOverlayPreview() {
        val text = selectedOverlayText.orEmpty()
        stickerOverlay.text = text
        stickerOverlay.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 36f * overlayScalePercent / 100f)
        stickerOverlay.alpha = overlayOpacityPercent / 100f
        stickerOverlay.typeface = ResourcesCompat.getFont(this, R.font.montserrat_semibold)
        stickerOverlay.gravity = android.view.Gravity.CENTER
        stickerOverlay.setShadowLayer(dp(4).toFloat(), 0f, dp(2).toFloat(), Color.argb(190, 0, 0, 0))
        stickerOverlay.contentDescription = "Перетащите, чтобы изменить положение надписи"
        stickerOverlay.visibility = if (text.isNotBlank() && videoUri != null) View.VISIBLE else View.GONE
        stickerOverlay.post {
            val bounds = overlayVideoBounds()
            stickerOverlay.maxWidth = (bounds.width().toInt() - dp(32)).coerceAtLeast(dp(120))
            applyOverlayPreviewPosition()
        }
    }

    private fun installOverlayDragging() {
        stickerOverlay.isClickable = true
        stickerOverlay.setOnClickListener { showMessage("Перетащите надпись по кадру") }
        stickerOverlay.setOnTouchListener { view, event ->
            if (selectedOverlayText.isNullOrBlank() || videoUri == null) return@setOnTouchListener false
            val parent = view.parent as? View ?: return@setOnTouchListener false
            val bounds = overlayVideoBounds()
            val halfWidth = bounds.width() / 2f
            val halfHeight = bounds.height() / 2f
            if (halfWidth <= 0f || halfHeight <= 0f) return@setOnTouchListener false

            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    overlayDragStartRawX = event.rawX
                    overlayDragStartRawY = event.rawY
                    overlayDragMoved = false
                    overlayDragStartTranslationX = view.translationX
                    overlayDragStartTranslationY = view.translationY
                    view.parent?.requestDisallowInterceptTouchEvent(true)
                    true
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val movedX = event.rawX - overlayDragStartRawX
                    val movedY = event.rawY - overlayDragStartRawY
                    val touchSlop = android.view.ViewConfiguration.get(this).scaledTouchSlop
                    if (movedX * movedX + movedY * movedY > touchSlop * touchSlop) overlayDragMoved = true
                    val parentCenterX = parent.width / 2f
                    val parentCenterY = parent.height / 2f
                    val targetCenterX = parentCenterX + overlayDragStartTranslationX + movedX
                    val targetCenterY = parentCenterY + overlayDragStartTranslationY + movedY
                    val maxOffsetX = (halfWidth - view.width / 2f).coerceAtLeast(0f)
                    val maxOffsetY = (halfHeight - view.height / 2f).coerceAtLeast(0f)
                    val offsetX = (targetCenterX - bounds.centerX()).coerceIn(-maxOffsetX, maxOffsetX)
                    val offsetY = (targetCenterY - bounds.centerY()).coerceIn(-maxOffsetY, maxOffsetY)
                    overlayPositionX = (offsetX / halfWidth).coerceIn(-1f, 1f)
                    overlayPositionY = (-offsetY / halfHeight).coerceIn(-1f, 1f)
                    view.translationX = bounds.centerX() - parentCenterX + offsetX
                    view.translationY = bounds.centerY() - parentCenterY + offsetY
                    true
                }
                android.view.MotionEvent.ACTION_UP,
                android.view.MotionEvent.ACTION_CANCEL -> {
                    view.parent?.requestDisallowInterceptTouchEvent(false)
                    if (event.actionMasked == android.view.MotionEvent.ACTION_UP && !overlayDragMoved) view.performClick()
                    true
                }
                else -> true
            }
        }
    }

    private fun overlayVideoBounds(): RectF {
        val parent = stickerOverlay.parent as? View
        val surface = playerView.videoSurfaceView?.takeIf { it.width > 0 && it.height > 0 } ?: playerView
        val parentLocation = IntArray(2)
        val surfaceLocation = IntArray(2)
        parent?.getLocationOnScreen(parentLocation)
        surface.getLocationOnScreen(surfaceLocation)
        val left = (surfaceLocation[0] - parentLocation[0]).toFloat()
        val top = (surfaceLocation[1] - parentLocation[1]).toFloat()
        return RectF(left, top, left + surface.width, top + surface.height)
    }

    private fun applyOverlayPreviewPosition() {
        if (!::stickerOverlay.isInitialized || !::playerView.isInitialized) return
        val parent = stickerOverlay.parent as? View ?: return
        val bounds = overlayVideoBounds()
        val halfWidth = bounds.width() / 2f
        val halfHeight = bounds.height() / 2f
        if (halfWidth <= 0f || halfHeight <= 0f) return
        val maxOffsetX = (halfWidth - stickerOverlay.width / 2f).coerceAtLeast(0f)
        val maxOffsetY = (halfHeight - stickerOverlay.height / 2f).coerceAtLeast(0f)
        val offsetX = (overlayPositionX.coerceIn(-1f, 1f) * halfWidth).coerceIn(-maxOffsetX, maxOffsetX)
        val offsetY = (-overlayPositionY.coerceIn(-1f, 1f) * halfHeight).coerceIn(-maxOffsetY, maxOffsetY)
        overlayPositionX = offsetX / halfWidth
        overlayPositionY = -offsetY / halfHeight
        stickerOverlay.translationX = bounds.centerX() - parent.width / 2f + offsetX
        stickerOverlay.translationY = bounds.centerY() - parent.height / 2f + offsetY
    }

    private fun createVideoEffects(includeOverlay: Boolean = false): List<Effect> {
        val effects = mutableListOf<Effect>()
        filterMatrix(selectedFilter)?.let { matrix -> effects.add(IntensityRgbMatrix(matrix, selectedFilterIntensity / 100f)) }
        if (includeOverlay) {
            selectedOverlayText?.takeIf { it.isNotBlank() }?.let { text ->
                val overlayText = SpannableString(text).apply {
                    setSpan(ForegroundColorSpan(Color.WHITE), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    setSpan(StyleSpan(Typeface.BOLD), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                val overlaySettings = androidx.media3.effect.StaticOverlaySettings.Builder()
                    .setScale(overlayScalePercent / 100f, overlayScalePercent / 100f)
                    .setAlphaScale(overlayOpacityPercent / 100f)
                    .setBackgroundFrameAnchor(overlayPositionX, overlayPositionY)
                    .build()
                effects.add(OverlayEffect(listOf<androidx.media3.effect.TextureOverlay>(
                    TextOverlay.createStaticTextOverlay(overlayText, overlaySettings)
                )))
            }
        }
        return effects
    }

    private fun createExportVideoEffects(): List<Effect> = createVideoEffects(includeOverlay = true).toMutableList().apply {
        add(Presentation.createForShortSide(selectedOutputShortSide))
    }

    private fun previewEffectsKey(): String = "$selectedFilter:${if (selectedFilter == 0) 0 else selectedFilterIntensity}"

    private fun applyPreviewEffects() {
        if (!::player.isInitialized) return
        if (previewEffectsKey() == appliedPreviewEffectsKey && !previewEffectsApplyPending) return
        if (previewEffectsApplyPending) return
        val delay = (lastPreviewEffectsApplyMs + 220L - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
        previewEffectsApplyPending = true
        handler.postDelayed(previewEffectsApply, delay)
    }

    private fun filterMatrix(index: Int): FloatArray? = when (index) {
        1 -> floatArrayOf(.2126f,.2126f,.2126f,0f, .7152f,.7152f,.7152f,0f, .0722f,.0722f,.0722f,0f, 0f,0f,0f,1f)
        2 -> floatArrayOf(-1f,0f,0f,0f, 0f,-1f,0f,0f, 0f,0f,-1f,0f, 1f,1f,1f,1f)
        3 -> floatArrayOf(1.08f,.02f,0f,0f, .03f,1f,0f,0f, 0f,0f,.84f,0f, 0f,0f,0f,1f)
        4 -> floatArrayOf(.88f,0f,0f,0f, 0f,1f,0f,0f, 0f,0f,1.12f,0f, 0f,0f,0f,1f)
        5 -> floatArrayOf(.393f,.349f,.272f,0f, .769f,.686f,.534f,0f, .189f,.168f,.131f,0f, 0f,0f,0f,1f)
        6 -> floatArrayOf(.82f,0f,0f,0f, 0f,.82f,0f,0f, 0f,0f,.82f,0f, .10f,.10f,.10f,1f)
        7 -> saturationMatrix(1.45f)
        else -> null
    }

    private fun saturationMatrix(saturation: Float): FloatArray {
        val r = .2126f * (1f - saturation)
        val g = .7152f * (1f - saturation)
        val b = .0722f * (1f - saturation)
        return floatArrayOf(r + saturation,g,b,0f, r,g + saturation,b,0f, r,g,b + saturation,0f, 0f,0f,0f,1f)
    }

    private class IntensityRgbMatrix(target: FloatArray, amount: Float) : RgbMatrix {
        private val matrix = FloatArray(16).apply {
            val identity = floatArrayOf(1f,0f,0f,0f, 0f,1f,0f,0f, 0f,0f,1f,0f, 0f,0f,0f,1f)
            for (i in indices) this[i] = identity[i] + (target[i] - identity[i]) * amount.coerceIn(0f, 1f)
        }
        override fun getMatrix(presentationTimeUs: Long, useHdr: Boolean): FloatArray = matrix
    }

    private class MutableIntensityRgbMatrix : RgbMatrix {
        @Volatile
        private var matrix = IDENTITY_MATRIX

        fun setFilter(target: FloatArray?, amount: Float) {
            val intensity = amount.coerceIn(0f, 1f)
            matrix = FloatArray(16).apply {
                for (index in indices) {
                    this[index] = IDENTITY_MATRIX[index] + ((target?.get(index) ?: IDENTITY_MATRIX[index]) - IDENTITY_MATRIX[index]) * intensity
                }
            }
        }

        override fun getMatrix(presentationTimeUs: Long, useHdr: Boolean): FloatArray = matrix

        private companion object {
            val IDENTITY_MATRIX = floatArrayOf(
                1f, 0f, 0f, 0f,
                0f, 1f, 0f, 0f,
                0f, 0f, 1f, 0f,
                0f, 0f, 0f, 1f
            )
        }
    }

    private fun showExportSettings() {
        if (videoUri == null || clipDuration <= 0L) {
            showMessage("Сначала откройте видео")
            return
        }
        if (exporting) return

        val sheet = createStyledSheet("Качество экспорта", "Выберите размер кадра для готового MP4.")
        val qualities = listOf(
            Triple(720, "720p · HD", "Быстрый экспорт и небольшой файл"),
            Triple(1080, "1080p · Full HD", "Хорошая детализация для большинства экранов"),
            Triple(1440, "2K · 1440p", "До 2560 × 1440 или 1440 × 2560 для вертикального видео")
        )
        val rows = mutableListOf<Pair<LinearLayout, TextView>>()

        fun refreshSelection() {
            rows.forEachIndexed { index, (row, mark) ->
                val selected = qualities[index].first == selectedOutputShortSide
                row.background = GradientDrawable().apply {
                    setColor(if (selected) Color.rgb(26, 45, 72) else Color.rgb(23, 29, 39))
                    setCornerRadius(dp(14).toFloat())
                    setStroke(dp(1), if (selected) Color.rgb(67, 137, 255) else Color.rgb(43, 52, 66))
                }
                mark.text = if (selected) "✓" else "○"
                mark.setTextColor(if (selected) Color.rgb(116, 179, 255) else Color.rgb(129, 145, 166))
            }
        }

        qualities.forEach { (shortSide, title, detail) ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(11), dp(14), dp(11))
                isClickable = true
                isFocusable = true
            }
            val textGroup = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            textGroup.addView(TextView(this).apply {
                text = title
                textSize = 13f
                typeface = ResourcesCompat.getFont(this@MainActivity, R.font.montserrat_semibold)
                setTextColor(Color.rgb(239, 243, 249))
            })
            textGroup.addView(TextView(this).apply {
                text = detail
                textSize = 11f
                typeface = ResourcesCompat.getFont(this@MainActivity, R.font.montserrat_regular)
                setTextColor(Color.rgb(151, 163, 181))
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(3) })
            row.addView(textGroup, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8) })
            val mark = TextView(this).apply {
                textSize = 17f
                typeface = ResourcesCompat.getFont(this@MainActivity, R.font.montserrat_semibold)
            }
            row.addView(mark)
            row.setOnClickListener {
                selectedOutputShortSide = shortSide
                refreshSelection()
            }
            sheet.panel.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) })
            rows.add(row to mark)
        }
        refreshSelection()
        sheet.panel.addView(TextView(this).apply {
            text = "Если кодек телефона не поддерживает выбранный размер, Media3 подберёт доступное разрешение. Детализация также зависит от исходного видео."
            textSize = 10f
            typeface = ResourcesCompat.getFont(this@MainActivity, R.font.montserrat_regular)
            setTextColor(Color.rgb(151, 163, 181))
            setLineSpacing(dp(2).toFloat(), 1f)
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4); bottomMargin = dp(6) })
        addSheetFooter(sheet.panel, "Экспортировать", {
            sheet.dialog.dismiss()
            exportVideo()
        }, "Назад", { sheet.dialog.dismiss() })
        showStyledSheet(sheet)
    }

    private fun exportVideo() {
        val source = videoUri
        if (source == null || clipDuration <= 0L || exporting) {
            if (source == null) showMessage("Сначала откройте видео")
            return
        }
        if (trimEnd <= trimStart) {
            showMessage("Конец фрагмента должен быть позже начала")
            return
        }

        player.pause()
        exporting = true
        exportButton.isEnabled = false
        val progressContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 16)
            val statusLabel = TextView(this@MainActivity).apply { text = "Подготовка видео…"; setTextColor(Color.WHITE) }
            exportStatusLabel = statusLabel
            addView(statusLabel)
            progressBar = ProgressBar(this@MainActivity, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
            addView(progressBar)
        }
        progressDialog = AlertDialog.Builder(this).setTitle("Экспорт").setView(progressContent).setCancelable(false).show()

        val renderSource = previewProxyFile?.let { Uri.fromFile(it) } ?: source
        if (audioMuted || !audioSelectionMade || selectedAudioOrdinal == null) {
            startTransformer(renderSource, null)
        } else {
            extractChosenAudio(source, selectedAudioOrdinal!!, renderSource)
        }
    }

    private fun extractChosenAudio(source: Uri, audioOrdinal: Int, renderSource: Uri) {
        Thread {
            try {
                val stagedVideo = File(cacheDir, "source-${System.currentTimeMillis()}.mp4")
                contentResolver.openInputStream(source)?.use { input -> stagedVideo.outputStream().use { input.copyTo(it) } }
                    ?: error("Не удалось прочитать исходное видео")
                stagedInput = stagedVideo
                val audioFile = File(cacheDir, "selected-audio-${System.currentTimeMillis()}.m4a")
                extractedAudio = audioFile
                val startSeconds = trimStart / 1000.0
                val durationSeconds = (trimEnd - trimStart) / 1000.0
                val command = "-y -ss $startSeconds -i ${quoteFfmpeg(stagedVideo.absolutePath)} -t $durationSeconds -map 0:a:$audioOrdinal -vn -sn -dn -c:a aac -b:a 192k ${quoteFfmpeg(audioFile.absolutePath)}"
                FFmpegKit.executeAsync(command) { session ->
                    if (ReturnCode.isSuccess(session.returnCode) && audioFile.exists() && audioFile.length() > 0) {
                        startTransformer(renderSource, Uri.fromFile(audioFile))
                    } else {
                        runOnUiThread {
                            showMessage("Не удалось извлечь выбранную дорожку из видео")
                            finishExport()
                        }
                    }
                }
            } catch (error: Exception) {
                runOnUiThread {
                    showMessage("Не удалось подготовить звук: ${error.localizedMessage ?: "ошибка чтения"}")
                    finishExport()
                }
            }
        }.start()
    }

    private fun startTransformer(source: Uri, replacementAudio: Uri?) {
        runOnUiThread {
            try {
                val clipping = MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(trimStart)
                    .setEndPositionMs(trimEnd)
                    .build()
                val input = MediaItem.Builder().setUri(source).setClippingConfiguration(clipping).build()
                val edited = EditedMediaItem.Builder(input)
                    .setRemoveAudio(audioMuted || replacementAudio != null)
                    .setEffects(Effects(Collections.emptyList(), createExportVideoEffects()))
                    .build()
                val target = File(cacheDir, "render-${System.currentTimeMillis()}.mp4")
                outputFile = target
                val listener = object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, result: ExportResult) {
                        progressHandler.removeCallbacks(progressUpdater)
                        val overlay = overlayAudioUri
                        if (overlay != null) mixOverlayIntoExport(target, overlay)
                        else publishCompletedExport(target)
                    }

                    override fun onError(composition: Composition, result: ExportResult, exception: ExportException) {
                        progressHandler.removeCallbacks(progressUpdater)
                        showMessage("Ошибка экспорта: ${exception.localizedMessage ?: "формат не поддержан"}")
                        finishExport()
                    }
                }
                val active = Transformer.Builder(this)
                    .setEncoderFactory(DefaultEncoderFactory.Builder(this).setEnableFallback(true).build())
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .setAudioMimeType(MimeTypes.AUDIO_AAC)
                    .addListener(listener)
                    .build()
                transformer = active
                if (replacementAudio == null) {
                    active.start(edited, target.absolutePath)
                } else {
                    val videoSequence = EditedMediaItemSequence.withVideoFrom(listOf(edited))
                    val audioItem = EditedMediaItem.Builder(MediaItem.fromUri(replacementAudio)).build()
                    val audioSequence = EditedMediaItemSequence.withAudioFrom(listOf(audioItem))
                    active.start(Composition.Builder(videoSequence, audioSequence).build(), target.absolutePath)
                }
                progressHandler.post(progressUpdater)
            } catch (error: Exception) {
                showMessage("Не удалось начать экспорт: ${error.localizedMessage ?: "ошибка"}")
                finishExport()
            }
        }
    }

    private fun mixOverlayIntoExport(videoFile: File, audioUri: Uri) {
        runOnUiThread { exportStatusLabel?.text = "Накладываем аудио…" }
        Thread {
            try {
                val overlayFile = File(cacheDir, "audio-overlay-${System.currentTimeMillis()}.audio")
                contentResolver.openInputStream(audioUri)?.use { input -> overlayFile.outputStream().use { input.copyTo(it) } }
                    ?: error("Не удалось прочитать аудиофайл")
                val mixedFile = File(cacheDir, "mixed-${System.currentTimeMillis()}.mp4")
                val duration = String.format(Locale.US, "%.3f", (trimEnd - trimStart).coerceAtLeast(1L) / 1000.0)
                val gain = String.format(Locale.US, "%.2f", overlayAudioGain.coerceIn(0f, 2f))
                val hasBaseAudio = !audioMuted && audioOptions.isNotEmpty()
                val filter = if (hasBaseAudio) {
                    "[0:a:0]aresample=async=1:first_pts=0[base];[1:a:0]atrim=duration=$duration,asetpts=PTS-STARTPTS,volume=$gain[over];[base][over]amix=inputs=2:duration=first:dropout_transition=0[aout]"
                } else {
                    "[1:a:0]atrim=duration=$duration,asetpts=PTS-STARTPTS,volume=$gain[aout]"
                }
                val audioLoop = if (hasBaseAudio) "-stream_loop -1 " else ""
                val command = "-y -i ${quoteFfmpeg(videoFile.absolutePath)} $audioLoop-i ${quoteFfmpeg(overlayFile.absolutePath)} -filter_complex ${quoteFfmpeg(filter)} -map 0:v:0 -map [aout] -c:v copy -c:a aac -b:a 192k -t $duration -movflags +faststart ${quoteFfmpeg(mixedFile.absolutePath)}"
                FFmpegKit.executeAsync(command) { session ->
                    overlayFile.delete()
                    if (ReturnCode.isSuccess(session.returnCode) && mixedFile.exists() && mixedFile.length() > 0) {
                        videoFile.delete()
                        if (!mixedFile.renameTo(videoFile)) mixedFile.copyTo(videoFile, overwrite = true)
                        mixedFile.delete()
                        publishCompletedExport(videoFile)
                    } else {
                        mixedFile.delete()
                        runOnUiThread {
                            showMessage("Не удалось наложить аудио. Проверьте формат выбранного файла.")
                            finishExport()
                        }
                    }
                }
            } catch (error: Exception) {
                runOnUiThread {
                    showMessage("Не удалось добавить аудио: ${error.localizedMessage ?: "ошибка чтения"}")
                    finishExport()
                }
            }
        }.start()
    }

    private fun publishCompletedExport(file: File) {
        runOnUiThread {
            try {
                publishExport(file)
                showMessage("Видео сохранено в Фильмы / MediaRedaktor")
            } catch (error: Exception) {
                showMessage("Не удалось сохранить видео: ${error.localizedMessage ?: "ошибка записи"}")
            } finally {
                finishExport()
            }
        }
    }

    private fun quoteFfmpeg(value: String): String = "'${value.replace("'", "'\\''")}'"

    private fun publishExport(file: File): Uri? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, "MediaRedaktor-${System.currentTimeMillis()}.mp4")
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/MediaRedaktor")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: error("Не удалось создать файл в медиатеке")
            try {
                contentResolver.openOutputStream(uri)?.use { output -> FileInputStream(file).use { it.copyTo(output) } }
                    ?: error("Не удалось открыть файл для записи")
                values.clear()
                values.put(MediaStore.Video.Media.IS_PENDING, 0)
                contentResolver.update(uri, values, null, null)
                return uri
            } catch (error: Exception) {
                contentResolver.delete(uri, null, null)
                throw error
            } finally {
                file.delete()
            }
        }
        val directory = getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: filesDir
        val destination = File(directory, "MediaRedaktor-${System.currentTimeMillis()}.mp4")
        file.copyTo(destination, overwrite = true)
        file.delete()
        return Uri.fromFile(destination)
    }

    private fun finishExport() {
        exporting = false
        transformer = null
        progressHandler.removeCallbacks(progressUpdater)
        progressDialog?.dismiss()
        progressDialog = null
        progressBar = null
        exportStatusLabel = null
        exportButton.isEnabled = true
        outputFile?.delete()
        extractedAudio?.delete()
        stagedInput?.delete()
        outputFile = null
        extractedAudio = null
        stagedInput = null
    }

    private fun displayName(uri: Uri): String {
        return contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        } ?: "Видео"
    }

    private fun updatePlayButton() {
        if (::player.isInitialized) playButton.text = if (player.isPlaying) "Ⅱ" else "▶"
    }

    private fun formatTime(milliseconds: Int): String {
        val seconds = (milliseconds / 1000).coerceAtLeast(0)
        val centiseconds = (milliseconds % 1000) / 10
        return "%02d:%02d.%02d".format(seconds / 60, seconds % 60, centiseconds)
    }

    private fun showMessage(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        handler.removeCallbacks(previewEffectsApply)
        handler.removeCallbacks(previewEffectRedraw)
        progressHandler.removeCallbacks(progressUpdater)
        overlayPreviewPlayer?.release()
        overlayPreviewPlayer = null
        if (::player.isInitialized) {
            playerView.player = null
            player.release()
        }
        super.onDestroy()
    }
}
