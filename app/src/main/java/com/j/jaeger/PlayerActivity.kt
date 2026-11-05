package com.j.jaeger

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.audiofx.LoudnessEnhancer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.Window
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.MediaItem
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.trackselection.MappingTrackSelector
import androidx.media3.exoplayer.upstream.DefaultDataSourceFactory
import androidx.media3.exoplayer.upstream.DefaultHttpDataSource
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.android.synthetic.main.activity_player.*
import java.util.concurrent.TimeUnit

@UnstableApi
class PlayerActivity : AppCompatActivity() {

    private lateinit var player: ExoPlayer
    private lateinit var playerView: PlayerView
    private lateinit var dynamicSubtitleView: SubtitleView
    private lateinit var hudTextView: TextView
    private lateinit var btnZoom: MaterialButton
    private lateinit var btnBoost: MaterialButton
    private lateinit var btnTracks: MaterialButton
    private lateinit var rootContainer: View
    private lateinit var topBar: View

    private var audioManager: AudioManager? = null
    private var loudnessEnhancer: LoudnessEnhancer? = null
    private var audioSessionId: Int = -1
    private var isBoostEnabled = false

    private var isZoomMode = false
    private var subtitleBottomMargin = 24

    // Touch handling
    private var isLongPressDetected = false
    private var isSpeedMode = false
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var initialTouchTime = 0L
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()
    private val tapTimeout = ViewConfiguration.getTapTimeout().toLong()
    private val doubleTapTimeout = ViewConfiguration.getDoubleTapTimeout().toLong()
    private var lastTapTime = 0L
    private var lastTapX = 0f
    private val handler = Handler(Looper.getMainLooper())
    private val longPressRunnable = Runnable { enterSpeedMode() }
    private val screenWidth = 0
    private val screenHeight = 0
    private var maxVolume = 100
    private var currentVolume = 50

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContentView(R.layout.activity_player)

        playerView = findViewById(R.id.playerView)
        dynamicSubtitleView = findViewById(R.id.dynamicSubtitleView)
        hudTextView = findViewById(R.id.hudTextView)
        btnZoom = findViewById(R.id.btnZoom)
        btnBoost = findViewById(R.id.btnBoost)
        btnTracks = findViewById(R.id.btnTracks)
        rootContainer = findViewById(R.id.rootContainer)
        topBar = findViewById(R.id.topBar)

        setupImmersiveMode()
        setupAudio()
        setupPlayer()
        setupControls()
        setupTouchHandler()
        loadMediaFromIntent(intent)
    }

    private fun setupImmersiveMode() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
    }

    private fun setupAudio() {
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        maxVolume = audioManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 100
        currentVolume = audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 50
    }

    private fun setupPlayer() {
        val trackSelector = DefaultTrackSelector(this)
        trackSelector.setParameters(
            trackSelector.buildUponParameters()
                .setPreferredTextLanguage("eng")
                .setSelectUndeterminedTextLanguage(true)
        )

        player = ExoPlayer.Builder(this)
            .setTrackSelector(trackSelector)
            .build()

        playerView.player = player
        playerView.useController = false

        dynamicSubtitleView.setUserDefaultStyle()
        dynamicSubtitleView.setUserDefaultTextSize()
        val captionStyle = androidx.media3.common.text.CaptionStyleCompat(
            androidx.media3.common.text.CaptionStyleCompat.EDGE_TYPE_OUTLINE,
            android.graphics.Color.WHITE,
            android.graphics.Color.TRANSPARENT,
            android.graphics.Typeface.DEFAULT_BOLD,
            android.graphics.Color.BLACK,
            android.graphics.Color.TRANSPARENT
        )
        dynamicSubtitleView.applyStyle(captionStyle)
        dynamicSubtitleView.setFixedTextSize(18f)

        player.addListener(object : Player.Listener {
            override fun onCues(cueGroup: androidx.media3.common.CueGroup) {
                dynamicSubtitleView.setCues(cueGroup.cues)
            }

            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                this@PlayerActivity.audioSessionId = audioSessionId
                setupLoudnessEnhancer(audioSessionId)
            }

            override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
                if (!isSpeedMode && playbackParameters.speed != 1.0f) {
                    player.playbackParameters = PlaybackParameters(1.0f)
                }
            }
        })
    }

    private fun setupLoudnessEnhancer(audioSessionId: Int) {
        try {
            loudnessEnhancer?.release()
            loudnessEnhancer = LoudnessEnhancer(audioSessionId)
            loudnessEnhancer?.enabled = false
        } catch (e: Exception) {
            Log.w("Jaeger", "LoudnessEnhancer not available", e)
            loudnessEnhancer = null
        }
    }

    private fun setupControls() {
        btnZoom.setOnClickListener { toggleZoomMode() }
        btnBoost.setOnClickListener { toggleBoost() }
        btnTracks.setOnClickListener { showTrackSelectionDialog() }
    }

    private fun toggleZoomMode() {
        isZoomMode = !isZoomMode
        val resizeMode = if (isZoomMode) PlayerView.RESIZE_MODE_ZOOM else PlayerView.RESIZE_MODE_FIT
        playerView.resizeMode = resizeMode
        btnZoom.text = if (isZoomMode) getString(R.string.zoom_crop) else getString(R.string.zoom_fit)

        val params = dynamicSubtitleView.layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
        params.bottomMargin = if (isZoomMode) 48 else 24
        dynamicSubtitleView.layoutParams = params
        dynamicSubtitleView.requestLayout()
    }

    private fun toggleBoost() {
        isBoostEnabled = !isBoostEnabled
        loudnessEnhancer?.enabled = isBoostEnabled
        if (isBoostEnabled) {
            try {
                loudnessEnhancer?.targetGain = 1000
            } catch (e: Exception) {
                Log.w("Jaeger", "Failed to set loudness gain", e)
            }
        }
        btnBoost.text = if (isBoostEnabled) getString(R.string.boost_on) else getString(R.string.boost_off)
    }

    private fun showTrackSelectionDialog() {
        val trackSelector = player.trackSelector as DefaultTrackSelector
        val currentTracks = trackSelector.currentMappedTrackInfo
        if (currentTracks == null) {
            Toast.makeText(this, "No tracks available", Toast.LENGTH_SHORT).show()
            return
        }

        val audioTracks = mutableListOf<Pair<Int, String>>()
        val subtitleTracks = mutableListOf<Pair<Int, String>>()

        for (i in 0 until currentTracks.length) {
            val trackGroup = currentTracks.getTrackGroups().get(i)
            val trackType = currentTracks.getRendererType(i)
            if (trackType == C.TRACK_TYPE_AUDIO) {
                for (j in 0 until trackGroup.length) {
                    val format = trackGroup.getFormat(j)
                    val lang = format.language ?: "und"
                    val label = "${format.sampleMimeType} - $lang ${if (format.channelCount > 0) "${format.channelCount}ch" else ""}"
                    audioTracks.add(i to label)
                }
            } else if (trackType == C.TRACK_TYPE_TEXT) {
                for (j in 0 until trackGroup.length) {
                    val format = trackGroup.getFormat(j)
                    val lang = format.language ?: "und"
                    val label = "${format.sampleMimeType} - $lang"
                    subtitleTracks.add(i to label)
                }
            }
        }

        val items = mutableListOf<String>()
        val trackData = mutableListOf<Pair<Int, Int>>()

        items.add("--- Audio Tracks ---")
        trackData.add(-1 to -1)
        for ((rendererIndex, label) in audioTracks) {
            items.add(label)
            trackData.add(rendererIndex to items.size - 1)
        }

        items.add("--- Subtitle Tracks ---")
        trackData.add(-1 to -1)
        for ((rendererIndex, label) in subtitleTracks) {
            items.add(label)
            trackData.add(rendererIndex to items.size - 1)
        }

        val selectedIndex = -1
        MaterialAlertDialogBuilder(this)
            .setTitle("Select Track")
            .setSingleChoiceItems(items.toTypedArray(), -1) { dialog, which ->
                val (rendererIndex, _) = trackData[which]
                if (rendererIndex >= 0) {
                    val params = trackSelector.buildUponParameters()
                    val override = TrackSelectionOverride(rendererIndex, trackData[which].second)
                    val newParams = params.setTrackSelectionOverride(
                        trackSelector.currentMappedTrackInfo!!,
                        override
                    )
                    trackSelector.setParameters(newParams)
                }
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun loadMediaFromIntent(intent: Intent?) {
        var uri: Uri? = intent?.data
        if (uri == null) {
            openFilePicker()
        } else {
            prepareMediaSource(uri)
        }
    }

    private fun openFilePicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "video/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("video/*"))
        }
        startActivityForResult(intent, 1001)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1001 && resultCode == Activity.RESULT_OK) {
            data?.data?.let { uri ->
                val takeFlags = data.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                contentResolver.takePersistableUriPermission(uri, takeFlags)
                prepareMediaSource(uri)
            }
        } else if (resultCode != Activity.RESULT_OK) {
            finish()
        }
    }

    private fun prepareMediaSource(uri: Uri) {
        val dataSourceFactory = DefaultDataSourceFactory(this)
        val mediaSource: MediaSource = ProgressiveMediaSource.Factory(dataSourceFactory)
            .createMediaSource(MediaItem.fromUri(uri))
        player.setMediaSource(mediaSource)
        player.prepare()
        player.playWhenReady = true
    }

    private fun setupTouchHandler() {
        rootContainer.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    lastTouchX = event.rawX
                    lastTouchY = event.rawY
                    initialTouchTime = System.currentTimeMillis()
                    isLongPressDetected = false
                    isSpeedMode = false
                    handler.postDelayed(longPressRunnable, longPressTimeout)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val deltaX = event.rawX - initialTouchX
                    val deltaY = event.rawY - initialTouchY
                    val distance = Math.sqrt(deltaX * deltaX + deltaY * deltaY).toInt()
                    val currentTime = System.currentTimeMillis()
                    val elapsed = currentTime - initialTouchTime

                    if (!isLongPressDetected && distance > ViewConfiguration.getScaledTouchSlop()) {
                        handler.removeCallbacks(longPressRunnable)
                    }

                    if (isSpeedMode) {
                        handleSpeedDrag(event.rawY)
                        return@setOnTouchListener true
                    }

                    if (isLongPressDetected) {
                        return@setOnTouchListener true
                    }

                    if (elapsed < longPressTimeout) {
                        handleSwipeGestures(event)
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(longPressRunnable)
                    if (isSpeedMode) {
                        exitSpeedMode()
                    } else if (!isLongPressDetected) {
                        val currentTime = System.currentTimeMillis()
                        val elapsed = currentTime - initialTouchTime
                        if (elapsed < tapTimeout) {
                            handleTap(event.rawX, event.rawY, currentTime)
                        }
                    }
                    isLongPressDetected = false
                    isSpeedMode = false
                    true
                }
            }
            false
        }
    }

    private fun enterSpeedMode() {
        isLongPressDetected = true
        isSpeedMode = true
        player.playbackParameters = PlaybackParameters(2.0f)
        showHUD(String.format(getString(R.string.speed_format), 2.0f))
    }

    private fun handleSpeedDrag(currentY: Float) {
        val deltaY = initialTouchY - currentY
        val speedChange = deltaY / (screenHeight * 0.5f) * 3.5f
        var newSpeed = 2.0f + speedChange
        newSpeed = newSpeed.coerceIn(0.5f, 4.0f)
        newSpeed = (newSpeed * 10).roundToInt() / 10f
        player.playbackParameters = PlaybackParameters(newSpeed)
        showHUD(String.format(getString(R.string.speed_format), newSpeed))
    }

    private fun exitSpeedMode() {
        player.playbackParameters = PlaybackParameters(1.0f)
        hideHUD()
    }

    private fun handleSwipeGestures(event: MotionEvent) {
        val deltaX = event.rawX - lastTouchX
        val deltaY = event.rawY - lastTouchY
        val absDeltaX = Math.abs(deltaX)
        val absDeltaY = Math.abs(deltaY)

        if (absDeltaX > absDeltaY) {
            handleHorizontalSwipe(deltaX)
        } else {
            val halfWidth = screenWidth / 2f
            if (initialTouchX < halfWidth) {
                handleBrightnessSwipe(deltaY)
            } else {
                handleVolumeSwipe(deltaY)
            }
        }
        lastTouchX = event.rawX
        lastTouchY = event.rawY
    }

    private fun handleHorizontalSwipe(deltaX: Float) {
        val currentPosition = player.currentPosition
        val duration = player.duration
        if (duration == C.TIME_UNSET) return

        val seekDelta = (deltaX / screenWidth * duration * 0.5f).toLong()
        var newPosition = currentPosition + seekDelta
        newPosition = newPosition.coerceIn(0, duration)
        player.seekTo(newPosition)

        val offsetSeconds = seekDelta / 1000
        val targetTime = formatTime(newPosition)
        showHUD(String.format(getString(R.string.seek_format), targetTime, offsetSeconds))
    }

    private fun handleBrightnessSwipe(deltaY: Float) {
        val brightnessChange = -deltaY / screenHeight
        var brightness = window.attributes.screenBrightness
        if (brightness < 0) brightness = Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, 255) / 255f
        brightness = (brightness + brightnessChange).coerceIn(0.01f, 1.0f)
        window.attributes.screenBrightness = brightness
        showHUD(String.format(getString(R.string.brightness_format), (brightness * 100).toInt()))
    }

    private fun handleVolumeSwipe(deltaY: Float) {
        val volumeChange = (-deltaY / screenHeight * maxVolume).toInt()
        currentVolume = (currentVolume + volumeChange).coerceIn(0, maxVolume)
        audioManager?.setStreamVolume(AudioManager.STREAM_MUSIC, currentVolume, 0)
        showHUD(String.format(getString(R.string.volume_format), (currentVolume * 100 / maxVolume)))
    }

    private fun handleTap(x: Float, y: Float, currentTime: Long) {
        val halfWidth = screenWidth / 2f
        val leftZone = screenWidth * 0.35f
        val rightZone = screenWidth * 0.65f

        if (currentTime - lastTapTime < doubleTapTimeout) {
            if (Math.abs(x - lastTapX) < ViewConfiguration.getScaledTouchSlop()) {
                if (x < leftZone) {
                    player.seekTo(Math.max(0, player.currentPosition - 10000))
                    showHUD("Rewind -10s")
                } else if (x > rightZone) {
                    player.seekTo(Math.min(player.duration, player.currentPosition + 10000))
                    showHUD("Forward +10s")
                }
                lastTapTime = 0
                return
            }
        }

        if (x > leftZone && x < rightZone) {
            player.playWhenReady = !player.playWhenReady
            showHUD(if (player.playWhenReady) "Play" else "Pause")
        }

        lastTapTime = currentTime
        lastTapX = x
    }

    private fun showHUD(text: String) {
        hudTextView.text = text
        hudTextView.visibility = View.VISIBLE
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ hideHUD() }, 1500)
    }

    private fun hideHUD() {
        hudTextView.visibility = View.GONE
    }

    private fun formatTime(timeMs: Long): String {
        return String.format("%02d:%02d:%02d",
            TimeUnit.MILLISECONDS.toHours(timeMs),
            TimeUnit.MILLISECONDS.toMinutes(timeMs) % 60,
            TimeUnit.MILLISECONDS.toSeconds(timeMs) % 60
        )
    }

    override fun onResume() {
        super.onResume()
        setupImmersiveMode()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            setupImmersiveMode()
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        loudnessEnhancer?.release()
        player.release()
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        loadMediaFromIntent(intent)
    }
}