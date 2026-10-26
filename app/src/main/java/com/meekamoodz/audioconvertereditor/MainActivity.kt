package com.meekamoodz.audioconvertereditor

import android.app.Activity
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
    private var dragStart = false
    var onSelectionChanged: ((Double, Double) -> Unit)? = null

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
        invalidate()
        onSelectionChanged?.invoke(startSeconds, endSeconds)
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
        canvas.drawRect(sx, 0f, ex, h, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(36, 40, 165, 250) })
        val step = w / levels.size.coerceAtLeast(1)
        val bw = (step * 0.48f).coerceAtLeast(1f)
        levels.forEachIndexed { index, level ->
            val x = index * step + (step - bw) / 2f
            val bh = (level.coerceIn(0.025f, 1f) * h * 0.82f).coerceAtLeast(3f)
            barPaint.color = if (x >= sx && x <= ex) Color.rgb(40, 165, 250) else Color.rgb(76, 91, 148)
            canvas.drawRoundRect(x, (h - bh) / 2f, x + bw, (h + bh) / 2f, bw / 2f, bw / 2f, barPaint)
        }
        handlePaint.color = Color.rgb(40, 165, 250)
        canvas.drawRoundRect(sx - 3f, 0f, sx + 3f, h, 3f, 3f, handlePaint)
        handlePaint.color = Color.rgb(249, 115, 22)
        canvas.drawRoundRect(ex - 3f, 0f, ex + 3f, h, 3f, 3f, handlePaint)
        canvas.drawCircle(sx, h * 0.12f, 9f, handlePaint)
        canvas.drawCircle(ex, h * 0.12f, 9f, handlePaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (durationSeconds <= 0.0 || width <= 0) return false
        val x = event.x.coerceIn(0f, width.toFloat())
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                val sx = (startSeconds / durationSeconds).toFloat() * width
                val ex = (endSeconds / durationSeconds).toFloat() * width
                dragStart = abs(x - sx) <= abs(x - ex)
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
        if (dragStart) startSeconds = t.coerceAtMost(endSeconds - 0.05).coerceAtLeast(0.0)
        else endSeconds = t.coerceAtLeast(startSeconds + 0.05).coerceAtMost(durationSeconds)
        invalidate()
        onSelectionChanged?.invoke(startSeconds, endSeconds)
    }

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
    private val handler = Handler(Looper.getMainLooper())
    private var updatingSeek = false
    private var lastDurationMs = 0

    private val picker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) result.data?.data?.let { uri ->
            selectedUri = uri
            selectedName = queryName(uri) ?: "selected_media"
            if (::fileLabel.isInitialized) fileLabel.text = "Selected: $selectedName"
            if (::status.isInitialized) status.text = "Loading audio waveform…"
            try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
            if (::waveform.isInitialized) loadWaveform(uri)
            preparePlayer(uri)
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
                handler.postDelayed(this, 350)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        MobileAds.initialize(this) {}
        loadInterstitial()
        loadRewarded()
        loadAppOpen()
        showHome()
    }

    override fun onStart() {
        super.onStart()
        showAppOpenIfReady()
    }

    private fun showHome() {
        activeTool = "home"
        val root = baseRoot()
        val scroll = ScrollView(this)
        val body = column().apply { setPadding(dp(18), dp(16), dp(18), dp(18)) }
        body.addView(TextView(this).apply {
            text = "♫"
            textSize = 48f
            gravity = Gravity.CENTER
            setTextColor(blue)
        }, matchWrap())
        body.addView(TextView(this).apply {
            text = "Audio Converter\nand Editor"
            textSize = 29f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setTypeface(null, android.graphics.Typeface.BOLD)
        }, matchWrap())
        body.addView(TextView(this).apply {
            text = "CREATE • EDIT • CONVERT • LISTEN"
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(Color.LTGRAY)
            setPadding(0, dp(8), 0, dp(22))
        }, matchWrap())
        section(body, "AUDIO EDITOR")
        val first = listOf(
            Triple("✂", "Trim Audio", "trim"),
            Triple("◖))", "Volume Booster", "volume"),
            Triple("⇄", "Merge Audio", "merge"),
            Triple("♫", "Mix Audio", "mix")
        )
        addTileGrid(body, first)
        section(body, "AUDIO PROCESSING")
        val second = listOf(
            Triple("▶♫", "Video to MP3", "video"),
            Triple("⇄", "Format Converter", "convert"),
            Triple("▤", "Compressor", "compress"),
            Triple("Ⅱ", "Split Audio", "split")
        )
        addTileGrid(body, second)
        section(body, "YOUR FILES")
        body.addView(button("OUTPUTS  •  SAVED AUDIO", panel2) { showOutputs() }, buttonParams())
        body.addView(button("PRIVACY POLICY", panel2) {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://sites.google.com/view/droptechdevelopers/audio-converter-and-editor-button?authuser=1")))
        }, buttonParams())
        body.addView(TextView(this).apply {
            text = "Every exported file must be saved through the rewarded-ad step."
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(Color.LTGRAY)
            setPadding(0, dp(18), 0, dp(4))
        }, matchWrap())
        scroll.addView(body)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
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
                "merge" -> "Choose an audio file to prepare for a merge workflow."
                "mix" -> "Choose an audio file to prepare for a mix workflow."
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
        body.addView(button("＋  SELECT AUDIO OR VIDEO", blue) { chooseFile() }, buttonParams())
        if (tool == "trim" || tool == "split") {
            section(body, "TRIM RANGE")
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
            body.addView(waveform, LinearLayout.LayoutParams(-1, dp(190)).apply { topMargin = dp(12); bottomMargin = dp(8) })
            selectedUri?.let { loadWaveform(it) }
        }
        section(body, "PLAYER")
        addPlayer(body)
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
            body.addView(button(if (tool == "trim" || tool == "split") "CONVERT SELECTED SECTION" else "CONVERT", purple) {
                if (tool == "merge" || tool == "mix") {
                    toast("This tool needs a second audio file. Choose another tool for now.")
                } else showSaveSettingsDialog()
            }, buttonParams())
        }
        body.addView(button("SAVE EXPORTED FILE  •  WATCH REWARDED AD", green) { savePendingExport() }, buttonParams())
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

    private fun addPlayer(parent: LinearLayout) {
        val card = column().apply { setPadding(dp(14), dp(12), dp(14), dp(12)); background = rounded(panel, 18) }
        playerTime = TextView(this).apply { text = "00:00 / 00:00"; textSize = 14f; setTextColor(Color.WHITE); gravity = Gravity.CENTER }
        playerSeek = SeekBar(this).apply {
            max = 1
            progress = 0
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser && playerPrepared) {
                        try { player?.seekTo(progress); playerTime.text = "${timeText(progress)} / ${timeText(player?.duration ?: 0)}" } catch (_: Exception) {}
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
        controls.addView(button("▶ / ❚❚", blue) { togglePlayer() }, LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginHorizontal = dp(4) })
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
            .setView(form)
            .setNegativeButton("CANCEL", null)
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
                if (tool == "trim" || tool == "split") {
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
                if (tool == "volume") args.addAll(listOf("-af", "volume=${(volumePercent.coerceAtLeast(10) / 100.0)}"))
                args.add(output.absolutePath)
                runOnUiThread { status.text = "Converting… please keep the app open." }
                val session = FFmpegKit.execute(args.joinToString(" ") { quote(it) })
                if (ReturnCode.isSuccess(session.returnCode) && output.exists() && output.length() > 0) {
                    runOnUiThread {
                        pendingExport?.delete()
                        pendingExport = output
                        status.text = "Export ready: $fileName.$ext. Tap Save and complete the rewarded ad."
                        toast("Conversion complete. Tap Save to finish.")
                    }
                } else {
                    output.delete()
                    runOnUiThread { status.text = "Could not process this file. Try another format."; toast("Conversion failed. Try MP3 or M4A.") }
                }
            } catch (e: Exception) {
                output.delete()
                runOnUiThread { status.text = "Error: ${e.message ?: "Could not read this file."}" }
            } finally { input.delete() }
        }.start()
    }

    private fun savePendingExport() {
        val file = pendingExport
        if (file == null || !file.exists() || file.length() <= 0L) {
            toast("Convert a file first, then tap Save.")
            return
        }
        val ad = rewarded
        if (ad == null) {
            status.text = "Rewarded ad is loading. Your export is still ready; try Save again shortly."
            toast("Ad not ready. File has not been saved.")
            loadRewarded()
            return
        }
        pendingExport = null
        rewardAndSave(file, ad)
    }

    private fun rewardAndSave(file: File, ad: RewardedAd) {
        rewarded = null
        var earned = false
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                loadRewarded()
                if (!earned) {
                    status.text = "Ad not completed. File was not saved."
                    toast("Complete the rewarded ad to save.")
                }
            }
            override fun onAdFailedToShowFullScreenContent(error: AdError) {
                loadRewarded()
                status.text = "Ad could not be shown. Please convert again and retry."
                file.delete()
            }
        }
        ad.show(this) {
            earned = true
            Thread {
                try {
                    val saved = saveDownload(file, pendingOutputName)
                    runOnUiThread {
                        lastSavedUri = saved.first
                        status.text = "Saved to Downloads/Audio Converter and Editor: ${saved.second}"
                        toast("Saved: ${saved.second}")
                    }
                } catch (e: Exception) {
                    runOnUiThread { status.text = "Could not save file: ${e.message ?: "Storage error"}" }
                } finally { file.delete() }
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
                type = "*/*"
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
                    if (::status.isInitialized) status.text = "Player ready. Press play to listen."
                }
                setOnCompletionListener { mp ->
                    if (::status.isInitialized) status.text = "Playback finished."
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
        if (isFinishing || showingAppOpen) return
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