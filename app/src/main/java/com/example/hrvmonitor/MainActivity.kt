package com.example.hrvmonitor

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.time.LocalDate
import java.util.Locale

class MainActivity : Activity(), HeartRateSensor.Listener {

    private enum class Phase { IDLE, RESTING, MEASURING, DONE }

    companion object {
        private const val REQ_PERMS = 1
        private const val KEY_DEVICE = "device"
        private const val KEY_ATHLETE = "athlete"
        private const val KEY_API = "apiKey"
        private const val KEY_REST = "rest"
        private const val KEY_MEASURE = "measure"
        // Typical protocol (HRV4Training / Elite HRV style): ~1-2 min settling, then 2-3 min recording.
        private const val DEFAULT_REST_S = 120
        private const val DEFAULT_MEASURE_S = 180
    }

    private lateinit var sensor: HeartRateSensor
    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }
    private val handler = Handler(Looper.getMainLooper())
    private val tone: ToneGenerator? = try { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 90) } catch (_: Exception) { null }

    private lateinit var status: TextView
    private lateinit var hrText: TextView
    private lateinit var phaseText: TextView
    private lateinit var resultText: TextView
    private lateinit var deviceList: LinearLayout
    private lateinit var startButton: Button
    private lateinit var uploadButton: Button
    private lateinit var athleteInput: EditText
    private lateinit var apiKeyInput: EditText
    private lateinit var restInput: EditText
    private lateinit var measureInput: EditText

    private var phase = Phase.IDLE
    private var phaseEndsAt = 0L
    private val beats = ArrayList<Beat>()
    private var lastPacketAt = 0L
    private var result: HrvResult? = null

    private val ticker = object : Runnable {
        override fun run() {
            tick()
            if (phase == Phase.RESTING || phase == Phase.MEASURING) handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sensor = HeartRateSensor(this, this)
        buildUi()
        if (hasPerms()) autoConnect() else requestPermissions(perms(), REQ_PERMS)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        sensor.disconnect()
        tone?.release()
        super.onDestroy()
    }

    // ---------- permissions ----------

    private fun perms(): Array<String> =
        if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    private fun hasPerms() = perms().all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode != REQ_PERMS) return
        if (hasPerms()) autoConnect() else status.text = "Bluetooth permission is required."
    }

    private fun autoConnect() {
        val addr = prefs.getString(KEY_DEVICE, null)
        when {
            !sensor.isBluetoothOn -> status.text = "Turn on Bluetooth, then tap \"Find sensor\"."
            addr != null -> { status.text = "Connecting to your sensor…"; sensor.connect(addr) }
            else -> status.text = "Put the strap on (wet the electrodes), then tap \"Find sensor\"."
        }
    }

    // ---------- sensor callbacks ----------

    override fun onDeviceFound(address: String, name: String) {
        deviceList.addView(Button(this).apply {
            text = "Connect: $name"
            isAllCaps = false
            setOnClickListener {
                prefs.edit().putString(KEY_DEVICE, address).apply()
                deviceList.removeAllViews()
                status.text = "Connecting to $name…"
                sensor.connect(address)
            }
        })
    }

    override fun onConnectionChanged(connected: Boolean, message: String) {
        status.text = message
    }

    override fun onHeartRate(bpm: Int, rrMs: List<Double>) {
        hrText.text = "$bpm bpm"
        if (phase != Phase.MEASURING) return
        val now = SystemClock.elapsedRealtime()
        // If notifications stopped for longer than the beats in this packet can explain, beats were lost.
        val gap = lastPacketAt > 0 && now - lastPacketAt > maxOf(3000.0, rrMs.sum() + 1500.0)
        lastPacketAt = now
        rrMs.forEachIndexed { i, rr -> beats.add(Beat(rr, breakBefore = gap && i == 0)) }
    }

    // ---------- measurement flow ----------

    private fun startOrCancel() {
        if (phase == Phase.RESTING || phase == Phase.MEASURING) {
            phase = Phase.IDLE
            phaseText.text = "Cancelled."
            startButton.text = "Start measurement"
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            return
        }
        saveSettings()
        result = null
        resultText.text = ""
        uploadButton.isEnabled = false
        phase = Phase.RESTING
        phaseEndsAt = SystemClock.elapsedRealtime() + seconds(restInput, DEFAULT_REST_S) * 1000L
        startButton.text = "Cancel"
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    private fun tick() {
        val now = SystemClock.elapsedRealtime()
        val left = phaseEndsAt - now
        when (phase) {
            Phase.RESTING -> if (left <= 0) {
                phase = Phase.MEASURING
                beats.clear()
                lastPacketAt = 0
                phaseEndsAt = now + seconds(measureInput, DEFAULT_MEASURE_S) * 1000L
                beep(200)
            } else {
                phaseText.text = "RESTING – lie still, breathe normally\n${mmss(left)} until measurement starts"
            }
            Phase.MEASURING -> if (left <= 0) finish() else {
                phaseText.text = "MEASURING – stay still\n${mmss(left)} left · ${beats.size} beats"
            }
            else -> {}
        }
    }

    private fun finish() {
        phase = Phase.DONE
        startButton.text = "Start measurement"
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        beep(600)
        val r = Hrv.analyze(beats)
        result = r
        if (r == null) {
            phaseText.text = "Done – but not enough clean beats."
            resultText.text = "Got ${beats.size} RR intervals. Check the strap is wet and snug, then retry."
            return
        }
        phaseText.text = "Done ✓"
        resultText.text = String.format(
            Locale.US,
            "RMSSD: %.1f ms  (ln %.2f)\nSDNN: %.1f ms\nResting HR: %.0f bpm\nBeats: %d total, %d clean (%.1f%% artifacts)\nQuality: %s",
            r.rmssd, r.lnRmssd, r.sdnn, r.meanHr, r.totalBeats, r.cleanBeats, r.artifactPercent, r.quality,
        )
        uploadButton.isEnabled = true
    }

    private fun upload() {
        val r = result ?: return
        val key = apiKeyInput.text.toString().trim()
        if (key.isEmpty()) { status.text = "Enter your intervals.icu API key first."; return }
        saveSettings()
        val athlete = athleteInput.text.toString()
        uploadButton.isEnabled = false
        status.text = "Uploading…"
        Thread {
            val msg = try {
                IntervalsIcu.uploadWellness(athlete, key, LocalDate.now(), Math.round(r.meanHr).toInt(), r.rmssd, r.sdnn)
                "Uploaded to intervals.icu ✓"
            } catch (e: Exception) {
                "Upload failed: ${e.message}"
            }
            runOnUiThread {
                status.text = msg
                uploadButton.isEnabled = true
            }
        }.start()
    }

    // ---------- helpers ----------

    private fun saveSettings() {
        prefs.edit()
            .putString(KEY_ATHLETE, athleteInput.text.toString().trim())
            .putString(KEY_API, apiKeyInput.text.toString().trim())
            .putInt(KEY_REST, seconds(restInput, DEFAULT_REST_S))
            .putInt(KEY_MEASURE, seconds(measureInput, DEFAULT_MEASURE_S))
            .apply()
    }

    private fun seconds(e: EditText, def: Int) = e.text.toString().toIntOrNull()?.coerceIn(0, 1800) ?: def

    private fun mmss(ms: Long): String {
        val s = (ms + 999) / 1000
        return String.format(Locale.US, "%d:%02d", s / 60, s % 60)
    }

    private fun beep(ms: Int) {
        try { tone?.startTone(ToneGenerator.TONE_PROP_BEEP, ms) } catch (_: Exception) {}
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = (16 * resources.displayMetrics.density).toInt()
            setPadding(p, p, p, p)
        }
        fun label(t: String, size: Float = 16f) = TextView(this).apply { text = t; textSize = size }.also { root.addView(it) }
        fun input(hint: String, value: String, type: Int) = EditText(this).apply {
            this.hint = hint; setText(value); inputType = type
        }.also { root.addView(it) }

        status = label("", 16f)
        root.addView(Button(this).apply {
            text = "Find sensor"
            setOnClickListener {
                when {
                    !hasPerms() -> requestPermissions(perms(), REQ_PERMS)
                    !sensor.isBluetoothOn -> status.text = "Turn on Bluetooth first."
                    else -> { deviceList.removeAllViews(); status.text = "Searching (15 s)…"; sensor.startScan() }
                }
            }
        })
        deviceList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }.also { root.addView(it) }

        hrText = label("-- bpm", 48f)
        phaseText = label("", 20f)

        label("Rest before measuring (s):")
        restInput = input("120", prefs.getInt(KEY_REST, DEFAULT_REST_S).toString(), InputType.TYPE_CLASS_NUMBER)
        label("Measurement length (s):")
        measureInput = input("180", prefs.getInt(KEY_MEASURE, DEFAULT_MEASURE_S).toString(), InputType.TYPE_CLASS_NUMBER)

        startButton = Button(this).apply { text = "Start measurement"; setOnClickListener { startOrCancel() } }
        root.addView(startButton)

        resultText = label("", 18f)

        label("\nintervals.icu", 18f)
        athleteInput = input("Athlete ID (e.g. i12345, or 0 = me)", prefs.getString(KEY_ATHLETE, "0") ?: "0", InputType.TYPE_CLASS_TEXT)
        apiKeyInput = input("API key (Settings → Developer)", prefs.getString(KEY_API, "") ?: "",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        uploadButton = Button(this).apply { text = "Upload to intervals.icu"; isEnabled = false; setOnClickListener { upload() } }
        root.addView(uploadButton)

        setContentView(ScrollView(this).apply { addView(root) })
    }
}
