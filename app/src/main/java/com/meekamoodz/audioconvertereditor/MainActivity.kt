package com.meekamoodz.audioconvertereditor

import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.graphics.Color
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
import com.google.android.gms.ads.appopen.AppOpenAdLoadCallback
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Locale

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
    private lateinit var banner: AdView
    private var selectedUri: Uri? = null
    private var selectedName = "media"
    private var interstitial: InterstitialAd? = null
    private var rewarded: RewardedAd? = null
    private var appOpen: AppOpenAd? = null
    private var lastInterstitialAt = 0L
    private var lastSavedUri: Uri? = null
    private var player: MediaPlayer? = null
    private var showingAppOpen = false

    private val picker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) result.data?.data?.let { uri ->
            selectedUri = uri
            selectedName = queryName(uri) ?: "selected_media"
            fileLabel.text = "Selected: $selectedName"
            status.text = "Ready. Choose an action below."
            try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
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
        body.addView(button("CONVERT / EXTRACT AUDIO", purple) { process(false, false) }, buttonParams())
        body.addView(button("CROP AUDIO", blue) { process(true, false) }, buttonParams())
        body.addView(button("COMPRESS AUDIO", purple) { process(false, true) }, buttonParams())
        body.addView(button("PLAY LAST EXPORTED FILE", Color.rgb(70, 70, 95)) { playLast() }, buttonParams())
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
            text = "Exporting requires completing a rewarded ad. Files are processed on your device."
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
                        status.text = "Processing complete. Complete the rewarded ad to save."
                        rewardAndSave(output)
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

    private fun rewardAndSave(file: File) {
        val ad = rewarded
        if (ad == null) {
            file.delete()
            status.text = "Rewarded ad is loading. Try exporting again in a moment."
            toast("Ad not ready. The file was not saved; please retry.")
            loadRewarded()
            return
        }
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
            object : AppOpenAdLoadCallback() {
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
        if (::banner.isInitialized) banner.destroy()
        super.onDestroy()
    }
}
