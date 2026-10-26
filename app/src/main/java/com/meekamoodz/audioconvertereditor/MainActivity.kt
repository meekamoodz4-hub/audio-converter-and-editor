package com.meekamoodz.audioconvertereditor

import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.graphics.Color
import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.Gravity
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

private class AudioWaveformView(context: android.content.Context) : View(context) {
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val selectedPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var levels: List<Float> = emptyList()
    private var durationSeconds = 0.0
    private var startSeconds = 0.0
    private var endSeconds = 0.0
    private var dragStart = false
    var onSelectionChanged: ((Double, Double) -> Unit)? = null

    init {
        barPaint.color = Color.rgb(91, 83, 135)
        selectedPaint.color = Color.rgb(139, 92, 246)
        handlePaint.color = Color.rgb(59, 130, 246)
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
        canvas.drawColor(Color.rgb(9, 8, 25))
        if (levels.isEmpty()) {
            textPaint.textAlign = Paint.Align.CENTER
            canvas.drawText("Select a file to load waveform", w / 2f, h / 2f, textPaint)
            return
        }
        val sx = (startSeconds / durationSeconds).toFloat().coerceIn(0f, 1f) * w
        val ex = (endSeconds / durationSeconds).toFloat().coerceIn(0f, 1f) * w
        canvas.drawRect(sx, 0f, ex, h, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(45, 59, 130, 246) })
        val step = w / levels.size.coerceAtLeast(1)
        val barWidth = (step * 0.58f).coerceAtLeast(1f)
        levels.forEachIndexed { index, level ->
            val x = index * step + (step - barWidth) / 2f
            val barH = (level.coerceIn(0.025f, 1f) * h * 0.82f).coerceAtLeast(3f)
            barPaint.color = if (x >= sx && x <= ex) Color.rgb(139, 92, 246) else Color.rgb(78, 78, 120)
            canvas.drawRoundRect(x, (h - barH) / 2f, x + barWidth, (h + barH) / 2f, barWidth / 2f, barWidth / 2f, barPaint)
        }
        handlePaint.color = Color.rgb(59, 130, 246)
        canvas.drawRoundRect(sx - 3f, 0f, sx + 3f, h, 3f, 3f, handlePaint)
        handlePaint.color = Color.rgb(217, 70, 239)
        canvas.drawRoundRect(ex - 3f, 0f, ex + 3f, h, 3f, 3f, handlePaint)
        canvas.drawCircle(sx, h * 0.13f, 9f, handlePaint)
        canvas.drawCircle(ex, h * 0.13f, 9f, handlePaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (durationSeconds <= 0.0 || width <= 0) return false
        val x = event.x.coerceIn(0f, width.toFloat())
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                val startX = (startSeconds / durationSeconds).toFloat() * width
                val endX = (endSeconds / durationSeconds).toFloat() * width
                dragStart = kotlin.math.abs(x - startX) <= kotlin.math.abs(x - endX)
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
        val seconds = (x / width.toFloat()).coerceIn(0f, 1f) * durationSeconds
        if (dragStart) startSeconds = seconds.coerceAtMost(endSeconds - 0.05).coerceAtLeast(0.0)
        else endSeconds = seconds.coerceAtLeast(startSeconds + 0.05).coerceAtMost(durationSeconds)
        invalidate()
        onSelectionChanged?.invoke(startSeconds, endSeconds)
    }

    override fun performClick(): Boolean { super.performClick(); return true }
}

class MainActivity : ComponentActivity() {
    private val purple = Color.rgb(139, 92, 246)
    private val bg = Color.rgb(16, 11, 34)
    private val panel = Color.rgb(31, 23, 59)
    private val blue = Color.rgb(59, 130, 246)
    private lateinit var status: TextView
    private lateinit var fileLabel: TextView
    private lateinit var format: Spinner
    private lateinit var bitrate: Spinner
    private lateinit var startTime: EditText
    private lateinit var endTime: EditText
    private lateinit var waveform: AudioWaveformView
    private lateinit var banner: AdView
    private var selectedUri: Uri? = null
    private var selectedName = "media"
    private var interstitial: InterstitialAd? = null
    private var rewarded: RewardedAd? = null
    private var appOpen: AppOpenAd? = null
    private var lastInterstitialAt = 0L
    private var lastSavedUri: Uri? = null
    private var pendingExport: File? = null
    private var player: MediaPlayer? = null
    private var showingAppOpen = false

    private val picker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) result.data?.data?.let { uri ->
            selectedUri = uri
            selectedName = queryName(uri) ?: "selected_media"
            fileLabel.text = "Selected: $selectedName"
            status.text = "Loading audio waveform…"
            try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
            loadWaveform(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        makeUi()
        MobileAds.initialize(this) {}
        loadInterstitial()
        loadRewarded()
        loadAppOpen()
    }

    override fun onStart() {
        super.onStart()
        showAppOpenIfReady()
    }

    private fun makeUi() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(bg) }
        val scroll = ScrollView(this)
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(18))
        }
        body.addView(TextView(this).apply {
            text = "♫"
            textSize = 44f
            gravity = Gravity.CENTER
            setTextColor(purple)
        }, matchWrap())
        body.addView(TextView(this).apply {
            text = "Audio Converter\nand Editor"
            textSize = 28f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setTypeface(null, android.graphics.Typeface.BOLD)
        }, matchWrap())
        body.addView(TextView(this).apply {
            text = "Convert • Trim • Compress • Listen"
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(Color.LTGRAY)
            setPadding(0, dp(8), 0, dp(18))
        }, matchWrap())
        fileLabel = TextView(this).apply {
            text = "No file selected"
            textSize = 14f
            setTextColor(Color.WHITE)
            setPadding(dp(12), dp(14), dp(12), dp(14))
            setBackgroundColor(panel)
        }
        body.addView(fileLabel, matchWrap())
        body.addView(button("SELECT VIDEO OR AUDIO", blue) { chooseFile() }, buttonParams())
        section(body, "OUTPUT FORMAT")
        format = spinner(listOf("MP3", "WAV", "M4A (AAC)", "FLAC"))
        body.addView(format, matchWrap())
        section(body, "AUDIO QUALITY")
        bitrate = spinner(listOf("128 kbps - smaller", "192 kbps - balanced", "256 kbps - high", "320 kbps - best MP3"))
        body.addView(bitrate, matchWrap())
        section(body, "CROP RANGE (SECONDS)")
        val times = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        startTime = edit("Start e.g. 0")
        endTime = edit("End e.g. 30")
        times.addView(startTime, LinearLayout.LayoutParams(0, dp(50), 1f).apply { marginEnd = dp(5) })
        times.addView(endTime, LinearLayout.LayoutParams(0, dp(50), 1f).apply { marginStart = dp(5) })
        body.addView(times, matchWrap())
        section(body, "WAVEFORM CROP EDITOR")
        body.addView(TextView(this).apply {
            text = "Drag the purple handles to choose the part to keep."
            textSize = 12f
            setTextColor(Color.LTGRAY)
            setPadding(0, 0, 0, dp(6))
        }, matchWrap())
        waveform = AudioWaveformView(this).apply {
            setBackgroundColor(Color.rgb(9, 8, 25))
            onSelectionChanged = { start, end ->
                startTime.setText(String.format(Locale.US, "%.2f", start))
                endTime.setText(String.format(Locale.US, "%.2f", end))
            }
        }
        body.addView(waveform, LinearLayout.LayoutParams(-1, dp(150)).apply { topMargin = dp(4); bottomMargin = dp(8) })
        body.addView(button("CONVERT / EXTRACT AUDIO", purple) { process(false, false) }, buttonParams())
        body.addView(button("CROP AUDIO", blue) { process(true, false) }, buttonParams())
        body.addView(button("COMPRESS AUDIO", purple) { process(false, true) }, buttonParams())
        body.addView(button("SAVE EXPORTED FILE • WATCH REWARDED AD", Color.rgb(22, 163, 74)) { savePendingExport() }, buttonParams())
        body.addView(button("PLAY LAST SAVED FILE", Color.rgb(70, 70, 95)) { playLast() }, buttonParams())
        body.addView(button("PRIVACY POLICY", Color.rgb(70, 70, 95)) {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://sites.google.com/view/droptechdevelopers/audio-converter-and-editor-button?authuser=1")))
        }, buttonParams())
        status = TextView(this).apply {
            text = "Choose a media file to begin."
            textSize = 14f
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER
            setPadding(0, dp(14), 0, dp(8))
        }
        body.addView(status, matchWrap())
        body.addView(TextView(this).apply {
            text = "Create an export, then tap Save and complete the rewarded ad. Each saved export requires its own rewarded ad."
            textSize = 12f
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER
        }, matchWrap())
        scroll.addView(body)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        banner = AdView(this).apply {
            adUnitId = "ca-app-pub-1083885756946416/6468935842"
            setAdSize(AdSize.BANNER)
            loadAd(AdRequest.Builder().build())
        }
        root.addView(banner, LinearLayout.LayoutParams(-1, -2))
        setContentView(root)
    }

    private fun loadWaveform(uri: Uri) {
        Thread {
            val input = File(cacheDir, "wave_in_${System.currentTimeMillis()}.media")
            val pcm = File(cacheDir, "wave_out_${System.currentTimeMillis()}.pcm")
            try {
                contentResolver.openInputStream(uri).use { stream ->
                    requireNotNull(stream) { "Could not open selected file." }
                    FileOutputStream(input).use { stream.copyTo(it) }
                }
                val command = listOf("-y", "-i", input.absolutePath, "-vn", "-ac", "1", "-ar", "8000", "-f", "s16le", pcm.absolutePath)
                    .joinToString(" ") { quote(it) }
                val session = FFmpegKit.execute(command)
                if (!ReturnCode.isSuccess(session.returnCode) || !pcm.exists() || pcm.length() < 2L) {
                    runOnUiThread { status.text = "Waveform unavailable for this file. You can still enter crop times." }
                    return@Thread
                }
                val bytes = pcm.readBytes()
                val totalSamples = bytes.size / 2
                val samplesPerBar = maxOf(1, totalSamples / 180)
                val levels = ArrayList<Float>()
                var i = 0
                while (i < totalSamples) {
                    var peak = 0
                    val stop = minOf(totalSamples, i + samplesPerBar)
                    var j = i
                    while (j < stop) {
                        val raw = ((bytes[j * 2 + 1].toInt() shl 8) or (bytes[j * 2].toInt() and 0xff)).toShort().toInt()
                        peak = maxOf(peak, kotlin.math.abs(raw))
                        j++
                    }
                    levels.add(peak / 32768f)
                    i = stop
                }
                val duration = totalSamples / 8000.0
                runOnUiThread {
                    waveform.setWaveform(levels, duration)
                    startTime.setText("0.00")
                    endTime.setText(String.format(Locale.US, "%.2f", duration))
                    status.text = "Waveform ready. Drag the handles to select your crop."
                }
            } catch (e: Exception) {
                runOnUiThread { status.text = "Could not build waveform. You can still enter crop times." }
            } finally {
                input.delete()
                pcm.delete()
            }
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

    private fun process(crop: Boolean, compress: Boolean) {
        val uri = selectedUri
        if (uri == null) { toast("Select a video or audio file first."); return }
        val ext = when (format.selectedItemPosition) { 1 -> "wav"; 2 -> "m4a"; 3 -> "flac"; else -> "mp3" }
        val input = File(cacheDir, "input_${System.currentTimeMillis()}_${safeName(selectedName)}")
        val output = File(cacheDir, "audio_${System.currentTimeMillis()}.$ext")
        status.text = "Preparing your file…"
        Thread {
            try {
                contentResolver.openInputStream(uri).use { stream ->
                    requireNotNull(stream) { "Could not open the selected file." }
                    FileOutputStream(input).use { stream.copyTo(it) }
                }
                val args = mutableListOf("-y")
                if (crop) {
                    val start = startTime.text.toString().toDoubleOrNull() ?: 0.0
                    val end = endTime.text.toString().toDoubleOrNull()
                    if (start < 0 || end == null || end <= start) {
                        runOnUiThread { status.text = "Enter a valid crop start and end time."; toast("End time must be greater than start time.") }
                        input.delete()
                        return@Thread
                    }
                    args.addAll(listOf("-ss", start.toString(), "-i", input.absolutePath, "-t", (end - start).toString()))
                } else args.addAll(listOf("-i", input.absolutePath))
                args.addAll(listOf("-vn"))
                when (ext) {
                    "mp3" -> args.addAll(listOf("-codec:a", "libmp3lame", "-b:a", chosenBitrate()))
                    "wav" -> args.addAll(listOf("-codec:a", "pcm_s16le"))
                    "m4a" -> args.addAll(listOf("-codec:a", "aac", "-b:a", chosenBitrate()))
                    "flac" -> args.addAll(listOf("-codec:a", "flac"))
                }
                if (compress && ext != "wav" && ext != "flac") args.addAll(listOf("-ac", "2", "-ar", "44100"))
                args.add(output.absolutePath)
                val command = args.joinToString(" ") { quote(it) }
                runOnUiThread { status.text = "Processing audio… please keep the app open." }
                val session = FFmpegKit.execute(command)
                if (ReturnCode.isSuccess(session.returnCode) && output.exists() && output.length() > 0) {
                    runOnUiThread {
                        pendingExport?.delete()
                        pendingExport = output
                        status.text = "Export ready: ${output.name}. Tap SAVE EXPORTED FILE to watch a rewarded ad and save it."
                        toast("Export ready. Tap Save when you want to save this file.")
                    }
                } else {
                    output.delete()
                    runOnUiThread { status.text = "Could not process this file. Try another input or format."; toast("Conversion failed. Try another format.") }
                }
                input.delete()
            } catch (e: Exception) {
                input.delete()
                output.delete()
                runOnUiThread { status.text = "Error: " + (e.message ?: "Could not read this file.") }
            }
        }.start()
    }

    private fun savePendingExport() {
        val file = pendingExport
        if (file == null || !file.exists() || file.length() <= 0L) {
            toast("Create an export first, then tap Save.")
            return
        }
        val ad = rewarded
        if (ad == null) {
            status.text = "Rewarded ad is loading. Your export is kept ready; try Save again shortly."
            toast("Ad not ready yet. Your export has not been saved.")
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
                    file.delete()
                    status.text = "Ad not completed. File was not saved."
                    toast("Complete the rewarded ad to save.")
                }
            }
            override fun onAdFailedToShowFullScreenContent(error: AdError) {
                loadRewarded()
                file.delete()
                status.text = "Ad could not be shown. Please try again."
            }
        }
        ad.show(this) {
            earned = true
            Thread {
                try {
                    val saved = saveDownload(file)
                    runOnUiThread {
                        lastSavedUri = saved.first
                        status.text = "Saved to Downloads/Audio Converter and Editor: " + saved.second
                        toast("Saved: " + saved.second)
                    }
                } catch (e: Exception) {
                    runOnUiThread { status.text = "Could not save file: " + (e.message ?: "Storage error") }
                } finally { file.delete() }
            }.start()
        }
    }

    private fun saveDownload(file: File): Pair<Uri, String> {
        val ext = file.extension.lowercase(Locale.ROOT)
        val mime = when (ext) { "wav" -> "audio/wav"; "m4a" -> "audio/mp4"; "flac" -> "audio/flac"; else -> "audio/mpeg" }
        val name = "Audio_${System.currentTimeMillis()}.$ext"
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
        contentResolver.openOutputStream(uri).use { out ->
            requireNotNull(out) { "Could not open the output file." }
            FileInputStream(file).use { it.copyTo(out) }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        }
        return Pair(uri, name)
    }

    private fun playLast() {
        val uri = lastSavedUri
        if (uri == null) { toast("Export a file first."); return }
        try {
            player?.release()
            player = MediaPlayer().apply {
                setDataSource(this@MainActivity, uri)
                setOnPreparedListener { it.start(); status.text = "Playing last exported file." }
                setOnCompletionListener { status.text = "Playback finished." }
                prepareAsync()
            }
        } catch (_: Exception) { toast("Unable to play this file.") }
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

    private fun chosenBitrate() = when (bitrate.selectedItemPosition) { 0 -> "128k"; 1 -> "192k"; 2 -> "256k"; else -> "320k" }

    private fun spinner(items: List<String>) = Spinner(this).apply {
        adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, items).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        setPadding(dp(8), dp(4), dp(8), dp(4))
        setBackgroundColor(panel)
    }

    private fun edit(hintText: String) = EditText(this).apply {
        hint = hintText
        setHintTextColor(Color.LTGRAY)
        setTextColor(Color.WHITE)
        inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        setSingleLine(true)
        setPadding(dp(12), dp(10), dp(12), dp(10))
        setBackgroundColor(panel)
    }

    private fun section(parent: LinearLayout, text: String) {
        parent.addView(TextView(this).apply {
            this.text = text
            textSize = 12f
            setTextColor(purple)
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, dp(16), 0, dp(6))
        }, matchWrap())
    }

    private fun button(text: String, color: Int, action: () -> Unit) = Button(this).apply {
        this.text = text
        textSize = 13f
        setTextColor(Color.WHITE)
        backgroundTintList = android.content.res.ColorStateList.valueOf(color)
        isAllCaps = false
        setOnClickListener { action() }
    }

    private fun buttonParams() = LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(8) }
    private fun matchWrap() = LinearLayout.LayoutParams(-1, -2)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun queryName(uri: Uri): String? = try {
        contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    } catch (_: Exception) { null }
    private fun safeName(value: String) = value.replace(Regex("[^A-Za-z0-9._-]"), "_").take(40)
    private fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    override fun onDestroy() {
        player?.release()
        player = null
        pendingExport?.delete()
        pendingExport = null
        if (::banner.isInitialized) banner.destroy()
        super.onDestroy()
    }
}
