package com.meekamoodz.audioconvertereditor

import android.app.Activity
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Environment
import android.provider.MediaStore
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.google.android.gms.ads.*
import com.google.android.gms.ads.appopen.AppOpenAd
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Locale
import kotlin.math.abs

private class AudioWaveformView(context: android.content.Context) : View(context) {
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var levels: List<Float> = emptyList()
    private var durationSeconds = 0.0
    private var startSeconds = 0.0
    private var endSeconds = 0.0
    private var playheadSeconds = 0.0
    private var dragMode = DragMode.PLAYHEAD
    private enum class DragMode { START, END, PLAYHEAD }
    var onSelectionChanged: ((Double, Double) -> Unit)? = null
    var onPlayheadChanged: ((Double) -> Unit)? = null

    init {
        barPaint.color = Color.rgb(40, 165, 250)
        handlePaint.color = Color.rgb(40, 165, 250)
        textPaint.color = Color.WHITE
        textPaint.textSize = 26f
    }

    fun setWaveform(values: List<Float>, duration: Double) {
        levels = values
        durationSeconds = duration.coerceAtLeast(0.01)
        startSeconds = 0.0
        endSeconds = durationSeconds
        playheadSeconds = 0.0
        invalidate()
        onSelectionChanged?.invoke(startSeconds, endSeconds)
    }

    fun setPlayhead(seconds: Double) {
        playheadSeconds = seconds.coerceIn(0.0, durationSeconds.coerceAtLeast(0.0))
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        canvas.drawColor(Color.rgb(23, 34, 82))
        if (levels.isEmpty()) {
            textPaint.textAlign = Paint.Align.CENTER
            canvas.drawText("Choose a file to load its waveform", w / 2f, h / 2f, textPaint)
            return
        }
        val sx = (startSeconds / durationSeconds).toFloat().coerceIn(0f, 1f) * w
        val ex = (endSeconds / durationSeconds).toFloat().coerceIn(0f, 1f) * w
        val px = (playheadSeconds / durationSeconds).toFloat().coerceIn(0f, 1f) * w
        canvas.drawRect(sx, 0f, ex, h, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(36, 40, 165, 250) })
        val step = w / levels.size.coerceAtLeast(1)
        val bw = (step * 0.48f).coerceAtLeast(1f)
        levels.forEachIndexed { index, level ->
            val x = index * step + (step - bw) / 2f
            val bh = (level.coerceIn(0.025f, 1f) * h * 0.72f).coerceAtLeast(3f)
            barPaint.color = if (x >= sx && x <= ex) Color.rgb(40, 190, 235) else Color.rgb(76, 91, 148)
            canvas.drawRoundRect(x, (h - bh) / 2f, x + bw, (h + bh) / 2f, bw / 2f, bw / 2f, barPaint)
        }
        handlePaint.color = Color.rgb(40, 220, 160)
        canvas.drawRoundRect(sx - 4f, 0f, sx + 4f, h, 4f, 4f, handlePaint)
        canvas.drawRoundRect(ex - 4f, 0f, ex + 4f, h, 4f, 4f, handlePaint)
        canvas.drawCircle(sx, h * 0.12f, 10f, handlePaint)
        canvas.drawCircle(ex, h * 0.12f, 10f, handlePaint)
        handlePaint.color = Color.rgb(255, 190, 70)
        canvas.drawLine(px, 0f, px, h, handlePaint.apply { strokeWidth = 3.5f })
        canvas.drawCircle(px, h * 0.5f, 8f, handlePaint)
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.textSize = 10f * resources.displayMetrics.density
        textPaint.color = Color.rgb(255, 210, 120)
        canvas.drawText("PLAYHEAD", px.coerceIn(38f, w - 38f), h - 7f, textPaint)
        textPaint.color = Color.WHITE
        textPaint.textSize = 26f
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (durationSeconds <= 0.0 || width <= 0) return false
        val x = event.x.coerceIn(0f, width.toFloat())
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                val sx = (startSeconds / durationSeconds).toFloat() * width
                val ex = (endSeconds / durationSeconds).toFloat() * width
                val px = (playheadSeconds / durationSeconds).toFloat() * width
                dragMode = if (event.y < height * 0.32f) {
                    if (abs(x - sx) <= abs(x - ex)) DragMode.START else DragMode.END
                } else {
                    if (abs(x - sx) < dp(18f) && abs(x - sx) <= abs(x - px)) DragMode.START
                    else if (abs(x - ex) < dp(18f) && abs(x - ex) < abs(x - px)) DragMode.END
                    else DragMode.PLAYHEAD
                }
                parent?.requestDisallowInterceptTouchEvent(true)
                updateFromX(x)
                return true
            }
            MotionEvent.ACTION_MOVE -> { updateFromX(x); return true }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                updateFromX(x)
                parent?.requestDisallowInterceptTouchEvent(false)
                performClick()
                return true
            }
        }
        return true
    }

    private fun updateFromX(x: Float) {
        val t = (x / width.toFloat()).coerceIn(0f, 1f) * durationSeconds
        when (dragMode) {
            DragMode.START -> startSeconds = t.coerceAtMost(endSeconds - 0.05).coerceAtLeast(0.0)
            DragMode.END -> endSeconds = t.coerceAtLeast(startSeconds + 0.05).coerceAtMost(durationSeconds)
            DragMode.PLAYHEAD -> {
                playheadSeconds = t
                onPlayheadChanged?.invoke(playheadSeconds)
            }
        }
        invalidate()
        onSelectionChanged?.invoke(startSeconds, endSeconds)
    }

    private fun dp(value: Float) = value * resources.displayMetrics.density
    override fun performClick(): Boolean { super.performClick(); return true }
}

class MainActivity : ComponentActivity() {
    private val bg = Color.rgb(7, 15, 58)
    private val panel = Color.rgb(27, 39, 91)
    private val panel2 = Color.rgb(34, 47, 105)
    private val blue = Color.rgb(35, 166, 245)
    private val purple = Color.rgb(139, 75, 246)
    private val cyan = Color.rgb(21, 220, 218)
    private val green = Color.rgb(65, 220, 125)
    private lateinit var status: TextView
    private lateinit var fileLabel: TextView
    private lateinit var waveform: AudioWaveformView
    private lateinit var startTime: EditText
    private lateinit var endTime: EditText
    private lateinit var playerTime: TextView
    private lateinit var playerSeek: SeekBar
    private lateinit var banner: AdView
    private var selectedUri: Uri? = null
    private var selectedName = "audio"
    private var activeTool = "convert"
    private var interstitial: InterstitialAd? = null
    private var rewarded: RewardedAd? = null
    private var appOpen: AppOpenAd? = null
    private var lastInterstitialAt = 0L
    private var lastSavedUri: Uri? = null
    private var pendingExport: File? = null
    private var pendingOutputName = "Audio"
    private var player: MediaPlayer? = null
    private var playerPrepared = false
    private var showingAppOpen = false
    private var splashVisible = false
    private val handler = Handler(Looper.getMainLooper())
    private var updatingSeek = false
    private var lastDurationMs = 0
    private var selectedVoiceEffect = "Normal"
    private data class MixTrack(val uri: Uri, val name: String, var offsetMs: Int = 0, var gain: Int = 100)
    private val mixTracks = mutableListOf<MixTrack>()
    private var mixRows: LinearLayout? = null
    private var mixStatus: TextView? = null
    private var videoPreview: VideoView? = null

    private var equalizerPreset = "Flat"
    private val equalizerGains = MutableList(10) { 0 }

    private val picker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) result.data?.data?.let { uri ->
            selectedUri = uri
            selectedName = queryName(uri) ?: "selected_media"
            if (::fileLabel.isInitialized) fileLabel.text = "Selected: $selectedName"
            if (::status.isInitialized) status.text = "Loading audio waveform…"
            try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
            if (::waveform.isInitialized) loadWaveform(uri)
            if (activeTool == "video") {
                videoPreview?.setVideoURI(uri)
                status.text = "Video loaded. Press play in the preview to watch it."
            } else preparePlayer(uri)
        }
    }


    private val mixPicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val data = result.data
            val uris = mutableListOf<Uri>()
            data?.clipData?.let { clip -> for (i in 0 until clip.itemCount) uris.add(clip.getItemAt(i).uri) }
            if (uris.isEmpty()) data?.data?.let { uris.add(it) }
            uris.forEach { uri ->
                try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
                mixTracks.add(MixTrack(uri, queryName(uri) ?: "Audio ${mixTracks.size + 1}"))
            }
            renderMixRows()
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            val p = player
            if (p != null && playerPrepared && ::playerSeek.isInitialized) {
                val pos = p.currentPosition.coerceAtLeast(0)
                updatingSeek = true
                playerSeek.max = p.duration.coerceAtLeast(1)
                playerSeek.progress = pos
                updatingSeek = false
                playerTime.text = "${timeText(pos)} / ${timeText(p.duration)}"
                if (::waveform.isInitialized) waveform.setPlayhead(pos / 1000.0)
                handler.postDelayed(this, 120)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (splashVisible) return
                if (activeTool != "home") {
                    stopPlayer()
                    showHome()
                    return
                }
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Exit Audio Converter and Editor?")
                    .setMessage("Do you want to close the app?")
                    .setNegativeButton("STAY", null)
                    .setPositiveButton("EXIT") { _, _ -> finish() }
                    .show()
            }
        })
        MobileAds.initialize(this) {}
        loadInterstitial()
        loadRewarded()
        loadAppOpen()
        showSplash()
    }

    override fun onStart() {
        super.onStart()
        showAppOpenIfReady()
    }


    private fun showSplash() {
        splashVisible = true
        val root = baseRoot()
        root.gravity = Gravity.CENTER
        val content = column().apply {
            gravity = Gravity.CENTER
            setPadding(dp(28), dp(24), dp(28), dp(24))
        }
        content.addView(TextView(this).apply {
            text = "♫"
            textSize = 76f
            gravity = Gravity.CENTER
            setTextColor(cyan)
            setShadowLayer(dp(22).toFloat(), 0f, 0f, blue)
        }, matchWrap())
        content.addView(TextView(this).apply {
            text = "AUDIO CONVERTER"
            textSize = 25f
            gravity = Gravity.CENTER
            letterSpacing = 0.08f
            setTextColor(Color.WHITE)
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, dp(12), 0, dp(2))
        }, matchWrap())
        content.addView(TextView(this).apply {
            text = "AND EDITOR"
            textSize = 16f
            gravity = Gravity.CENTER
            letterSpacing = 0.22f
            setTextColor(cyan)
        }, matchWrap())
        val bars = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(34), 0, dp(18))
        }
        val heights = listOf(18, 34, 52, 28, 44, 22, 48, 30, 18)
        heights.forEachIndexed { index, height ->
            val bar = View(this).apply {
                background = rounded(if (index % 2 == 0) cyan else blue, 8)
                pivotY = dp(height).toFloat() / 2f
            }
            bars.addView(bar, LinearLayout.LayoutParams(dp(5), dp(height)).apply {
                marginStart = dp(4); marginEnd = dp(4)
            })
            ObjectAnimator.ofFloat(bar, "scaleY", 0.35f, 1.0f, 0.35f).apply {
                duration = 520L + index * 65L
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.REVERSE
                startDelay = index * 55L
                start()
            }
        }
        content.addView(bars, matchWrap())
        content.addView(TextView(this).apply {
            text = "GETTING YOUR STUDIO READY"
            textSize = 10f
            gravity = Gravity.CENTER
            letterSpacing = 0.16f
            setTextColor(Color.LTGRAY)
        }, matchWrap())
        val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            progressTintList = android.content.res.ColorStateList.valueOf(cyan)
            indeterminateTintList = android.content.res.ColorStateList.valueOf(cyan)
        }
        content.addView(progress, LinearLayout.LayoutParams(dp(170), dp(4)).apply { topMargin = dp(14) })
        root.addView(content, LinearLayout.LayoutParams(-1, -2))
        setContentView(root)
        handler.postDelayed({
            if (!isFinishing && splashVisible) {
                splashVisible = false
                showHome()
                showAppOpenIfReady()
            }
        }, 1900L)    }

    private fun showHome() {
        activeTool = "home"
        videoPreview = null
        val root = baseRoot()
        val scroll = ScrollView(this).apply { clipToPadding = false }
        val body = column().apply { setPadding(dp(18), dp(12), dp(18), dp(22)) }

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(16))
        }
        top.addView(TextView(this).apply {
            text = "♫"
            textSize = 34f
            setTextColor(cyan)
            setShadowLayer(dp(14).toFloat(), 0f, 0f, blue)
            setPadding(0, 0, dp(10), 0)
        }, LinearLayout.LayoutParams(dp(48), -2))
        val brand = column()
        brand.addView(TextView(this).apply {
            text = "AUDIO EDITOR"
            textSize = 19f
            letterSpacing = 0.12f
            setTextColor(Color.WHITE)
            setTypeface(null, android.graphics.Typeface.BOLD)
        }, matchWrap())
        brand.addView(TextView(this).apply {
            text = "Your all-in-one audio studio"
            textSize = 12f
            setTextColor(Color.rgb(145, 184, 224))
        }, matchWrap())
        top.addView(brand, LinearLayout.LayoutParams(0, -2, 1f))
        body.addView(top, matchWrap())

        val hero = column().apply {
            setPadding(dp(18), dp(18), dp(18), dp(18))
            background = rounded(Color.rgb(16, 47, 104), 22)
        }
        hero.addView(TextView(this).apply {
            text = "MAKE EVERY SOUND\nYOUR OWN"
            textSize = 23f
            letterSpacing = 0.04f
            setTextColor(Color.WHITE)
            setTypeface(null, android.graphics.Typeface.BOLD)
        }, matchWrap())
        hero.addView(TextView(this).apply {
            text = "Trim, mix, enhance and convert your favourite audio."
            textSize = 13f
            setTextColor(Color.rgb(190, 214, 245))
            setPadding(0, dp(7), 0, dp(14))
        }, matchWrap())
        hero.addView(button("OPEN AUDIO STUDIO  →", purple) { openTool("convert", "Format Converter") }, buttonParams())
        body.addView(hero, matchWrap())

        section(body, "AUDIO EDITOR")
        addTileGrid(body, listOf(
            Triple("✂", "Trim Audio", "trim"),
            Triple("▥", "Multi-Track Studio", "multitrack"),
            Triple("◖))", "Volume Booster", "volume")
        ))
        section(body, "AUDIO PROCESSING")
        addTileGrid(body, listOf(
            Triple("▶♫", "Video to MP3", "video"),
            Triple("⇄", "Format Converter", "convert"),
            Triple("▤", "Compressor", "compress"),
            Triple("Ⅱ", "Split Audio", "split")
        ))
        section(body, "AUDIO EFFECTS")
        addTileGrid(body, listOf(
            Triple("♫", "10-Band Equalizer", "equalizer"),
            Triple("🎙", "Voice Changer", "voice"),
            Triple("☎", "Ringtone Maker", "ringtone")
        ))
        section(body, "YOUR STUDIO")
        body.addView(button("▣   MY SAVED AUDIO & OUTPUTS", panel2) { showOutputs() }, buttonParams())
        body.addView(button("PRIVACY POLICY", panel2) {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://sites.google.com/view/droptechdevelopers/audio-converter-and-editor-button?authuser=1")))
        }, buttonParams())
        body.addView(TextView(this).apply {
            text = "Create • Edit • Convert • Listen"
            textSize = 11f
            gravity = Gravity.CENTER
            letterSpacing = 0.12f
            setTextColor(Color.rgb(133, 160, 205))
            setPadding(0, dp(18), 0, dp(6))
        }, matchWrap())

        scroll.addView(body)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(7), dp(8), dp(7))
            background = rounded(Color.rgb(12, 22, 70), 16)
        }
        fun navItem(icon: String, label: String, selected: Boolean, action: () -> Unit) {
            val item = column().apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(5), 0, dp(5))
                isClickable = true
                isFocusable = true
                setOnClickListener { action() }
            }
            item.addView(TextView(this).apply {
                text = icon; textSize = 20f; gravity = Gravity.CENTER
                setTextColor(if (selected) cyan else Color.LTGRAY)
            }, matchWrap())
            item.addView(TextView(this).apply {
                text = label; textSize = 10f; gravity = Gravity.CENTER
                setTextColor(if (selected) cyan else Color.LTGRAY)
            }, matchWrap())
            nav.addView(item, LinearLayout.LayoutParams(0, -2, 1f))
        }
        navItem("⌂", "Home", true) { showHome() }
        navItem("▣", "Files", false) { showOutputs() }
        navItem("⚙", "Settings", false) { showSettings() }
        root.addView(nav, LinearLayout.LayoutParams(-1, -2).apply {
            setMargins(dp(12), dp(4), dp(12), dp(6))
        })
        attachBanner(root)
        setContentView(root)
    }

    private fun showSettings() {
        activeTool = "settings"
        val root = baseRoot()
        val body = column().apply { setPadding(dp(18), dp(16), dp(18), dp(18)) }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(button("‹", panel2) { showHome() }, LinearLayout.LayoutParams(dp(54), dp(48)))
        header.addView(TextView(this).apply {
            text = "Settings"; textSize = 24f; setTextColor(Color.WHITE); setPadding(dp(12), 0, 0, 0)
        }, matchWrap())
        body.addView(header, matchWrap())
        body.addView(TextView(this).apply {
            text = "AUDIO CONVERTER AND EDITOR"
            textSize = 17f; setTextColor(cyan); setPadding(0, dp(26), 0, dp(8))
        }, matchWrap())
        body.addView(TextView(this).apply {
            text = "Version 1.1\nCreate and edit audio on your device."
            textSize = 13f; setTextColor(Color.LTGRAY)
        }, matchWrap())
        body.addView(button("PRIVACY POLICY", panel2) {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://sites.google.com/view/droptechdevelopers/audio-converter-and-editor-button?authuser=1")))
        }, buttonParams())
        body.addView(button("BACK TO HOME", purple) { showHome() }, buttonParams())
        root.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        attachBanner(root)
        setContentView(root)
    }

    private fun addTileGrid(parent: LinearLayout, items: List<Triple<String, String, String>>) {
        items.chunked(2).forEachIndexed { rowIndex, rowItems ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            rowItems.forEachIndexed { colIndex, item ->
                val color = when ((rowIndex + colIndex) % 4) { 0 -> Color.rgb(8, 61, 100); 1 -> Color.rgb(54, 28, 95); 2 -> Color.rgb(7, 66, 92); else -> Color.rgb(35, 44, 112) }
                val tile = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    setPadding(dp(8), dp(18), dp(8), dp(18))
                    background = rounded(color, 20)
                    isClickable = true
                    isFocusable = true
                    setOnClickListener { openTool(item.third, item.second) }
                }
                tile.addView(TextView(this).apply {
                    text = item.first
                    textSize = 30f
                    gravity = Gravity.CENTER
                    setTextColor(when (item.third) { "trim" -> green; "volume" -> cyan; "video" -> Color.rgb(235, 92, 228); else -> blue })
                }, matchWrap())
                tile.addView(TextView(this).apply {
                    text = item.second
                    textSize = 16f
                    gravity = Gravity.CENTER
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setTextColor(Color.WHITE)
                    setPadding(0, dp(10), 0, 0)
                }, matchWrap())
                row.addView(tile, LinearLayout.LayoutParams(0, dp(112), 1f).apply {
                    if (colIndex == 0) marginEnd = dp(7) else marginStart = dp(7)
                })
            }
            if (rowItems.size == 1) row.addView(View(this), LinearLayout.LayoutParams(0, dp(112), 1f))
            parent.addView(row, matchWrap())
            if (rowIndex < 2) parent.addView(View(this), LinearLayout.LayoutParams(1, dp(10)))
        }
    }

    private fun openTool(tool: String, title: String) {
        if (tool == "multitrack") { showMultiTrackStudio(); return }
        activeTool = tool
        val root = baseRoot()
        val scroll = ScrollView(this)
        val body = column().apply { setPadding(dp(18), dp(12), dp(18), dp(18)) }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(button("‹", panel2) { stopPlayer(); showHome() }, LinearLayout.LayoutParams(dp(54), dp(48)))
        header.addView(TextView(this).apply {
            text = title
            textSize = 23f
            setTextColor(Color.WHITE)
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(dp(12), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        body.addView(header, matchWrap())
        body.addView(TextView(this).apply {
            text = when (tool) {
                "video" -> "Extract audio from a video and choose your output settings."
                "convert" -> "Convert an audio file into another supported format."
                "trim", "split" -> "Choose the section of audio you want to keep."
                "compress" -> "Create a smaller audio file with your chosen quality."
                "volume" -> "Increase the audio volume before exporting."
                "voice" -> "Change the character of a voice with selectable audio effects."
                "equalizer" -> "Shape the sound with a quick equalizer preset."
                "ringtone" -> "Select a short section and export it as a ringtone."
                else -> "Edit your audio."
            }
            textSize = 14f
            setTextColor(Color.LTGRAY)
            setPadding(0, dp(12), 0, dp(14))
        }, matchWrap())
        fileLabel = TextView(this).apply {
            text = selectedUri?.let { "Selected: $selectedName" } ?: "No file selected"
            textSize = 14f
            setTextColor(Color.WHITE)
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = rounded(panel, 14)
        }
        body.addView(fileLabel, matchWrap())
        body.addView(button(if (tool == "video") "＋  SELECT VIDEO" else "＋  SELECT AUDIO", blue) { chooseFile() }, buttonParams())
        if (tool == "video") {
            section(body, "VIDEO PREVIEW")
            val preview = VideoView(this).apply { setBackgroundColor(Color.BLACK) }
            val controller = MediaController(this)
            controller.setAnchorView(preview)
            preview.setMediaController(controller)
            videoPreview = preview
            body.addView(preview, LinearLayout.LayoutParams(-1, dp(220)).apply { topMargin = dp(4); bottomMargin = dp(10) })
            selectedUri?.let { videoPreview?.setVideoURI(it) }
            section(body, "OUTPUT SETTINGS")
            body.addView(TextView(this).apply {
                text = "Extract the soundtrack and export it as MP3, WAV, M4A or FLAC. Choose quality and file name when you convert."
                textSize = 12f; setTextColor(Color.LTGRAY); setPadding(0, 0, 0, dp(8))
            }, matchWrap())
        }
        if (tool == "trim" || tool == "split" || tool == "ringtone") {
            section(body, if (tool == "ringtone") "RINGTONE LENGTH" else "TRIM RANGE")
            val times = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            startTime = edit("Start (seconds)")
            endTime = edit("End (seconds)")
            times.addView(startTime, LinearLayout.LayoutParams(0, dp(50), 1f).apply { marginEnd = dp(5) })
            times.addView(endTime, LinearLayout.LayoutParams(0, dp(50), 1f).apply { marginStart = dp(5) })
            body.addView(times, matchWrap())
            waveform = AudioWaveformView(this)
            waveform.onSelectionChanged = { start, end ->
                startTime.setText(String.format(Locale.US, "%.2f", start))
                endTime.setText(String.format(Locale.US, "%.2f", end))
            }
            waveform.onPlayheadChanged = { seconds ->
                if (playerPrepared) {
                    try { player?.seekTo((seconds * 1000.0).toInt()) } catch (_: Exception) {}
                }
            }
            body.addView(waveform, LinearLayout.LayoutParams(-1, dp(190)).apply { topMargin = dp(12); bottomMargin = dp(8) })
            selectedUri?.let { loadWaveform(it) }
        }
        if (tool != "video") {
            section(body, "AUDIO PLAYER")
            addPlayer(body)
        }
        if (tool == "voice") {
            section(body, "VOICE EFFECT")
            val effects = Spinner(this).apply {
                adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item,
                    listOf("Normal", "Deep Voice", "High Pitch", "Robot", "Echo"))
                onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onNothingSelected(parent: AdapterView<*>?) {}
                    override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                        selectedVoiceEffect = listOf("Normal", "Deep Voice", "High Pitch", "Robot", "Echo")[position]
                    }
                }
            }
            body.addView(effects, matchWrap())
        }
        if (tool == "equalizer") {
            section(body, "10-BAND GRAPHIC EQUALIZER")
            body.addView(TextView(this).apply {
                text = "Fine-tune ten frequency bands from deep bass to sparkling highs. Adjust each band from −12 dB to +12 dB."
                textSize = 12f
                setTextColor(Color.LTGRAY)
                setPadding(0, 0, 0, dp(10))
            }, matchWrap())
            val frequencies = listOf("31 Hz", "62 Hz", "125 Hz", "250 Hz", "500 Hz", "1 kHz", "2 kHz", "4 kHz", "8 kHz", "16 kHz")
            val eqBars = mutableListOf<SeekBar>()
            val eqReadouts = mutableListOf<TextView>()
            val presets = Spinner(this).apply {
                adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item,
                    listOf("Custom", "Flat", "Bass Boost", "Pop", "Rock", "Jazz", "Classical", "Vocal", "Treble Boost"))
                onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onNothingSelected(parent: AdapterView<*>?) {}
                    override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                        val preset = listOf("Custom", "Flat", "Bass Boost", "Pop", "Rock", "Jazz", "Classical", "Vocal", "Treble Boost")[position]
                        equalizerPreset = preset
                        val values = when (preset) {
                            "Bass Boost" -> listOf(7, 6, 5, 3, 1, 0, 0, 0, 0, 0)
                            "Pop" -> listOf(-1, 1, 4, 5, 2, -1, 1, 3, 4, 2)
                            "Rock" -> listOf(5, 4, 2, -1, -2, 1, 4, 5, 5, 4)
                            "Jazz" -> listOf(4, 3, 1, 2, -2, -2, 0, 2, 3, 4)
                            "Classical" -> listOf(5, 4, 3, 2, -1, -1, 0, 2, 3, 4)
                            "Vocal" -> listOf(-2, -1, 1, 3, 5, 5, 4, 2, 0, -1)
                            "Treble Boost" -> listOf(0, 0, 0, 0, 0, 1, 3, 5, 7, 7)
                            else -> List(10) { 0 }
                        }
                        equalizerGains.indices.forEach { i ->
                            equalizerGains[i] = values[i]
                            if (eqBars.size > i) eqBars[i].progress = values[i] + 12
                            if (eqReadouts.size > i) eqReadouts[i].text = "${values[i]} dB"
                        }
                    }
                }
            }
            val presetCard = column().apply { setPadding(dp(14), dp(12), dp(14), dp(12)); background = rounded(panel, 16) }
            presetCard.addView(TextView(this).apply { text = "SOUND PROFILE"; textSize = 11f; letterSpacing = 0.1f; setTextColor(cyan) }, matchWrap())
            presetCard.addView(presets, matchWrap())
            body.addView(presetCard, matchWrap())
            body.addView(TextView(this).apply {
                text = "FREQUENCY MIXER     −12 dB  to  +12 dB"
                textSize = 10f; letterSpacing = 0.06f; setTextColor(Color.LTGRAY); setPadding(0, dp(14), 0, dp(6))
            }, matchWrap())
            frequencies.forEachIndexed { index, frequency ->
                val band = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(10), dp(4), dp(10), dp(4))
                    background = rounded(if (index % 2 == 0) Color.rgb(16, 42, 91) else Color.rgb(37, 29, 91), 12)
                }
                band.addView(TextView(this).apply {
                    text = frequency
                    textSize = 12f
                    setTextColor(Color.WHITE)
                }, LinearLayout.LayoutParams(dp(54), -2))
                val slider = SeekBar(this).apply {
                    max = 24
                    progress = 12
                    progressTintList = android.content.res.ColorStateList.valueOf(cyan)
                    thumbTintList = android.content.res.ColorStateList.valueOf(blue)
                    setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                            equalizerGains[index] = progress - 12
                            if (eqReadouts.size > index) eqReadouts[index].text = "${progress - 12} dB"
                        }
                        override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                        override fun onStopTrackingTouch(seekBar: SeekBar?) {}
                    })
                }
                band.addView(slider, LinearLayout.LayoutParams(0, dp(42), 1f))
                val readout = TextView(this).apply {
                    text = "0 dB"
                    textSize = 11f
                    gravity = Gravity.END
                    setTextColor(cyan)
                }
                band.addView(readout, LinearLayout.LayoutParams(dp(48), -2))
                eqBars.add(slider)
                eqReadouts.add(readout)
                body.addView(band, LinearLayout.LayoutParams(-1, dp(54)).apply { bottomMargin = dp(5) })
            }
            body.addView(button("RESET ALL BANDS TO FLAT", panel2) {
                equalizerGains.indices.forEach { i ->
                    equalizerGains[i] = 0
                    eqBars[i].progress = 12
                    eqReadouts[i].text = "0 dB"
                }
                presets.setSelection(1)
            }, buttonParams())
        }
        if (tool == "volume") {
            section(body, "VOLUME BOOST")
            val volume = SeekBar(this).apply { max = 300; progress = 150 }
            val volLabel = TextView(this).apply { text = "150% volume"; setTextColor(Color.WHITE) }
            volume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) { volLabel.text = "${progress.coerceAtLeast(10)}% volume" }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
            body.addView(volLabel, matchWrap())
            body.addView(volume, matchWrap())
            body.addView(button("CONVERT", purple) { showSaveSettingsDialog(volume.progress) }, buttonParams())
        } else {
            body.addView(button(
                when (tool) {
                    "trim", "split" -> "EXPORT SELECTED SECTION"
                    "ringtone" -> "CREATE RINGTONE"
                    "voice" -> "APPLY VOICE EFFECT"
                    "equalizer" -> "APPLY EQUALIZER"
                    else -> "CONVERT"
                }, purple) {
                showSaveSettingsDialog()
            }, buttonParams())
        }
        body.addView(button("PLAY LAST SAVED FILE", panel2) { playLast() }, buttonParams())
        status = TextView(this).apply {
            text = "Choose a file to begin."
            textSize = 13f
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER
            setPadding(0, dp(14), 0, dp(8))
        }
        body.addView(status, matchWrap())
        scroll.addView(body)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        attachBanner(root)
        setContentView(root)
    }


    private fun showMultiTrackStudio() {
        activeTool = "multitrack"
        stopPlayer()
        val root = baseRoot()
        val scroll = ScrollView(this)
        val body = column().apply { setPadding(dp(16), dp(12), dp(16), dp(18)) }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(button("‹", panel2) { showHome() }, LinearLayout.LayoutParams(dp(54), dp(48)))
        header.addView(TextView(this).apply {
            text = "Multi-Track Studio"; textSize = 22f; setTextColor(Color.WHITE)
            setTypeface(null, android.graphics.Typeface.BOLD); setPadding(dp(10), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        body.addView(header, matchWrap())
        body.addView(TextView(this).apply {
            text = "Layer music, vocals and sound effects. Set each clip's start position and volume, preview the arrangement and export one mixed file."
            textSize = 13f; setTextColor(Color.LTGRAY); setPadding(0, dp(12), 0, dp(14))
        }, matchWrap())
        body.addView(button("＋  ADD AUDIO TRACKS", blue) {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE); type = "audio/*"
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            }
            mixPicker.launch(intent)
        }, buttonParams())
        section(body, "TIMELINE & TRACK CONTROLS")
        mixRows = column()
        body.addView(mixRows, matchWrap())
        renderMixRows()
        body.addView(button("▶  RENDER MIX PREVIEW", panel2) { renderMix(true) }, buttonParams())
        body.addView(button("EXPORT MIX", purple) { showMixExportDialog() }, buttonParams())
        mixStatus = TextView(this).apply {
            text = "Add at least two audio files to create a mix."
            textSize = 13f; gravity = Gravity.CENTER; setTextColor(cyan); setPadding(0, dp(12), 0, dp(8))
        }
        body.addView(mixStatus, matchWrap())
        scroll.addView(body)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        attachBanner(root)
        setContentView(root)
    }

    private fun renderMixRows() {
        val container = mixRows ?: return
        container.removeAllViews()
        mixTracks.forEachIndexed { index, track ->
            val card = column().apply { setPadding(dp(12), dp(10), dp(12), dp(10)); background = rounded(panel, 14) }
            val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            top.addView(TextView(this).apply {
                text = "TRACK ${index + 1}  •  ${track.name}"
                textSize = 13f; setTextColor(Color.WHITE); setTypeface(null, android.graphics.Typeface.BOLD)
            }, LinearLayout.LayoutParams(0, -2, 1f))
            top.addView(button("×", panel2) { mixTracks.remove(track); renderMixRows() }, LinearLayout.LayoutParams(dp(42), dp(38)))
            card.addView(top, matchWrap())
            val offsetText = TextView(this).apply {
                text = "Clip start: ${track.offsetMs / 1000.0}s"; textSize = 12f; setTextColor(cyan)
            }
            card.addView(offsetText, matchWrap())
            val offset = SeekBar(this).apply {
                max = 120; progress = (track.offsetMs / 1000).coerceIn(0, 120)
                progressTintList = android.content.res.ColorStateList.valueOf(blue)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                        track.offsetMs = progress * 1000
                        offsetText.text = "Clip start: ${progress}s"
                    }
                    override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                    override fun onStopTrackingTouch(seekBar: SeekBar?) {}
                })
            }
            card.addView(offset, matchWrap())
            val gainText = TextView(this).apply {
                text = "Track volume: ${track.gain}%"; textSize = 12f; setTextColor(Color.LTGRAY)
            }
            card.addView(gainText, matchWrap())
            val gain = SeekBar(this).apply {
                max = 150; progress = track.gain
                progressTintList = android.content.res.ColorStateList.valueOf(cyan)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                        track.gain = progress; gainText.text = "Track volume: ${progress}%"
                    }
                    override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                    override fun onStopTrackingTouch(seekBar: SeekBar?) {}
                })
            }
            card.addView(gain, matchWrap())
            container.addView(card, matchWrap())
            if (index < mixTracks.lastIndex) container.addView(View(this), LinearLayout.LayoutParams(1, dp(8)))
        }
    }

    private fun showMixExportDialog() {
        if (mixTracks.size < 2) { toast("Add at least two audio tracks."); return }
        val name = EditText(this).apply { setText("My_Mix"); setSingleLine(true); setTextColor(Color.WHITE) }
        val format = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, listOf("MP3", "WAV", "M4A (AAC)", "FLAC"))
        }
        val quality = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, listOf("128 kbps", "192 kbps", "256 kbps", "320 kbps")); setSelection(1)
        }
        val form = column().apply {
            setPadding(dp(18), dp(8), dp(18), dp(8))
            addView(TextView(this@MainActivity).apply { text = "FILE NAME"; setTextColor(Color.LTGRAY) })
            addView(name, matchWrap())
            addView(TextView(this@MainActivity).apply { text = "FORMAT"; setTextColor(Color.LTGRAY) })
            addView(format, matchWrap())
            addView(TextView(this@MainActivity).apply { text = "QUALITY"; setTextColor(Color.LTGRAY) })
            addView(quality, matchWrap())
        }
        val dialog = AlertDialog.Builder(this).setTitle("Export mixed audio").setView(form)
            .setNegativeButton("CANCEL", null).setPositiveButton("EXPORT", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val safe = name.text.toString().trim().replace(Regex("[^A-Za-z0-9 _-]"), "_")
                if (safe.isBlank()) { name.error = "Enter a file name"; return@setOnClickListener }
                val ext = listOf("mp3", "wav", "m4a", "flac")[format.selectedItemPosition]
                val rate = listOf("128k", "192k", "256k", "320k")[quality.selectedItemPosition]
                dialog.dismiss()
                renderMix(false, ext, rate, safe)
            }
        }
        dialog.show()
    }

    private fun renderMix(preview: Boolean, ext: String = "mp3", rate: String = "192k", fileName: String = "My_Mix") {
        if (mixTracks.size < 2) { toast("Add at least two audio tracks."); return }
        val output = File(cacheDir, "mixed_${System.currentTimeMillis()}.${if (preview) "mp3" else ext}")
        val inputs = mutableListOf<File>()
        mixStatus?.text = "Preparing ${mixTracks.size} audio tracks…"
        Thread {
            try {
                val args = mutableListOf("-y")
                mixTracks.forEachIndexed { i, track ->
                    val input = File(cacheDir, "mix_input_${System.currentTimeMillis()}_$i")
                    contentResolver.openInputStream(track.uri).use { stream ->
                        requireNotNull(stream) { "Could not read ${track.name}" }
                        FileOutputStream(input).use { stream.copyTo(it) }
                    }
                    inputs.add(input)
                    args.addAll(listOf("-i", input.absolutePath))
                }
                val filters = mutableListOf<String>()
                mixTracks.forEachIndexed { i, track ->
                    filters.add("[$i:a]adelay=${track.offsetMs}|${track.offsetMs},volume=${track.gain / 100.0}[a$i]")
                }
                val labels = mixTracks.indices.joinToString("") { "[a$it]" }
                filters.add("${labels}amix=inputs=${mixTracks.size}:duration=longest:dropout_transition=2:normalize=0[out]")
                args.addAll(listOf("-filter_complex", filters.joinToString(";"), "-map", "[out]"))
                when (if (preview) "mp3" else ext) {
                    "wav" -> args.addAll(listOf("-codec:a", "pcm_s16le"))
                    "m4a" -> args.addAll(listOf("-codec:a", "aac", "-b:a", rate))
                    "flac" -> args.addAll(listOf("-codec:a", "flac"))
                    else -> args.addAll(listOf("-codec:a", "libmp3lame", "-b:a", if (preview) "192k" else rate))
                }
                args.add(output.absolutePath)
                val session = FFmpegKit.execute(args.joinToString(" ") { quote(it) })
                if (!ReturnCode.isSuccess(session.returnCode) || !output.exists() || output.length() == 0L) {
                    throw IllegalStateException("The audio tracks could not be mixed.")
                }
                runOnUiThread {
                    if (preview) {
                        val previewPlayer = MediaPlayer()
                        previewPlayer.setDataSource(output.absolutePath)
                        previewPlayer.setOnPreparedListener { it.start(); mixStatus?.text = "Playing mix preview." }
                        previewPlayer.setOnCompletionListener { it.release(); output.delete(); mixStatus?.text = "Preview finished." }
                        previewPlayer.prepareAsync()
                    } else {
                        pendingExport?.delete()
                        pendingExport = output
                        pendingOutputName = fileName
                        mixStatus?.text = "Mix ready. Complete the save step."
                        showPendingSaveDialog("$fileName.$ext")
                    }
                }
            } catch (e: Exception) {
                output.delete()
                runOnUiThread { mixStatus?.text = "Mix failed: ${e.message ?: "Check your audio files."}" }
            } finally { inputs.forEach { it.delete() } }
        }.start()
    }

    private fun addPlayer(parent: LinearLayout) {
        val card = column().apply { setPadding(dp(14), dp(12), dp(14), dp(12)); background = rounded(panel, 18) }
        playerTime = TextView(this).apply { text = "00:00 / 00:00"; textSize = 14f; setTextColor(Color.WHITE); gravity = Gravity.CENTER }
        playerSeek = SeekBar(this).apply {
            max = 1
            progress = 0
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser && playerPrepared) {
                        try {
                            player?.seekTo(progress)
                            playerTime.text = "${timeText(progress)} / ${timeText(player?.duration ?: 0)}"
                            if (::waveform.isInitialized) waveform.setPlayhead(progress / 1000.0)
                        } catch (_: Exception) {}
                    }
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        card.addView(playerTime, matchWrap())
        card.addView(playerSeek, matchWrap())
        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        controls.addView(button("− 5s", panel2) { jumpPlayer(-5000) }, LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginEnd = dp(4) })
        controls.addView(button("▶ / ❚❚", blue) { togglePlayer() }, LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginStart = dp(4); marginEnd = dp(4) })
        controls.addView(button("+ 5s", panel2) { jumpPlayer(5000) }, LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginStart = dp(4) })
        card.addView(controls, matchWrap())
        parent.addView(card, matchWrap())
    }

    private fun showSaveSettingsDialog(volumePercent: Int = 100) {
        if (selectedUri == null) { toast("Select an audio or video file first."); return }
        val form = column().apply { setPadding(dp(20), dp(8), dp(20), dp(4)) }
        val name = EditText(this).apply {
            setText(selectedName.substringBeforeLast('.', selectedName).replace(Regex("[^A-Za-z0-9 _-]"), "_") + "_edited")
            hint = "File name"
            setSingleLine(true)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.LTGRAY)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = rounded(panel2, 12)
        }
        val formatSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, listOf("MP3", "WAV", "M4A (AAC)", "FLAC"))
        }
        val qualitySpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, listOf("Small • 128 kbps", "Standard • 192 kbps", "High • 256 kbps", "Best • 320 kbps"))
            setSelection(1)
        }
        form.addView(TextView(this).apply { text = "FILE NAME"; setTextColor(Color.LTGRAY); textSize = 12f }, matchWrap())
        form.addView(name, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(6); bottomMargin = dp(12) })
        form.addView(TextView(this).apply { text = "OUTPUT FORMAT"; setTextColor(Color.LTGRAY); textSize = 12f }, matchWrap())
        form.addView(formatSpinner, matchWrap())
        form.addView(TextView(this).apply { text = "QUALITY"; setTextColor(Color.LTGRAY); textSize = 12f; setPadding(0, dp(12), 0, 0) }, matchWrap())
        form.addView(qualitySpinner, matchWrap())
        val dialog = AlertDialog.Builder(this)
            .setTitle("Save as")
            .setView(form)            .setNegativeButton("CANCEL", null)
            .setPositiveButton("CONVERT", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(blue)
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(Color.LTGRAY)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val fileName = name.text.toString().trim().replace(Regex("[^A-Za-z0-9 _-]"), "_").trim()
                if (fileName.isBlank()) { name.error = "Enter a file name"; return@setOnClickListener }
                val ext = when (formatSpinner.selectedItemPosition) { 1 -> "wav"; 2 -> "m4a"; 3 -> "flac"; else -> "mp3" }
                val kbps = when (qualitySpinner.selectedItemPosition) { 0 -> "128k"; 1 -> "192k"; 2 -> "256k"; else -> "320k" }
                dialog.dismiss()
                processExport(ext, kbps, fileName, volumePercent)
            }
        }
        dialog.show()
    }

    private fun processExport(ext: String, kbps: String, fileName: String, volumePercent: Int) {
        val uri = selectedUri ?: return
        val tool = activeTool
        val input = File(cacheDir, "input_${System.currentTimeMillis()}_${safeName(selectedName)}")
        val output = File(cacheDir, "export_${System.currentTimeMillis()}.$ext")
        pendingOutputName = fileName
        status.text = "Preparing your export…"
        Thread {
            try {
                contentResolver.openInputStream(uri).use { stream ->
                    requireNotNull(stream) { "Could not open selected file." }
                    FileOutputStream(input).use { stream.copyTo(it) }
                }
                val args = mutableListOf("-y")
                if (tool == "trim" || tool == "split" || tool == "ringtone") {
                    val start = if (::startTime.isInitialized) startTime.text.toString().toDoubleOrNull() ?: 0.0 else 0.0
                    val end = if (::endTime.isInitialized) endTime.text.toString().toDoubleOrNull() else null
                    if (start < 0 || end == null || end <= start) {
                        runOnUiThread { status.text = "Enter a valid start and end time."; toast("End time must be greater than start time.") }
                        input.delete()
                        return@Thread
                    }
                    args.addAll(listOf("-ss", start.toString(), "-i", input.absolutePath, "-t", (end - start).toString()))
                } else args.addAll(listOf("-i", input.absolutePath))
                args.addAll(listOf("-vn"))
                when (ext) {
                    "mp3" -> args.addAll(listOf("-codec:a", "libmp3lame", "-b:a", kbps))
                    "wav" -> args.addAll(listOf("-codec:a", "pcm_s16le"))
                    "m4a" -> args.addAll(listOf("-codec:a", "aac", "-b:a", kbps))
                    "flac" -> args.addAll(listOf("-codec:a", "flac"))
                }
                if (tool == "compress") args.addAll(listOf("-ac", "2", "-ar", "44100"))
                val filters = mutableListOf<String>()
                if (tool == "volume") filters.add("volume=${(volumePercent.coerceAtLeast(10) / 100.0)}")
                if (tool == "voice") {
                    when (selectedVoiceEffect) {
                        "Deep Voice" -> filters.add("asetrate=44100*0.82,aresample=44100")
                        "High Pitch" -> filters.add("asetrate=44100*1.22,aresample=44100")
                        "Robot" -> filters.add("aecho=0.8:0.88:60:0.4")
                        "Echo" -> filters.add("aecho=0.8:0.7:900:0.35")
                    }
                }
                if (tool == "equalizer") {
                    val frequencies = listOf(31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)
                    frequencies.forEachIndexed { index, frequency ->
                        val gain = equalizerGains[index]
                        if (gain != 0) filters.add("equalizer=f=$frequency:width_type=o:width=1:g=$gain")
                    }
                }
                if (filters.isNotEmpty()) args.addAll(listOf("-af", filters.joinToString(",")))
                args.add(output.absolutePath)
                runOnUiThread { status.text = "Converting… please keep the app open." }
                val session = FFmpegKit.execute(args.joinToString(" ") { quote(it) })
                if (ReturnCode.isSuccess(session.returnCode) && output.exists() && output.length() > 0) {
                    runOnUiThread {
                        pendingExport?.delete()
                        pendingExport = output
                        status.text = "Export ready: $fileName.$ext. Save from the popup to finish."
                        toast("Conversion complete. Your Save File popup is ready.")
                        showPendingSaveDialog("$fileName.$ext")
                    }
                } else {
                    output.delete()
                    runOnUiThread { status.text = "Could not process this file. Try another format."; toast("Conversion failed. Try MP3 or M4A.") }
                }
            } catch (e: Exception) {
                output.delete()
                runOnUiThread { status.text = "Error: ${e.message ?: "Could not read this file."}" }
            } finally {
                input.delete()
            }
        }.start()
    }

    private fun showPendingSaveDialog(fileLabelText: String = "Your exported audio") {
        val file = pendingExport
        if (file == null || !file.exists() || file.length() <= 0L) return
        AlertDialog.Builder(this)
            .setTitle("Your export is ready")
            .setMessage("$fileLabelText is ready. Tap SAVE FILE and complete the rewarded ad to save it to Downloads/Audio Converter and Editor. Your file will not be saved unless the reward is earned.")
            .setPositiveButton("SAVE FILE") { _, _ -> savePendingExport() }
            .setNegativeButton("LATER", null)
            .show()
            .apply {
                getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(blue)
                getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(Color.LTGRAY)
            }
    }

    private fun savePendingExport() {
        val file = pendingExport
        if (file == null || !file.exists() || file.length() <= 0L) {
            toast("Convert a file first, then use Save File.")
            return
        }
        val ad = rewarded
        if (ad == null) {
            status.text = "Your export is safe and ready. The rewarded ad is loading."
            loadRewarded()
            AlertDialog.Builder(this)
                .setTitle("Ad is loading")
                .setMessage("Your export has not been deleted. Wait a moment, then tap TRY AGAIN to load the rewarded ad and save your file.")
                .setPositiveButton("TRY AGAIN") { _, _ -> savePendingExport() }
                .setNegativeButton("CANCEL", null)
                .show()
            return
        }
        rewardAndSave(file, ad)
    }

    private fun rewardAndSave(file: File, ad: RewardedAd) {
        rewarded = null
        var earned = false
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                loadRewarded()
                if (!earned) {
                    status.text = "Ad not completed. Your export is still ready and was not saved."
                    toast("Complete the rewarded ad to save.")
                    showPendingSaveDialog(file.name)
                }
            }
            override fun onAdFailedToShowFullScreenContent(error: AdError) {
                loadRewarded()
                status.text = "Ad could not be shown. Your export is still ready."
                toast("Ad could not be shown. Try Save File again shortly.")
                showPendingSaveDialog(file.name)
            }
        }
        ad.show(this) {
            earned = true
            Thread {
                try {
                    val saved = saveDownload(file, pendingOutputName)
                    runOnUiThread {
                        lastSavedUri = saved.first
                        if (pendingExport == file) pendingExport = null
                        status.text = "Saved to Downloads/Audio Converter and Editor: ${saved.second}"
                        file.delete()
                        toast("Saved: ${saved.second}")
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        status.text = "Could not save file: ${e.message ?: "Storage error"}. Your export is still available."
                        showPendingSaveDialog(file.name)
                    }
                }
            }.start()
        }
    }

    private fun saveDownload(file: File, baseName: String): Pair<Uri, String> {
        val ext = file.extension.lowercase(Locale.ROOT)
        val mime = when (ext) { "wav" -> "audio/wav"; "m4a" -> "audio/mp4"; "flac" -> "audio/flac"; else -> "audio/mpeg" }
        val safe = baseName.replace(Regex("[^A-Za-z0-9 _-]"), "_").trim().ifBlank { "Audio" }
        val name = "$safe.$ext"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Audio Converter and Editor")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.Downloads.EXTERNAL_CONTENT_URI else MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val uri = contentResolver.insert(collection, values) ?: error("Could not create the download file.")
        try {
            contentResolver.openOutputStream(uri).use { out ->
                requireNotNull(out) { "Could not open output file." }
                FileInputStream(file).use { it.copyTo(out) }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            }
        } catch (e: Exception) {
            contentResolver.delete(uri, null, null)
            throw e
        }
        return Pair(uri, name)
    }

    private fun loadWaveform(uri: Uri) {
        if (!::waveform.isInitialized) return
        Thread {
            val input = File(cacheDir, "wave_in_${System.currentTimeMillis()}.media")
            val pcm = File(cacheDir, "wave_out_${System.currentTimeMillis()}.pcm")
            try {
                contentResolver.openInputStream(uri).use { stream ->
                    requireNotNull(stream) { "Could not open selected file." }
                    FileOutputStream(input).use { stream.copyTo(it) }
                }
                val command = listOf("-y", "-i", input.absolutePath, "-vn", "-ac", "1", "-ar", "8000", "-f", "s16le", pcm.absolutePath).joinToString(" ") { quote(it) }
                val session = FFmpegKit.execute(command)
                if (!ReturnCode.isSuccess(session.returnCode) || !pcm.exists() || pcm.length() < 2L) {
                    runOnUiThread { if (::status.isInitialized) status.text = "Waveform unavailable. You can still enter crop times." }
                    return@Thread
                }
                val bytes = pcm.readBytes()
                val samples = bytes.size / 2
                val perBar = maxOf(1, samples / 180)
                val levels = ArrayList<Float>()
                var i = 0
                while (i < samples) {
                    var peak = 0
                    val stop = minOf(samples, i + perBar)
                    var j = i
                    while (j < stop) {
                        val raw = ((bytes[j * 2 + 1].toInt() shl 8) or (bytes[j * 2].toInt() and 0xff)).toShort().toInt()
                        peak = maxOf(peak, abs(raw))
                        j++
                    }
                    levels.add(peak / 32768f)
                    i = stop
                }
                val duration = samples / 8000.0
                runOnUiThread {
                    if (::waveform.isInitialized) waveform.setWaveform(levels, duration)
                    if (::status.isInitialized) status.text = "Waveform ready. Drag the handles to choose the section."
                }
            } catch (_: Exception) {
                runOnUiThread { if (::status.isInitialized) status.text = "Could not build waveform. Enter crop times manually." }
            } finally { input.delete(); pcm.delete() }
        }.start()
    }

    private fun chooseFile() {
        val openPicker = {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = if (activeTool == "video") "video/*" else "audio/*"
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            }
            picker.launch(intent)
        }
        val ad = interstitial
        if (ad != null && System.currentTimeMillis() - lastInterstitialAt > 180000L) {
            interstitial = null
            lastInterstitialAt = System.currentTimeMillis()
            ad.fullScreenContentCallback = object : FullScreenContentCallback() {
                override fun onAdDismissedFullScreenContent() { loadInterstitial(); openPicker() }
                override fun onAdFailedToShowFullScreenContent(error: AdError) { loadInterstitial(); openPicker() }
            }
            ad.show(this)
        } else openPicker()
    }

    private fun preparePlayer(uri: Uri) {
        try {
            player?.release()
            playerPrepared = false
            player = MediaPlayer().apply {
                setDataSource(this@MainActivity, uri)
                setOnPreparedListener { mp ->
                    playerPrepared = true
                    lastDurationMs = mp.duration
                    if (::playerSeek.isInitialized) { playerSeek.max = mp.duration.coerceAtLeast(1); playerSeek.progress = 0 }
                    if (::playerTime.isInitialized) playerTime.text = "00:00 / ${timeText(mp.duration)}"
                    if (::waveform.isInitialized) waveform.setPlayhead(0.0)
                    if (::status.isInitialized) status.text = "Player ready. Press play to listen."
                }
                setOnCompletionListener { mp ->                    if (::status.isInitialized) status.text = "Playback finished."
                    try { mp.seekTo(0); if (::playerSeek.isInitialized) playerSeek.progress = 0 } catch (_: Exception) {}
                }
                prepareAsync()
            }
            handler.removeCallbacks(tick)
            handler.post(tick)
        } catch (_: Exception) { toast("Unable to play this file.") }
    }

    private fun togglePlayer() {
        val p = player
        if (p == null || !playerPrepared) {
            selectedUri?.let { preparePlayer(it) } ?: toast("Select a file to play first.")
            return
        }
        try {
            if (p.isPlaying) p.pause() else p.start()
            handler.removeCallbacks(tick)
            handler.post(tick)
        } catch (_: Exception) { toast("Playback is not ready yet.") }
    }

    private fun jumpPlayer(delta: Int) {
        val p = player
        if (p == null || !playerPrepared) { toast("Select a file first."); return }
        try {
            p.seekTo((p.currentPosition + delta).coerceIn(0, p.duration))
            if (::playerTime.isInitialized) playerTime.text = "${timeText(p.currentPosition)} / ${timeText(p.duration)}"
        } catch (_: Exception) {}
    }

    private fun playLast() {
        val uri = lastSavedUri
        if (uri == null) { toast("No file saved in this session yet."); return }
        preparePlayer(uri)
        handler.postDelayed({ if (playerPrepared) { try { player?.start(); handler.post(tick) } catch (_: Exception) {} } }, 400)
    }

    private fun showOutputs() {
        activeTool = "outputs"
        val root = baseRoot()
        val body = column().apply { setPadding(dp(18), dp(18), dp(18), dp(18)) }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(button("‹", panel2) { showHome() }, LinearLayout.LayoutParams(dp(54), dp(48)))
        header.addView(TextView(this).apply { text = "Outputs"; textSize = 25f; setTextColor(Color.WHITE); setPadding(dp(12), 0, 0, 0) }, matchWrap())
        body.addView(header, matchWrap())
        body.addView(TextView(this).apply {
            text = lastSavedUri?.let { "Last saved: $it" } ?: "Your latest saved file will appear here after you export it."
            textSize = 14f
            setTextColor(Color.LTGRAY)
            setPadding(0, dp(18), 0, dp(18))
        }, matchWrap())
        body.addView(button("PLAY LAST SAVED FILE", blue) { playLast() }, buttonParams())
        body.addView(button("OPEN DOWNLOADS", panel2) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW).apply { setDataAndType(MediaStore.Downloads.EXTERNAL_CONTENT_URI, "*/*"); addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
            } catch (_: Exception) { toast("Open your phone's Downloads app to find saved files.") }
        }, buttonParams())
        root.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        attachBanner(root)
        setContentView(root)
    }

    private fun loadInterstitial() {
        InterstitialAd.load(this, "ca-app-pub-1083885756946416/7602676237", AdRequest.Builder().build(),
            object : InterstitialAdLoadCallback() {
                override fun onAdLoaded(ad: InterstitialAd) { interstitial = ad }
                override fun onAdFailedToLoad(error: LoadAdError) { interstitial = null }
            })
    }

    private fun loadRewarded() {
        RewardedAd.load(this, "ca-app-pub-1083885756946416/4114235240", AdRequest.Builder().build(),
            object : RewardedAdLoadCallback() {
                override fun onAdLoaded(ad: RewardedAd) { rewarded = ad }
                override fun onAdFailedToLoad(error: LoadAdError) { rewarded = null }
            })
    }

    private fun loadAppOpen() {
        AppOpenAd.load(this, "ca-app-pub-1083885756946416/2801153579", AdRequest.Builder().build(),
            object : AppOpenAd.AppOpenAdLoadCallback() {
                override fun onAdLoaded(ad: AppOpenAd) { appOpen = ad }
                override fun onAdFailedToLoad(error: LoadAdError) { appOpen = null }
            })
    }

    private fun showAppOpenIfReady() {
        val ad = appOpen ?: return
        if (isFinishing || showingAppOpen || splashVisible) return
        appOpen = null
        showingAppOpen = true
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() { showingAppOpen = false; loadAppOpen() }
            override fun onAdFailedToShowFullScreenContent(error: AdError) { showingAppOpen = false; loadAppOpen() }
        }
        ad.show(this)
    }

    private fun baseRoot() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(bg) }
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun attachBanner(root: LinearLayout) {
        banner = AdView(this).apply {
            adUnitId = "ca-app-pub-1083885756946416/6468935842"
            setAdSize(AdSize.BANNER)
            loadAd(AdRequest.Builder().build())
        }
        root.addView(banner, LinearLayout.LayoutParams(-1, -2))
    }
    private fun section(parent: LinearLayout, label: String) {
        parent.addView(TextView(this).apply {
            text = label
            textSize = 13f
            setTextColor(Color.rgb(99, 201, 255))
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, dp(18), 0, dp(9))
        }, matchWrap())
    }
    private fun button(label: String, color: Int, action: () -> Unit) = Button(this).apply {
        text = label
        textSize = 13f
        setTextColor(Color.WHITE)
        backgroundTintList = android.content.res.ColorStateList.valueOf(color)
        isAllCaps = false
        setOnClickListener { action() }
    }
    private fun buttonParams() = LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(9) }
    private fun matchWrap() = LinearLayout.LayoutParams(-1, -2)
    private fun edit(hintText: String) = EditText(this).apply {
        hint = hintText
        setHintTextColor(Color.LTGRAY)
        setTextColor(Color.WHITE)
        inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        setSingleLine(true)
        setPadding(dp(12), dp(10), dp(12), dp(10))
        background = rounded(panel2, 12)
    }
    private fun rounded(color: Int, radius: Int) = android.graphics.drawable.GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun timeText(ms: Int): String {
        val safe = ms.coerceAtLeast(0) / 1000
        return String.format(Locale.US, "%02d:%02d", safe / 60, safe % 60)
    }
    private fun queryName(uri: Uri): String? = try {
        contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    } catch (_: Exception) { null }
    private fun safeName(value: String) = value.replace(Regex("[^A-Za-z0-9._-]"), "_").take(40)
    private fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    private fun stopPlayer() {
        try { player?.pause() } catch (_: Exception) {}
        try { videoPreview?.pause() } catch (_: Exception) {}
        handler.removeCallbacks(tick)
    }

    override fun onDestroy() {
        stopPlayer()
        player?.release()
        player = null
        pendingExport?.delete()
        pendingExport = null
        if (::banner.isInitialized) banner.destroy()
        super.onDestroy()
    }
}