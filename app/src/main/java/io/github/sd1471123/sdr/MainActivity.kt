package io.github.sd1471123.sdr

import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.io.BufferedOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private companion object {
        const val ACTION_USB_PERMISSION = "io.github.sd1471123.sdr.USB_PERMISSION"
        const val ESPRESSIF_VID = 0x303A
        const val CH343_VID = 0x1A86
        const val REQ_SAVE_IQ = 1001

        /** Language preference: absent means "follow the system locale". */
        const val PREFS = "sdr_prefs"
        const val KEY_LANG = "lang"
        const val LANG_EN = "en"
        const val LANG_ZH = "zh"

        /** Sliders and the text field saved next to the spinner positions. */
        const val KEY_GAIN = "gain"
        const val KEY_AGC = "agc"
        const val KEY_FREQ = "freq"

        /** Defaults restored by "reset to defaults". */
        const val DEFAULT_GAIN = 40
        const val DEFAULT_FREQ = 2437

        /** Project page shown by the author credit. */
        const val GITHUB_URL = "https://github.com/sd1471123/"

        /** Per-pin extras: status LED and buzzer. */
        const val KEY_LED_PIN = "led_pin"
        const val KEY_LED_R = "led_r"
        const val KEY_LED_G = "led_g"
        const val KEY_LED_B = "led_b"
        const val KEY_LED_BRIGHT = "led_bright"
        const val KEY_LED_MODE = "led_mode"
        const val KEY_LED_STAGES = "led_stages"
        const val KEY_LED_ON = "led_on"

        /** Pins the user drove away from Z, as "5:1,6:0" — the firmware forgets them on reboot. */
        const val KEY_GPIO_STATES = "gpio_states"

        /** Pin cell metrics: label, then a 7 dp gap, then the 3x24 dp control on its 2 dp track. */
        private const val LABEL_WIDTH_DP = 60
        private const val COLUMN_GAP_DP = 18
        private const val CELL_WIDTH_DP = LABEL_WIDTH_DP + 7 + 78
        const val KEY_BEEP_PIN = "beep_pin"
        const val KEY_BEEP_FREQ = "beep_freq"
        const val KEY_BEEP_MAX = "beep_max"
        const val KEY_BEEP_MODE = "beep_mode"
        const val KEY_BEEP_ACTIVE = "beep_active"
        const val KEY_BEEP_CHANS = "beep_chans"
        const val KEY_BEEP_PATTERN = "beep_pattern"
        const val KEY_BEEP_ON = "beep_on_ms"
        const val KEY_BEEP_OFF = "beep_off_ms"
        const val KEY_BEEP_BURSTS = "beep_bursts"
        const val KEY_BEEP_CYCLE = "beep_cycle_ms"

        const val DEFAULT_LED_PIN = 27      // ESP32-C5-DevKitC-1 RGB LED
        const val DEFAULT_BEEP_PIN = 26
        const val DEFAULT_BEEP_FREQ = 2700

        /**
         * Alarm rhythm: three short beeps, then a pause. The firmware only knows "sound at this
         * duty" and "stop", so the app clocks the pattern; one phase reaches the board per
         * capture step, which is why the times are clamped far above that granularity.
         */
        const val DEFAULT_BEEP_ON_MS = 300
        const val DEFAULT_BEEP_OFF_MS = 200
        const val DEFAULT_BEEP_BURSTS = 3
        const val DEFAULT_BEEP_CYCLE_MS = 2_000
        const val BEEP_MIN_MS = 50
        const val BEEP_MAX_MS = 10_000
        const val BEEP_MAX_CYCLE_MS = 60_000
        const val MAX_BEEP_BURSTS = 20

        /** No completed sweep for this long means the condition is gone: stop the rhythm. */
        const val BEEP_STALE_MS = 3_000L

        /** Colour source of the status LED: one fixed colour, or the interference score. */
        const val LED_FIXED = 0
        const val LED_AUTO = 1
        const val DEFAULT_LED_MODE = LED_AUTO

        /** Stages the interference score is quantised into; each one gets its own colour. */
        const val LED_STAGES = 8

        /** One LED command costs the firmware about 1 ms, so stage changes are limited to 5 Hz. */
        const val LED_STAGE_MS = 200L

        /** Hysteresis in stages: a score sitting on a boundary must not flicker. */
        const val LED_STAGE_HYST = 0.4f

        /** Stable ids so the mode radio group can be read back after the dialog is built. */
        private const val LED_MODE_FIXED_ID = 1001
        private const val LED_MODE_AUTO_ID = 1002

        /** Green → yellow → orange → red, the gradient the stage table starts with. */
        fun defaultLedStages(): Array<IntArray> = arrayOf(
            intArrayOf(0, 180, 60, 100),
            intArrayOf(60, 200, 40, 100),
            intArrayOf(150, 200, 0, 100),
            intArrayOf(230, 170, 0, 100),
            intArrayOf(255, 130, 0, 100),
            intArrayOf(255, 80, 0, 100),
            intArrayOf(255, 30, 0, 100),
            intArrayOf(255, 0, 0, 100),
        )

        /** Active buzzers ignore the tone; a carrier this high only leaves the average current. */
        const val ACTIVE_BEEP_FREQ = 20000

        /** A channel counts as "red" at the same point the strip/bars turn red. */
        const val ACTIVITY_RED_DB = 26f
        const val DETECT_RED_PCT = 90f
        const val MAX_BEEP_CHANNELS = 10

        val RATES_TXT = arrayOf("80 MS/s", "40 MS/s", "20 MS/s", "10 MS/s", "8 MS/s", "4 MS/s")
        val FFT_TXT = arrayOf("FFT 256", "FFT 512", "FFT 1024", "FFT 2048", "FFT 4096")
        val FFT_VAL = intArrayOf(256, 512, 1024, 2048, 4096)
        val BW_VAL = intArrayOf(0, 11, 16, 20, 24, 30, 40, 48)
        val LO_TXT = arrayOf("0", "+12 MHz", "-12 MHz", "+16 MHz", "-16 MHz")
        val LO_VAL = intArrayOf(0, 12, -12, 16, -16)
    }

    private lateinit var usb: UsbManager
    private lateinit var txtStatus: TextView
    private lateinit var spectrum: SpectrumView
    private lateinit var btnConnect: Button
    private lateinit var editFreq: EditText
    private lateinit var seekGain: SeekBar
    private lateinit var txtGain: TextView
    private lateinit var btnRecord: Button
    private lateinit var txtRecord: TextView
    private lateinit var txtBattery: TextView
    private lateinit var btnUpdate: Button
    private lateinit var btnPause: Button
    private lateinit var btnGpio: Button
    private lateinit var btnScreen: Button

    /** True keeps the window awake; the flag lives on the window, so it dies with the activity. */
    private var keepScreenOn = false
    private lateinit var chkAgc: CheckBox

    /** True stops the capture loop itself, so nothing keeps scanning behind a frozen display. */
    private var paused = false

    private val main = Handler(Looper.getMainLooper())
    private var dev: Espsdr? = null
    private var engine: SdrSource? = null
    private var updateBusy = false

    /** Worker that tears the previous connection down; a new open waits for it to finish. */
    private var closing: Thread? = null

    // ---- status LED / buzzer ----------------------------------------------
    private val io = Executors.newSingleThreadExecutor()
    private var ledPin = DEFAULT_LED_PIN
    private var ledR = 0
    private var ledG = 64
    private var ledB = 255
    private var ledBright = 100
    private var ledMode = DEFAULT_LED_MODE
    /** Master switch of the status LED: off wins over the colour, the mode and the interference score. */
    private var ledOn = true
    /** Per stage: r, g, b and brightness. Index 0 is the cleanest band, the last one the busiest. */
    private var ledStages = defaultLedStages()
    private var ledScore = 0f
    private var ledStage = -1
    private var ledStamp = 0L

    private var beepPin = DEFAULT_BEEP_PIN
    private var beepFreq = DEFAULT_BEEP_FREQ
    private var beepMax = 60
    private var beepMode = 0                                  // 0 activity bar, 1 detection bar
    private var beepActive = false                            // true = self-oscillating buzzer
    private val beepChannels = sortedSetOf<Int>()
    private var beepDuty = -1
    private var beepStamp = 0L

    // ---- alarm rhythm ------------------------------------------------------
    private var beepPattern = false
    private var beepOnMs = DEFAULT_BEEP_ON_MS
    private var beepOffMs = DEFAULT_BEEP_OFF_MS
    private var beepBursts = DEFAULT_BEEP_BURSTS
    private var beepCycleMs = DEFAULT_BEEP_CYCLE_MS

    /** Beeps already played in the current cycle, and whether the tone is on right now. */
    private var beepBurst = 0
    private var beepSounding = false

    /** True while a phase of the rhythm is pending, so a volume change cannot restart it. */
    private var beepArmed = false

    /** Last time the condition was actually evaluated; the rhythm stops when this goes stale. */
    private var beepSeen = 0L

    /** True when the rail selects the synthetic source instead of the USB board. */
    private var demoSource = false

    private var currentMode = Mode.LIVE
    private var currentBand = Bands.GHZ24
    private var presets: List<Preset> = emptyList()

    @Suppress("DEPRECATION")
    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_USB_PERMISSION) return
            val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            if (granted && device != null) open(device) else status(getString(R.string.status_usb_denied))
        }
    }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            showBattery(intent)
        }
    }

    private val frameListener = object : SdrEngine.Listener {
        override fun onFrame(frame: SdrFrame) {
            val rec = engine?.recorder
            val recFrames = rec?.frames ?: 0
            val recPoints = rec?.samples ?: 0
            val recKb = (rec?.bytes ?: 0L) / 1024
            main.post {
                // A frozen display must stay frozen: no redraw *and* no channel-activity text.
                if (paused) return@post
                spectrum.setFrame(frame)
                val line = summary(frame)
                if (line.isNotEmpty()) status(line)
                updateBeep(frame.bars, frame.rates, frame.band)
                updateLed(frame)
                txtRecord.text = if (rec == null) "" else
                    getString(R.string.status_rec_live, recFrames, recPoints, recKb)
            }
        }

        override fun onNotice(text: String) {
            main.post { status(text) }
        }

        override fun onError(t: Throwable) {
            main.post {
                status(getString(R.string.status_capture_lost, t.message.orEmpty()))
                closeRadio()
            }
        }
    }

    /**
     * The activity is rebuilt with the locale chosen in the language spinner; when nothing has
     * been stored the system locale decides (Chinese in, everything else English).
     */
    override fun attachBaseContext(newBase: Context) {
        val tag = newBase.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LANG, null)
        if (tag.isNullOrEmpty()) {
            super.attachBaseContext(newBase)
            return
        }
        val locale = Locale.forLanguageTag(tag)
        Locale.setDefault(locale)
        val config = Configuration(newBase.resources.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale)
        super.attachBaseContext(newBase.createConfigurationContext(config))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        alignRailLabels(findViewById(R.id.rootLayout))

        txtStatus = findViewById(R.id.txtStatus)
        spectrum = findViewById(R.id.spectrum)
        btnConnect = findViewById(R.id.btnConnect)
        editFreq = findViewById(R.id.editFreq)
        seekGain = findViewById(R.id.seekGain)
        txtGain = findViewById(R.id.txtGain)
        btnRecord = findViewById(R.id.btnRecord)
        txtRecord = findViewById(R.id.txtRecord)
        txtBattery = findViewById(R.id.txtBattery)
        btnUpdate = findViewById(R.id.btnUpdate)
        btnGpio = findViewById(R.id.btnGpio)
        chkAgc = findViewById(R.id.chkAgc)      // read before applySavedConfig() restores it
        btnRecord.isEnabled = false
        // The GPIO menu also holds the LED and buzzer setup, so it stays usable without a board.
        btnGpio.isEnabled = true

        usb = getSystemService(Context.USB_SERVICE) as UsbManager
        registerUsbReceiver()
        registerBatteryReceiver()
        applyCutoutInsets()
        enterFullscreen()

        setupSpinners()
        applySavedConfig()
        loadSideConfig()
        setupLanguage()
        btnConnect.setOnClickListener { if (engine == null) connect() else disconnect() }
        findViewById<Button>(R.id.btnTune).setOnClickListener { applyFreq() }
        btnPause = findViewById(R.id.btnPause)
        btnPause.setOnClickListener { setPaused(!paused) }
        setPaused(false)                 // seeds the icon size that matches the pause glyph
        btnScreen = findViewById(R.id.btnScreen)
        btnScreen.setOnClickListener { setKeepScreenOn(!keepScreenOn) }
        showKeepScreenOn()               // seeds the dim state of the sun
        findViewById<Button>(R.id.btnSaveCfg).setOnClickListener { saveConfig() }
        findViewById<Button>(R.id.btnResetCfg).setOnClickListener { confirmResetConfig() }
        findViewById<Button>(R.id.btnClear).setOnClickListener { spectrum.clear() }
        chkAgc.setOnCheckedChangeListener { _, checked ->
            engine?.gain = if (checked) -1 else seekGain.progress
        }
        btnRecord.setOnClickListener { toggleRecord() }
        findViewById<Button>(R.id.btnPrevPreset).setOnClickListener { stepPreset(-1) }
        findViewById<Button>(R.id.btnNextPreset).setOnClickListener { stepPreset(1) }
        findViewById<TextView>(R.id.txtAuthor).setOnClickListener { openProjectPage() }
        btnUpdate.setOnClickListener { checkForUpdates() }
        spectrum.onTune = { freq -> tuneTo(freq) }
        spectrum.onEdgeRetune = { delta -> edgeRetune(delta) }
        seekGain.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                txtGain.text = value.toString()
                engine?.gain = if (chkAgc.isChecked) -1 else value
            }

            override fun onStartTrackingTouch(bar: SeekBar) {}
            override fun onStopTrackingTouch(bar: SeekBar) {}
        })

        if (intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            status(getString(R.string.status_usb_attached))
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            status(getString(R.string.status_usb_attached))
        }
    }

    override fun onResume() {
        super.onResume()
        enterFullscreen()
    }

    override fun onDestroy() {
        closeRadio()
        try {
            unregisterReceiver(permissionReceiver)
        } catch (_: IllegalArgumentException) {
        }
        try {
            unregisterReceiver(batteryReceiver)
        } catch (_: IllegalArgumentException) {
        }
        super.onDestroy()
    }

    /**
     * True edge-to-edge: the bars are hidden *and* the layout flags let the window use the
     * space they occupied — without LAYOUT_FULLSCREEN the decor keeps reserving the status
     * bar strip, which is the empty band at the top. The display cutout is handled by
     * [applyCutoutInsets] instead, so nothing hides under the camera hole.
     */
    @Suppress("DEPRECATION")
    private fun enterFullscreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                it.hide(WindowInsets.Type.systemBars())
            }
            return
        }
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
    }

    /** Only the cutout moves the content inwards; hidden system bars never do. */
    private fun applyCutoutInsets() {
        val root = findViewById<View>(R.id.rootLayout)
        val base = (8 * resources.displayMetrics.density).toInt()
        root.setOnApplyWindowInsetsListener { view, insets ->
            var left = 0
            var top = 0
            var right = 0
            var bottom = 0
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                insets.displayCutout?.let { cut ->
                    left = cut.safeInsetLeft
                    top = cut.safeInsetTop
                    right = cut.safeInsetRight
                    bottom = cut.safeInsetBottom
                }
            }
            view.setPadding(base + left, base + top, base + right, base + bottom)
            insets
        }
        root.requestApplyInsets()
    }

    // ---- controls ---------------------------------------------------------
    private fun setupSpinners() {
        val modes = resources.getStringArray(R.array.modes)
        val bandwidths = resources.getStringArray(R.array.bandwidths)

        spinner(R.id.spMode, modes).onPick { pos ->
            currentMode = if (pos >= 2) Mode.SWEEP else Mode.LIVE
            currentBand = if (pos % 2 == 0) Bands.GHZ24 else Bands.GHZ5
            val cur = parseFreq()
            val inBand = if (currentBand == Bands.GHZ24) cur in 2400..2500 else cur in 5150..5895
            if (!inBand) editFreq.setText((if (currentBand == Bands.GHZ24) 2437 else 5180).toString())
            engine?.apply {
                mode = currentMode
                band = currentBand
                centerMhz = parseFreq()
            }
            rebuildPresets()
            applyModeVisibility()
            if (engine != null) status(getString(R.string.status_mode, modes[pos]))
        }

        spinner(R.id.spRate, RATES_TXT).onPick { pos ->
            engine?.rateIdx = pos
            if (engine != null) status(getString(R.string.status_rate, RATES_TXT[pos]))
        }

        spinner(R.id.spFft, FFT_TXT).onPick { pos ->
            engine?.nfft = FFT_VAL[pos]
            if (engine != null) status(getString(R.string.status_fft, FFT_VAL[pos]))
        }

        spinner(R.id.spBw, bandwidths).onPick { pos ->
            engine?.bandwidthMhz = BW_VAL[pos]
            if (engine != null) status(getString(R.string.status_bw, bandwidths[pos]))
        }

        spinner(R.id.spLo, LO_TXT).onPick { pos ->
            engine?.loOffsetMhz = LO_VAL[pos]
            if (engine != null) status(getString(R.string.status_lo, LO_TXT[pos]))
        }

        spinner(
            R.id.spSource,
            arrayOf(getString(R.string.source_usb), getString(R.string.source_demo))
        ).onPick { pos ->
            demoSource = pos == 1
            if (engine != null) {
                closeRadio()
                status(getString(R.string.status_disconnected))
            }
        }

        // manual dB axis: Auto or a fixed floor, with a selectable span
        val floorVals = IntArray(10) { -20 - it * 10 }                 // -20 … -110 dBFS
        spinner(
            R.id.spFloor,
            Array(floorVals.size + 1) {
                if (it == 0) getString(R.string.axis_auto) else floorVals[it - 1].toString()
            }
        ).onPick { pos ->
            spectrum.autoScale = pos == 0
            if (pos > 0) spectrum.manualFloor = floorVals[pos - 1].toFloat()
        }

        val spanVals = intArrayOf(30, 40, 50, 60, 70, 80, 90, 100)
        spinner(R.id.spSpan, Array(spanVals.size) { "${spanVals[it]} dB" }).onPick { pos ->
            spectrum.manualSpan = spanVals[pos].toFloat()
        }

        val smoothingVals = floatArrayOf(0f, 0.5f, 0.25f, 0.1f)
        spinner(
            R.id.spAvg,
            arrayOf(
                getString(R.string.avg_off), getString(R.string.avg_short),
                getString(R.string.avg_med), getString(R.string.avg_high)
            )
        ).onPick { pos -> spectrum.smoothing = smoothingVals[pos] }

        spinner(
            R.id.spBits,
            arrayOf(getString(R.string.bits_8), getString(R.string.bits_10))
        ).onPick { pos ->
            engine?.iqBits = if (pos == 1) 10 else 8
            if (engine != null) status(getString(R.string.status_bits, if (pos == 1) 10 else 8))
        }

        // per-frame DC removal; off keeps the LO leak visible at bin 0
        spinner(R.id.spDc, arrayOf(getString(R.string.dc_on), getString(R.string.dc_off))).onPick { pos ->
            engine?.dcRemove = pos == 0
        }

        // spectrum source: phone-side FFT of a burst capture, or the chip's SPEC streamer
        spinner(
            R.id.spCap,
            arrayOf(getString(R.string.cap_iq), getString(R.string.cap_spec))
        ).onPick { pos ->
            engine?.onChipSpectrum = pos == 1
            if (engine != null) {
                status(getString(if (pos == 1) R.string.status_cap_on else R.string.status_cap_off))
            }
        }

        // waterfall scroll speed; the SPEC stream tops 100 rows/s, unreadable at full rate
        val wfMs = floatArrayOf(0f, 33f, 83f, 250f)
        spinner(
            R.id.spWf,
            arrayOf(
                getString(R.string.wf_max), getString(R.string.wf_30),
                getString(R.string.wf_12), getString(R.string.wf_4)
            )
        ).onPick { pos -> spectrum.wfRowMs = wfMs[pos] }

        findViewById<Button>(R.id.btnGpio).setOnClickListener { showGpioMenu() }

        rebuildPresets()
    }

    /**
     * Language spinner: English / 中文. The stored choice wins; without one the system locale
     * decides, and every non-Chinese language lands on the English default resources.
     */
    private fun setupLanguage() {
        val sp = findViewById<Spinner>(R.id.spLang)
        sp.adapter = adapterFor(arrayOf("English", "中文"))
        val current = currentLangTag()
        sp.setSelection(if (current == LANG_ZH) 1 else 0, false)
        sp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val tag = if (position == 1) LANG_ZH else LANG_EN
                if (tag == currentLangTag()) return
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_LANG, tag).apply()
                recreate()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    /** "zh" or "en": the saved choice first, otherwise derived from the system language. */
    private fun currentLangTag(): String {
        val saved = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_LANG, null)
        val tag = saved ?: Locale.getDefault().language
        return if (tag.startsWith("zh")) LANG_ZH else LANG_EN
    }

    /**
     * Freezes the whole pipeline, not just the drawing: the capture loop stops polling frames, so
     * the spectrum, the waterfall and the "busiest / detection" line all hold their last value.
     */
    private fun setPaused(on: Boolean) {
        paused = on
        engine?.paused = on
        if (on) beepSilence()        // a frozen display must not keep an alarm sounding
        btnPause.setText(if (on) R.string.resume_icon else R.string.pause_icon)
        // ▶ reads smaller than ❚❚ at the same size, so nudge it up to match.
        btnPause.textSize = if (on) 13f else 10f
    }

    /**
     * The sun in the top bar: keeps the panel lit while the spectrum is being watched. The flag
     * rides on the window, so it needs no permission and goes away with the activity.
     */
    private fun setKeepScreenOn(on: Boolean) {
        keepScreenOn = on
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        showKeepScreenOn()
        tell(getString(if (on) R.string.screen_on else R.string.screen_off))
    }

    /** Lit sun = holding the screen on, dim sun = the system timeout applies again. */
    private fun showKeepScreenOn() {
        btnScreen.alpha = if (keepScreenOn) 1f else 0.45f
    }

    // ---- saved configuration ----------------------------------------------

    /** Spinners written by "save config"; the language lives under its own key. */
    private fun configSpinners(): IntArray = intArrayOf(
        R.id.spMode, R.id.spSource, R.id.spRate, R.id.spBits, R.id.spDc, R.id.spCap,
        R.id.spWf, R.id.spFft, R.id.spBw, R.id.spLo, R.id.spFloor, R.id.spSpan, R.id.spAvg
    )

    private fun configKey(id: Int): String = "cfg_" + resources.getResourceEntryName(id)

    /** Snapshot of every control the user can tune, taken by the "save config" button. */
    private fun saveConfig() {
        val edit = getSharedPreferences(PREFS, MODE_PRIVATE).edit()
        for (id in configSpinners()) {
            edit.putInt(configKey(id), findViewById<Spinner>(id).selectedItemPosition)
        }
        edit.putInt(KEY_GAIN, seekGain.progress)
        edit.putBoolean(KEY_AGC, chkAgc.isChecked)
        edit.putString(KEY_FREQ, parseFreq().toString())
        edit.apply()
        tell(getString(R.string.cfg_saved))
    }

    /** The status line is overwritten by the live summary, so confirmations also pop a toast. */
    private fun tell(text: String) {
        status(text)
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    /** Replays the stored snapshot at start-up; when nothing is stored the defaults stay. */
    private fun applySavedConfig() {
        val p = getSharedPreferences(PREFS, MODE_PRIVATE)
        val freq = p.getString(KEY_FREQ, null)?.toIntOrNull() ?: return
        pick(R.id.spMode, p)                    // rebuilds the preset list for the saved band
        editFreq.setText(freq.toString())
        for (id in configSpinners()) {
            if (id != R.id.spMode) pick(id, p)
        }
        seekGain.progress = p.getInt(KEY_GAIN, DEFAULT_GAIN)
        txtGain.text = seekGain.progress.toString()     // listener is not attached yet
        chkAgc.isChecked = p.getBoolean(KEY_AGC, false)
        rebuildPresets()                        // align the preset with the saved centre frequency
    }

    private fun pick(id: Int, p: SharedPreferences) {
        val pos = p.getInt(configKey(id), -1)
        if (pos >= 0) findViewById<Spinner>(id).setSelection(pos)
    }

    private fun confirmResetConfig() {
        AlertDialog.Builder(this)
            .setTitle(R.string.cfg_reset_title)
            .setMessage(R.string.cfg_reset_msg)
            .setNegativeButton(R.string.cfg_cancel, null)
            .setPositiveButton(R.string.cfg_reset_ok) { _, _ -> applyDefaults() }
            .show()
    }

    /** Drops the snapshot and puts every control back to the value it boots with. */
    private fun applyDefaults() {
        val edit = getSharedPreferences(PREFS, MODE_PRIVATE).edit()
        for (id in configSpinners()) edit.remove(configKey(id))
        edit.remove(KEY_GAIN)
        edit.remove(KEY_AGC)
        edit.remove(KEY_FREQ)
        edit.apply()

        findViewById<Spinner>(R.id.spMode).setSelection(0)
        editFreq.setText(DEFAULT_FREQ.toString())
        for (id in configSpinners()) {
            if (id != R.id.spMode) findViewById<Spinner>(id).setSelection(0)
        }
        seekGain.progress = DEFAULT_GAIN
        txtGain.text = DEFAULT_GAIN.toString()
        chkAgc.isChecked = false
        rebuildPresets()
        tell(getString(R.string.cfg_reset_done))
    }

    /** Presets, centre frequency and LO offset only mean something in the live modes. */
    private fun applyModeVisibility() {
        val live = currentMode == Mode.LIVE
        findViewById<View>(R.id.rowPreset).visibility = if (live) View.VISIBLE else View.GONE
        findViewById<View>(R.id.rowPresetNav).visibility = if (live) View.VISIBLE else View.GONE
        findViewById<View>(R.id.rowFreq).visibility = if (live) View.VISIBLE else View.GONE
        findViewById<View>(R.id.rowLo).visibility = if (live) View.VISIBLE else View.GONE
    }

    /** Channel presets of the current band, including the 40/80 MHz groupings on 5 GHz. */
    private fun rebuildPresets() {
        presets = Bands.presets(currentBand)
        val sp = findViewById<Spinner>(R.id.spPreset)
        val adapter = adapterFor(presets.map { it.label })
        sp.onItemSelectedListener = null
        sp.adapter = adapter

        val cur = parseFreq()
        val near = presets.indices.minByOrNull { Math.abs(presets[it].center - cur) }
        if (near != null) sp.setSelection(near, false)

        // Attach the listener after the initial selection so seeding the list stays silent.
        sp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                presets.getOrNull(position)?.let { applyPreset(it) }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    /** Arrow buttons: step one entry up/down the preset list, wrapping at both ends. */
    private fun stepPreset(delta: Int) {
        if (presets.isEmpty()) return
        val sp = findViewById<Spinner>(R.id.spPreset)
        val n = presets.size
        val next = ((sp.selectedItemPosition + delta) % n + n) % n
        sp.setSelection(next)
    }

    private fun applyPreset(p: Preset) {
        editFreq.setText(p.center.toString())
        engine?.centerMhz = p.center
        if (p.width >= 40) {
            // A 40/80 MHz channel only fits in the widest capture.
            findViewById<Spinner>(R.id.spRate).setSelection(0)
            engine?.rateIdx = 0
        }
        if (engine != null) status(getString(R.string.status_preset, p.label, p.center))
    }

    private fun spinner(id: Int, items: Array<String>): Spinner {
        val sp = findViewById<Spinner>(id)
        sp.adapter = adapterFor(items)
        return sp
    }

    /** Spinners use their own compact item layout: the widget style cannot shrink the text. */
    private fun adapterFor(items: Array<String>): ArrayAdapter<String> {
        val adapter = ArrayAdapter(this, R.layout.item_spinner, items)
        adapter.setDropDownViewResource(R.layout.item_spinner_dropdown)
        return adapter
    }

    private fun adapterFor(items: List<String>): ArrayAdapter<String> {
        val adapter = ArrayAdapter(this, R.layout.item_spinner, items)
        adapter.setDropDownViewResource(R.layout.item_spinner_dropdown)
        return adapter
    }

    private fun Spinner.onPick(block: (Int) -> Unit) {
        onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                block(position)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    /**
     * Status line: busiest channels and best detection rate, with the clipping warning appended
     * at the end. Clip never stands alone — a clip-only summary flickered the top bar between
     * the standing message and "削顶 x %" every few frames.
     */
    private fun summary(frame: SdrFrame): String {
        val parts = ArrayList<String>(3)
        val bars = frame.bars
        if (bars != null) {
            val busy = busiest(bars, frame.band)
            if (busy.isNotEmpty()) parts.add(busy)
        }
        val rates = frame.rates
        if (rates != null) {
            val top = topDetected(rates, bars, frame.band)
            if (top.isNotEmpty()) parts.add(top)
        }
        if (parts.isEmpty()) return ""
        if (frame.clipPct >= 0.5f) parts.add(getString(R.string.clip_pct, frame.clipPct))
        return parts.joinToString("   ")
    }

    private fun busiest(bars: FloatArray, band: String): String {
        val chans = Bands.channels20(band)
        val n = minOf(bars.size, chans.size)
        if (n == 0) return ""
        val order = (0 until n).sortedByDescending { bars[it] }
        val top = order.take(2).joinToString("   ") { i ->
            getString(
                R.string.busiest_item,
                Bands.channelNumber(chans[i].toFloat()),
                chans[i],
                "%.0f".format(bars[i])
            )
        }
        return getString(R.string.busiest, top)
    }

    /**
     * Channel with the best detection rate; equal rates are broken by signal strength, so a
     * broad noise floor does not pin the readout on the first channel of the band.
     */
    private fun topDetected(rates: FloatArray, bars: FloatArray?, band: String): String {
        val chans = Bands.channels20(band)
        val n = minOf(rates.size, chans.size)
        if (n == 0) return ""
        var best = 0
        for (i in 1 until n) {
            if (rates[i] > rates[best] ||
                (rates[i] == rates[best] && bars != null && i < bars.size && bars[i] > bars[best])
            ) best = i
        }
        if (rates[best] < 1f) return ""
        val ch = Bands.channelNumber(chans[best].toFloat())
        return getString(R.string.top_detected, ch, "%.0f".format(rates[best]))
    }

    // ---- I/Q recording ----------------------------------------------------
    private fun toggleRecord() {
        val e = engine
        if (e == null) {
            status(getString(R.string.status_need_device))
            return
        }
        if (e.recorder != null) {
            stopRecording()
            return
        }
        val name = "iq_${parseFreq()}MHz_" +
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".cfile"
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/octet-stream"
            putExtra(Intent.EXTRA_TITLE, name)
        }
        try {
            startActivityForResult(intent, REQ_SAVE_IQ)
        } catch (e: Exception) {
            status(getString(R.string.status_no_picker, e.message.orEmpty()))
        }
    }

    @Deprecated("Activity#onActivityResult is fine for a single document picker")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_SAVE_IQ || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val e = engine ?: return
        try {
            val out = contentResolver.openOutputStream(uri)
                ?: throw IllegalStateException("openOutputStream() returned null")
            val rec = IqRecorder(BufferedOutputStream(out, 1 shl 16))
            e.recorder = rec
            btnRecord.setText(R.string.stop_record)
            status(getString(R.string.status_rec_start, uri.lastPathSegment.orEmpty()))
        } catch (ex: Exception) {
            status(getString(R.string.status_write_fail, ex.message.orEmpty()))
        }
    }

    private fun stopRecording() {
        val e = engine
        val rec = e?.recorder
        e?.recorder = null
        rec?.close()
        btnRecord.setText(R.string.record)
        txtRecord.text = if (rec == null) "" else
            getString(R.string.status_rec_saved, rec.frames, rec.samples, rec.bytes / 1024)
        if (rec != null) status(getString(R.string.status_rec_done, rec.samples))
    }

    private fun tuneTo(freq: Float) {
        val mhz = Math.round(freq)
        engine?.centerMhz = mhz
        editFreq.setText(mhz.toString())
        status(getString(R.string.status_tuned, mhz))
    }

    /** Dragging the spectrum past a band edge walks the centre by one capture window. */
    private fun edgeRetune(deltaMhz: Float) {
        if (currentMode != Mode.LIVE) return
        val target = parseFreq() + Math.round(deltaMhz)
        val lo = if (currentBand == Bands.GHZ24) 2400 else 5150
        val hi = if (currentBand == Bands.GHZ24) 2500 else 5895
        if (target - 40 < lo || target + 40 > hi) return
        tuneTo(target.toFloat())
    }

    /** Asks the board which pins it drives and in which state, then shows the picker. */
    private fun showGpioDialog() {
        val d = dev ?: return
        val source = engine
        Thread {
            val wasPaused = source?.paused ?: false
            try {
                // The capture loop owns the wire while it runs, and pausing it only takes effect
                // at the top of its next step: sleeping a fixed 200 ms was a gamble. When the
                // capture in flight outlived it, the GPIO? reply and the DATA payload landed in
                // each other's reads, the CRC check failed and the whole capture was torn down —
                // which is the "capture lost" that showed up almost every time this panel opened.
                // Wait for the loop to actually let go, and skip the query rather than write into
                // a busy wire.
                if (source != null) {
                    source.paused = true
                    val idle = (source as? SdrEngine)?.awaitBusIdle(1500) ?: true
                    if (!idle) {
                        main.post { toast(getString(R.string.gpio_busy)) }
                        return@Thread
                    }
                    d.drainInput()          // nothing should be buffered once the loop is idle
                }
                val states = LinkedHashMap(
                    d.gpioStates() ?: run {
                        // Nothing to show: hand the menu back instead of landing on the main screen.
                        main.post {
                            toast(getString(R.string.gpio_none))
                            showGpioMenu()
                        }
                        return@Thread
                    }
                )
                // The firmware keeps pin levels in RAM only, so replay what the user set last
                // time; Z is the reset state and is never stored.
                for ((pin, state) in loadGpioPrefs()) {
                    if (states[pin] == state || !states.containsKey(pin)) continue
                    try {
                        d.cmd("GPIO $pin $state")
                        states[pin] = state
                    } catch (_: Throwable) {
                        // Keep the reported state: the panel shows what the board really does.
                    }
                }
                main.post { showGpioPanel(d, states) }
            } catch (t: Throwable) {
                main.post { toast(t.message ?: "GPIO error") }
            } finally {
                source?.paused = wasPaused
            }
        }.start()
    }

    /**
     * Pin grid: the state the firmware reported plus a Z/0/1 segmented control per pin, two
     * columns from 360 dp up. The panel draws its own header (count, owned pins, legend) instead
     * of an AlertDialog title, so the whole card sits on one type scale.
     */
    private fun showGpioPanel(d: Espsdr, states: Map<Int, Char>) {
        val pins = states.keys.sorted()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(2))
        }

        content.addView(TextView(this).apply {
            setText(R.string.gpio_menu_pins)
            setTextColor(getColor(R.color.text))
            textSize = 13f
            letterSpacing = 0.02f
            setTypeface(typeface, Typeface.BOLD)
        })

        val owned = ArrayList<String>(2)
        owned.add(getString(R.string.gpio_pin_led_tag) + " " + ledPin)
        if (beepPin in states) owned.add(getString(R.string.gpio_pin_beep_tag) + " " + beepPin)
        content.addView(TextView(this).apply {
            text = if (owned.isEmpty()) getString(R.string.gpio_pins_count, pins.size)
            else getString(R.string.gpio_pins_header, pins.size, owned.joinToString(" · "))
            setTextColor(getColor(R.color.text_dim))
            textSize = 8f
            letterSpacing = 0.05f
            setPadding(0, dp(3), 0, 0)
        })

        content.addView(TextView(this).apply {
            setText(R.string.gpio_legend)
            setTextColor(getColor(R.color.text_dim))
            textSize = 8f
            setPadding(0, dp(6), 0, dp(5))
        })
        content.addView(divider())

        val columns = if (resources.configuration.screenWidthDp >= 400) 2 else 1
        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        if (pins.isEmpty()) {
            grid.addView(TextView(this).apply {
                setText(R.string.gpio_none)
                setTextColor(getColor(R.color.text_dim))
                textSize = 9f
                setPadding(0, dp(12), 0, dp(12))
            })
        } else {
            for (start in pins.indices step columns) {
                // Cells keep their natural width and the row centres the pair, so the grid sits in
                // the middle of the dialog instead of hugging its left edge.
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = if (columns > 1) Gravity.CENTER_HORIZONTAL else Gravity.START
                }
                for (i in 0 until columns) {
                    val pin = pins.getOrNull(start + i)
                    if (pin == null) {
                        // Odd pin count: keep the last cell empty so the grid stays aligned.
                        row.addView(View(this), LinearLayout.LayoutParams(dp(CELL_WIDTH_DP), dp(1)))
                    } else {
                        // A wide gap between the columns, a small one between a pin and its
                        // control: that is what keeps the pairing readable at a glance.
                        val params = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                        )
                        if (i == 0 && columns > 1) params.marginEnd = dp(COLUMN_GAP_DP)
                        row.addView(pinCell(d, pin, states[pin] ?: 'Z'), params)
                    }
                }
                grid.addView(row)
            }
        }

        // As tall as the grid wants, capped so the header and the "done" button still fit inside
        // the window: landscape is only 360 dp tall, and asking for more than that cut the last
        // row in half instead of scrolling to it.
        val rows = ((pins.size + columns - 1) / columns).coerceAtLeast(1)
        val cap = (resources.displayMetrics.heightPixels * 0.55f).toInt()
        content.addView(ScrollView(this).apply {
            addView(grid)
            isVerticalScrollBarEnabled = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, minOf(rows * dp(22) + dp(4), cap)
            )
        })

        AlertDialog.Builder(this)
            .setView(content)
            .setPositiveButton(R.string.done, null)
            .create()
            .also { it.backToMenu() }
            .show()
    }

    /**
     * One pin: label on the left, a Z/0/1 segmented control on the right. The control sits on a
     * recessed track so the level reads as one of three, and the label follows it: dim while the
     * pin is high-Z, accent once it is driven, red when the buzzer owns it.
     */
    private fun pinCell(d: Espsdr, pin: Int, initial: Char): LinearLayout {
        val buzzer = pin == beepPin
        val cell = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(1), 0, dp(1))
        }
        val label = TextView(this).apply {
            text = if (buzzer) getString(R.string.gpio_pin_buzzer, pin) else "GPIO $pin"
            textSize = 10f
            maxLines = 1
            letterSpacing = 0.02f
            ellipsize = TextUtils.TruncateAt.END
        }
        // Fixed width: every cell is then the same size, so the grid centres as a block and the
        // controls of one column still line up under each other.
        cell.addView(label, LinearLayout.LayoutParams(dp(LABEL_WIDTH_DP), LinearLayout.LayoutParams.WRAP_CONTENT))

        val keys = charArrayOf('Z', '0', '1')
        val chips = ArrayList<TextView>(keys.size)
        var state = initial

        fun paint(value: Char) {
            for ((i, chip) in chips.withIndex()) {
                val on = keys[i] == value
                chip.setBackgroundResource(if (on) R.drawable.bg_button_accent else 0)
                chip.setTextColor(getColor(if (on) R.color.accent else R.color.text_dim))
                chip.setTypeface(null, if (on) Typeface.BOLD else Typeface.NORMAL)
            }
            label.setTextColor(
                getColor(
                    when {
                        buzzer -> R.color.danger
                        value == 'Z' -> R.color.text_dim
                        else -> R.color.accent
                    }
                )
            )
        }

        val track = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(2), dp(2), dp(2), dp(2))
            setBackgroundResource(R.drawable.bg_segment_track)
            isFocusable = false
        }
        for (key in keys) {
            val chip = TextView(this).apply {
                text = key.toString()
                textSize = 9f
                gravity = Gravity.CENTER
                includeFontPadding = false
                isClickable = true
                isFocusable = false
            }
            chip.setOnClickListener {
                if (state == key) return@setOnClickListener
                val previous = state
                state = key
                paint(state)
                rememberGpio(pin, key)
                sendGpio(pin, key) {
                    // A sounding buzzer owns its pin: restore the shown state and say why.
                    state = previous
                    paint(previous)
                    rememberGpio(pin, previous)
                }
            }
            chips.add(chip)
            track.addView(
                chip,
                LinearLayout.LayoutParams(dp(24), dp(16)).apply {
                    if (chips.size > 1) marginStart = dp(1)
                }
            )
        }
        paint(state)
        cell.addView(
            track,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(7) }
        )
        return cell
    }

    /**
     * Pin levels live in the firmware's RAM and reset to Z on every boot, so the app keeps the
     * ones the user chose and replays them the next time the panel opens.
     */
    private fun loadGpioPrefs(): Map<Int, Char> {
        val raw = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_GPIO_STATES, "").orEmpty()
        val out = LinkedHashMap<Int, Char>()
        for (part in raw.split(',')) {
            val kv = part.split(':')
            if (kv.size != 2) continue
            val pin = kv[0].trim().toIntOrNull() ?: continue
            val state = kv[1].trim().firstOrNull() ?: continue
            if (state in "Z01") out[pin] = state
        }
        return out
    }

    private fun saveGpioPrefs(states: Map<Int, Char>) {
        val text = states.entries.joinToString(",") { "${it.key}:${it.value}" }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_GPIO_STATES, text).apply()
    }

    /** Z is the reset state, so only the driven levels are worth remembering. */
    private fun rememberGpio(pin: Int, state: Char) {
        val map = LinkedHashMap(loadGpioPrefs())
        if (state == 'Z') map.remove(pin) else map[pin] = state
        saveGpioPrefs(map)
    }

    /**
     * Writes one pin. While the capture loop runs these commands ride the side-command queue, so
     * they never share the wire with a capture; without an engine they go on the side thread.
     */
    private fun sendGpio(pin: Int, state: Char, onFail: (() -> Unit)? = null) {
        val d = dev ?: return
        val cmd = "GPIO $pin $state"
        if (engine != null) {
            // The refusal only shows up later, on the capture thread: undo the tap from there.
            d.queue(cmd) { t ->
                main.post {
                    toast(t.message ?: "GPIO error")
                    onFail?.invoke()
                }
            }
            return
        }
        io.execute {
            try {
                d.cmd(cmd)
            } catch (t: Throwable) {
                main.post {
                    toast(t.message ?: "GPIO error")
                    onFail?.invoke()
                }
            }
        }
    }

    // ---- GPIO sub-menu: pins / status LED / buzzer -------------------------

    /** The rail button opens a menu; each entry is its own panel. */
    private fun showGpioMenu() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(6))
        }
        lateinit var dialog: AlertDialog
        val close = { dialog.dismiss() }

        panelHeader(
            box, R.string.gpio_title,
            getString(R.string.gpio_pin_led_tag) + " " + ledPin + " · " +
                getString(R.string.gpio_pin_beep_tag) + " " + beepPin
        )
        box.addView(divider())

        box.addView(
            menuRow(R.string.gpio_menu_pins, pinsSummary()) {
                close()
                // Without a board there is nothing to open, so the menu stays put instead of
                // closing and dropping the user on the main screen.
                if (dev == null) {
                    tell(getString(R.string.side_need_device))
                } else {
                    close()
                    showGpioDialog()
                }
            }
        )
        box.addView(divider())
        box.addView(
            menuRow(R.string.gpio_menu_led, ledSummary()) {
                close()
                showLedPanel()
            }
        )
        box.addView(divider())
        box.addView(
            menuRow(R.string.gpio_menu_beep, beepSummary()) {
                close()
                showBeepPanel()
            }
        )
        box.addView(divider())
        box.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(10), 0, dp(2))
                addView(menuButton(getString(R.string.gpio_save), true) {
                    saveSideConfig()
                    repushSide()
                    tell(getString(R.string.gpio_saved))
                }, rowParams(1f))
                addView(menuButton(getString(R.string.gpio_reset), false) {
                    resetSideDefaults()
                    close()
                    showGpioMenu()          // redraw the summaries with the restored values
                }, rowParams(1f))
            }
        )

        dialog = AlertDialog.Builder(this)
            .setView(box)
            .setNegativeButton(R.string.done, null)
            .create()
        dialog.show()
    }

    private fun rowParams(weight: Float) = LinearLayout.LayoutParams(
        0, LinearLayout.LayoutParams.WRAP_CONTENT, weight
    ).apply { marginStart = dp(4); marginEnd = dp(4) }

    /**
     * A panel opened from the GPIO menu returns to it when it closes, so closing a panel walks one
     * level up instead of dropping the user on the main screen.
     */
    private fun AlertDialog.backToMenu() {
        setOnDismissListener {
            if (!isFinishing && !isDestroyed && !isChangingConfigurations) showGpioMenu()
        }
    }

    /** One menu entry: title, live summary underneath, and a chevron on the right. */
    private fun menuRow(titleId: Int, subtitle: String, onClick: () -> Unit): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(11), 0, dp(11))
            isClickable = true
            isFocusable = true
            val attrs = TypedValue()
            if (theme.resolveAttribute(android.R.attr.selectableItemBackground, attrs, true))
                setBackgroundResource(attrs.resourceId)
            setOnClickListener { onClick() }
        }
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(TextView(this).apply {
            setText(titleId)
            setTextColor(getColor(R.color.text))
            textSize = 12f
            letterSpacing = 0.03f
        })
        column.addView(TextView(this).apply {
            text = subtitle
            setTextColor(getColor(R.color.text_dim))
            textSize = 9f
            setPadding(0, dp(3), 0, 0)
        })
        row.addView(column, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(TextView(this).apply {
            text = "›"
            setTextColor(getColor(R.color.text_dim))
            textSize = 16f
            setPadding(dp(6), 0, 0, 0)
        })
        return row
    }

    private fun divider(): View = View(this).apply {
        setBackgroundColor(getColor(R.color.panel_stroke))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
    }

    private fun menuButton(text: String, accent: Boolean, onClick: () -> Unit): Button =
        Button(this).apply {
            setText(text)
            textSize = 11f
            isAllCaps = false
            minHeight = 0
            minWidth = 0
            setPadding(dp(10), dp(8), dp(10), dp(8))
            setBackgroundResource(if (accent) R.drawable.bg_button_accent else R.drawable.bg_button)
            setTextColor(getColor(if (accent) R.color.accent else R.color.text))
            stateListAnimator = null
            setOnClickListener { onClick() }
        }

    /**
     * Gives every rail label the same column: the natural width of the widest one, measured after
     * inflation. The text itself is left alone — no stretched letters — so Chinese and English
     * keep their normal spacing; short labels simply centre inside that column and the controls to
     * their right all start on the same line.
     */
    private fun alignRailLabels(root: View) {
        val labels = ArrayList<TextView>()
        fun collect(v: View) {
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) collect(v.getChildAt(i))
                return
            }
            if (v is TextView && v.tag == "rail") labels.add(v)
        }
        collect(root)
        if (labels.isEmpty()) return
        val free = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        var widest = 0
        for (label in labels) {
            label.measure(free, free)
            widest = maxOf(widest, label.measuredWidth)
        }
        for (label in labels) {
            val params = label.layoutParams as LinearLayout.LayoutParams
            params.width = widest
            label.layoutParams = params
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun pinsSummary(): String =
        if (dev == null) getString(R.string.side_need_device) else getString(R.string.gpio_pins_sub)

    private fun ledSummary(): String = when {
        !ledOn -> getString(R.string.gpio_led_off_sub, ledPin)
        ledMode == LED_AUTO -> getString(R.string.gpio_led_auto_sub, ledPin, LED_STAGES)
        else -> getString(R.string.gpio_led_sub, ledPin, ledR, ledG, ledB, ledBright)
    }

    private fun beepSummary(): String {
        val condition =
            getString(if (beepMode == 0) R.string.beep_cond_activity else R.string.beep_cond_rate)
        val tone = if (beepActive) getString(R.string.beep_type_active_short)
        else getString(R.string.beep_tone, beepFreq)
        val base = getString(R.string.gpio_beep_sub, beepPin, tone, condition, beepChannels.size)
        if (!beepPattern) return base
        return base + " · " +
            getString(R.string.beep_pattern_short, beepOnMs, beepOffMs, beepBursts, beepCycleMs)
    }

    /** Back to the pin/colour/volume values the app boots with. */
    private fun resetSideDefaults() {
        ledPin = DEFAULT_LED_PIN
        ledR = 0
        ledG = 64
        ledB = 255
        ledBright = 100
        ledMode = DEFAULT_LED_MODE
        ledOn = true
        ledStages = defaultLedStages()
        ledStage = -1
        ledScore = 0f
        beepPin = DEFAULT_BEEP_PIN
        beepFreq = DEFAULT_BEEP_FREQ
        beepMax = 60
        beepMode = 0
        beepPattern = false
        beepOnMs = DEFAULT_BEEP_ON_MS
        beepOffMs = DEFAULT_BEEP_OFF_MS
        beepBursts = DEFAULT_BEEP_BURSTS
        beepCycleMs = DEFAULT_BEEP_CYCLE_MS
        beepChannels.clear()
        beepSilence()
        beepDuty = -1
        val driven = loadGpioPrefs()
        saveGpioPrefs(emptyMap())
        saveSideConfig()
        if (dev != null) {
            sendSide("BEEP 0", null)
            applyLed(false)
            for ((pin, _) in driven) sendGpio(pin, 'Z')
        }
        tell(getString(R.string.gpio_reset_done))
    }

    private fun showLedPanel() {
        // The panel is also the place to prepare a colour before the board is plugged in.
        if (dev?.hasLed == false) {
            tell(getString(R.string.side_unsupported))
            return
        }
        val box = panel()
        panelHeader(box, R.string.gpio_menu_led, ledSummary())
        box.addView(divider())
        val editPin = numberField(box, R.string.led_pin, ledPin)

        // Two sources: a colour the user picks, or the stage table driven by the interference score.
        val modes = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        val fixedBtn = RadioButton(this).apply { id = LED_MODE_FIXED_ID; text = getString(R.string.led_mode_fixed) }
        val autoBtn = RadioButton(this).apply { id = LED_MODE_AUTO_ID; text = getString(R.string.led_mode_auto) }
        modes.addView(fixedBtn)
        modes.addView(autoBtn)
        modes.check(if (ledMode == LED_AUTO) LED_MODE_AUTO_ID else LED_MODE_FIXED_ID)
        box.addView(title(R.string.led_mode))
        box.addView(modes)

        // Both editors sit on this page; the radio group decides which one is shown, so switching
        // modes never leaves the irrelevant sliders on screen looking active.
        val fixedBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        var r = ledR
        var g = ledG
        var b = ledB
        var bright = ledBright
        compactSlider(fixedBox, getString(R.string.led_red), r, 255) { r = it }
        compactSlider(fixedBox, getString(R.string.led_green), g, 255) { g = it }
        compactSlider(fixedBox, getString(R.string.led_blue), b, 255) { b = it }
        compactSlider(fixedBox, getString(R.string.led_bright), bright, 100) { bright = it }

        val autoBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        autoBox.addView(hint(R.string.led_auto_hint))
        autoBox.addView(stageStrip { showLedStagesPanel() })
        autoBox.addView(hint(R.string.led_stages_caption))
        autoBox.addView(menuButton(getString(R.string.led_stages), accent = false) { showLedStagesPanel() })

        box.addView(fixedBox)
        box.addView(autoBox)

        val tall = (resources.displayMetrics.heightPixels * 0.72f).toInt()
        val scroll = panelScroll(box)

        // The auto view is short, the fixed view holds four sliders: let the dialog follow, so
        // neither mode leaves a big empty area or a scroll box that is cut off.
        fun showMode(auto: Boolean) {
            fixedBox.visibility = if (auto) View.GONE else View.VISIBLE
            autoBox.visibility = if (auto) View.VISIBLE else View.GONE
            // The dialog wraps this view in a FrameLayout, so the params have to be its type.
            scroll.layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                if (auto) FrameLayout.LayoutParams.WRAP_CONTENT else tall
            )
        }
        showMode(ledMode == LED_AUTO)
        modes.setOnCheckedChangeListener { _, id -> showMode(id == LED_MODE_AUTO_ID) }

        val dialog = AlertDialog.Builder(this)
            .setView(scroll)
            .setPositiveButton(R.string.apply) { _, _ ->
                ledPin = editPin.text.toString().toIntOrNull()?.coerceIn(0, 48) ?: DEFAULT_LED_PIN
                ledR = r
                ledG = g
                ledB = b
                ledBright = bright
                ledMode = if (modes.checkedRadioButtonId == LED_MODE_AUTO_ID) LED_AUTO else LED_FIXED
                ledStage = -1
                ledScore = 0f
                saveSideConfig()
                when {
                    // The switch is a setting of its own: saving while dark must not relight the strip.
                    !ledOn -> {
                        applyLed(false)
                        tell(getString(R.string.led_off_kept))
                    }
                    ledMode == LED_FIXED -> applyLed(true)
                    else -> tell(getString(R.string.led_auto_applied))
                }
            }
            .setNeutralButton(if (ledOn) R.string.led_off else R.string.led_on, null)
            .setNegativeButton(R.string.cancel, null)
            .create()
            .also { it.backToMenu() }

        // Set by the builder, the neutral button dismisses the dialog. Replacing its listener after
        // the dialog is shown lets the switch flip in place: the page, the mode and the sliders the
        // user was editing all stay as they are, only the label follows the new state.
        dialog.setOnShowListener {
            val toggle = dialog.getButton(AlertDialog.BUTTON_NEUTRAL)
            toggle.setOnClickListener {
                ledOn = !ledOn
                ledStage = -1                    // a fresh stage, so the auto colour re-pushes
                ledScore = 0f
                if (ledOn) {
                    if (ledMode == LED_FIXED) applyLed(true)
                    else tell(getString(R.string.led_auto_applied))
                } else {
                    sendSide("LED 0", getString(R.string.led_off))
                }
                saveSideConfig()                 // the switch outlives the dialog and the connection
                toggle.setText(if (ledOn) R.string.led_off else R.string.led_on)
            }
        }
        dialog.show()
    }

    /**
     * One colour per interference stage. The rows are ordered like the score: stage 1 is a clean
     * band, the last stage a saturated one, and each row previews its colour above the sliders.
     */
    private fun showLedStagesPanel() {
        val box = panel()
        panelHeader(box, R.string.led_stages, null)
        box.addView(divider())
        val draft = Array(LED_STAGES) { ledStages[it].copyOf() }
        val swatches = ArrayList<View>(LED_STAGES)

        fun paint(i: Int) = swatches[i].setBackgroundColor(Color.rgb(draft[i][0], draft[i][1], draft[i][2]))

        for (i in 0 until LED_STAGES) {
            val lo = i * 100 / LED_STAGES
            val hi = (i + 1) * 100 / LED_STAGES
            box.addView(TextView(this).apply {
                text = getString(R.string.led_stage_range, i + 1, lo, hi)
                setTextColor(getColor(R.color.text_dim))
                textSize = 9f
                setPadding(0, dp(10), 0, dp(2))
            })
            val swatch = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(12))
            }
            swatches.add(swatch)
            box.addView(swatch)
            paint(i)
            compactSlider(box, getString(R.string.led_red), draft[i][0], 255) { draft[i][0] = it; paint(i) }
            compactSlider(box, getString(R.string.led_green), draft[i][1], 255) { draft[i][1] = it; paint(i) }
            compactSlider(box, getString(R.string.led_blue), draft[i][2], 255) { draft[i][2] = it; paint(i) }
            compactSlider(box, getString(R.string.led_bright), draft[i][3], 100) { draft[i][3] = it; paint(i) }
        }

        AlertDialog.Builder(this)
            .setView(panelScroll(box))
            .setPositiveButton(R.string.apply) { _, _ ->
                ledStages = draft
                ledStage = -1
                ledScore = 0f
                saveSideConfig()
                tell(getString(R.string.led_stages_applied))
            }
            .setNeutralButton(R.string.led_stages_reset) { _, _ ->
                ledStages = defaultLedStages()
                ledStage = -1
                saveSideConfig()
                tell(getString(R.string.led_stages_applied))
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showBeepPanel() {
        if (dev?.hasBeep == false) {
            tell(getString(R.string.side_unsupported))
            return
        }
        val box = panel()
        panelHeader(box, R.string.gpio_menu_beep, beepSummary())
        box.addView(divider())
        val editPin = numberField(box, R.string.beep_pin, beepPin)
        val editFreq = numberField(box, R.string.beep_freq, beepFreq)

        // Passive buzzers need the tone, active ones only need current: the type decides how the
        // volume percentage turns into a duty cycle, so it has to be explicit.
        val types = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        val rbPassive = RadioButton(this).apply {
            id = View.generateViewId()
            text = getString(R.string.beep_type_passive)
        }
        val rbActive = RadioButton(this).apply {
            id = View.generateViewId()
            text = getString(R.string.beep_type_active)
        }
        types.addView(rbPassive)
        types.addView(rbActive)
        if (beepActive) rbActive.isChecked = true else rbPassive.isChecked = true
        box.addView(title(R.string.beep_type))
        box.addView(types)
        val syncFreq = { editFreq.isEnabled = !rbActive.isChecked }
        rbPassive.setOnCheckedChangeListener { _, _ -> syncFreq() }
        rbActive.setOnCheckedChangeListener { _, _ -> syncFreq() }
        syncFreq()

        val group = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        val rbActivity = RadioButton(this).apply {
            id = View.generateViewId()
            text = getString(R.string.beep_cond_activity)
        }
        val rbRate = RadioButton(this).apply {
            id = View.generateViewId()
            text = getString(R.string.beep_cond_rate)
        }
        group.addView(rbActivity)
        group.addView(rbRate)
        if (beepMode == 1) rbRate.isChecked = true else rbActivity.isChecked = true
        box.addView(title(R.string.beep_condition))
        box.addView(group)

        var maxVol = beepMax
        slider(box, getString(R.string.beep_max), maxVol, 100) { maxVol = it }

        box.addView(title(R.string.beep_channels))
        // One tappable square per channel: 16 channels stacked as rows ate half the panel, so they
        // become a compact grid — bound channels light up in the accent colour. Six per row, and a
        // one-or-two chip leftover is folded into the rows above instead of leaving a stub line.
        val chosen = LinkedHashSet(beepChannels)
        val channels = Bands.channels20(currentBand)
        val perRow = 6
        var rows = (channels.size + perRow - 1) / perRow
        if (rows > 1 && channels.size % perRow in 1..2) rows--
        val base = channels.size / rows
        val tail = channels.size % rows
        val slots = base + if (tail > 0) tail else 0
        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        fun paintChip(chip: TextView, on: Boolean) {
            chip.setBackgroundResource(if (on) R.drawable.bg_button_accent else R.drawable.bg_button)
            chip.setTextColor(getColor(if (on) R.color.accent else R.color.text_dim))
            chip.setTypeface(null, if (on) Typeface.BOLD else Typeface.NORMAL)
        }

        var index = 0
        for (r in 0 until rows) {
            // The leftover chips ride along on the first row: 13 channels become 7 + 6.
            val count = if (r == 0) base + tail else base
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (slot in 0 until slots) {
                val params = LinearLayout.LayoutParams(0, dp(26), 1f).apply { marginStart = dp(2) }
                if (slot >= count) {
                    // Keep the cells of a short row the same width, so every column stays aligned.
                    line.addView(View(this), params)
                    continue
                }
                val num = Bands.channelNumber(channels[index++].toFloat())
                val chip = TextView(this).apply {
                    text = num.toString()
                    textSize = 10f
                    gravity = Gravity.CENTER
                    includeFontPadding = false
                    isClickable = true
                    isFocusable = false
                }
                paintChip(chip, num in chosen)
                chip.setOnClickListener {
                    if (num in chosen) {
                        chosen.remove(num)
                    } else {
                        if (chosen.size >= MAX_BEEP_CHANNELS) {
                            toast(getString(R.string.beep_channels_limit, MAX_BEEP_CHANNELS))
                            return@setOnClickListener
                        }
                        chosen.add(num)
                    }
                    paintChip(chip, num in chosen)
                }
                line.addView(chip, params)
            }
            grid.addView(line, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = if (r == 0) 0 else dp(3) })
        }
        box.addView(grid)
        box.addView(hint(R.string.beep_channels_hint))

        // Alarm rhythm: on/off per beep, beeps per cycle, and the pause between cycles. Off keeps
        // the old behaviour, one continuous tone while the condition holds.
        box.addView(title(R.string.beep_pattern))
        val cbPattern = CheckBox(this).apply {
            setText(R.string.beep_pattern_on)
            isChecked = beepPattern
        }
        box.addView(cbPattern)
        // Four numbers on a 2x2 grid: stacked one per row they took ~270 dp, more than the whole
        // rest of the panel.
        val rhythmEdits = numberGrid(
            box,
            listOf(
                R.string.beep_on_ms to beepOnMs,
                R.string.beep_off_ms to beepOffMs,
                R.string.beep_bursts to beepBursts,
                R.string.beep_cycle_ms to beepCycleMs,
            )
        )
        val editOn = rhythmEdits[0]
        val editOff = rhythmEdits[1]
        val editBursts = rhythmEdits[2]
        val editCycle = rhythmEdits[3]
        val rhythm = listOf(editOn, editOff, editBursts, editCycle)
        fun syncRhythm() {
            for (f in rhythm) {
                f.isEnabled = cbPattern.isChecked
                f.alpha = if (cbPattern.isChecked) 1f else 0.4f
            }
        }
        cbPattern.setOnCheckedChangeListener { _, _ -> syncRhythm() }
        syncRhythm()
        box.addView(hint(R.string.beep_pattern_hint))

        val dialog = AlertDialog.Builder(this)
            .setView(panelScroll(box))
            .setPositiveButton(R.string.apply) { _, _ ->
                beepPin = editPin.text.toString().toIntOrNull()?.coerceIn(0, 48) ?: DEFAULT_BEEP_PIN
                beepFreq = editFreq.text.toString().toIntOrNull()?.coerceIn(100, 20000)
                    ?: DEFAULT_BEEP_FREQ
                beepMax = maxVol
                beepMode = if (rbRate.isChecked) 1 else 0
                beepActive = rbActive.isChecked
                beepChannels.clear()
                beepChannels.addAll(chosen)
                beepPattern = cbPattern.isChecked
                beepOnMs = editOn.text.toString().toIntOrNull()?.coerceIn(BEEP_MIN_MS, BEEP_MAX_MS)
                    ?: DEFAULT_BEEP_ON_MS
                beepOffMs = editOff.text.toString().toIntOrNull()?.coerceIn(BEEP_MIN_MS, BEEP_MAX_MS)
                    ?: DEFAULT_BEEP_OFF_MS
                beepBursts = editBursts.text.toString().toIntOrNull()?.coerceIn(1, MAX_BEEP_BURSTS)
                    ?: DEFAULT_BEEP_BURSTS
                beepCycleMs = editCycle.text.toString().toIntOrNull()
                    ?.coerceIn(BEEP_MIN_MS, BEEP_MAX_CYCLE_MS) ?: DEFAULT_BEEP_CYCLE_MS
                beepSilence()             // the old tone (if any) must not outlive the change
                beepDuty = -1
                saveSideConfig()
                tell(getString(R.string.beep_saved, beepChannels.size))
            }
            .setNegativeButton(R.string.cancel, null)
            // The neutral slot is the leftmost button of the row: test | cancel | apply.
            .setNeutralButton(R.string.beep_test, null)
            .create()
            .also { it.backToMenu() }
        dialog.show()
        // A dialog button dismisses on click by default; wiring the button itself keeps the panel
        // open, and the test plays the values currently typed without applying them.
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            testBeep(
                editPin.text.toString().toIntOrNull()?.coerceIn(0, 48) ?: beepPin,
                editFreq.text.toString().toIntOrNull()?.coerceIn(100, 20000) ?: beepFreq,
                maxVol,
            )
        }
    }

    private fun panel(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(10))
        }
    }

    /**
     * Panel header on the main screen's type scale: a bold 13sp title over an 8sp dim subtitle —
     * the same pair every card on the first layer uses. Panels add their own divider under it.
     */
    private fun panelHeader(box: LinearLayout, titleId: Int, subtitle: String?) {
        box.addView(TextView(this).apply {
            setText(titleId)
            setTextColor(getColor(R.color.text))
            textSize = 13f
            letterSpacing = 0.04f
            setTypeface(typeface, Typeface.BOLD)
        })
        if (subtitle != null) box.addView(TextView(this).apply {
            text = subtitle
            setTextColor(getColor(R.color.text_dim))
            textSize = 8f
            letterSpacing = 0.08f
            setPadding(0, dp(3), 0, 0)
        })
    }

    /** Section heading inside a panel, the main screen's card-title style. */
    private fun title(id: Int) = TextView(this).apply {
        setText(id)
        setTextColor(getColor(R.color.text))
        textSize = 13f
        letterSpacing = 0.04f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(8), 0, dp(2))
    }

    /** Small dim caption above an input, the main screen's SdrLabel. */
    private fun fieldLabel(id: Int) = TextView(this).apply {
        setText(id)
        setTextColor(getColor(R.color.text_dim))
        textSize = 8f
        letterSpacing = 0.08f
        setPadding(0, dp(6), 0, dp(2))
    }

    /** Secondary line of explanation inside a panel. */
    private fun hint(id: Int) = TextView(this).apply {
        setText(id)
        setTextColor(getColor(R.color.text_dim))
        textSize = 9f
        setPadding(0, dp(2), 0, dp(2))
    }

    /** The eight stage colours as one strip; tapping anywhere opens the editor. */
    private fun stageStrip(onClick: () -> Unit): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        isClickable = true
        isFocusable = true
        setPadding(0, dp(4), 0, dp(2))
        setOnClickListener { onClick() }
        for (i in 0 until LED_STAGES) {
            val s = ledStages[i]
            addView(
                View(this@MainActivity).apply { setBackgroundColor(Color.rgb(s[0], s[1], s[2])) },
                LinearLayout.LayoutParams(0, dp(16), 1f).apply { marginEnd = dp(1) }
            )
        }
    }

    /**
     * A dialog clips a tall child instead of scrolling it, so the viewport gets a fixed height
     * and the panel scrolls inside it.
     */
    private fun panelScroll(box: LinearLayout): ScrollView {
        val height = (resources.displayMetrics.heightPixels * 0.62f).toInt()
        return ScrollView(this).apply {
            addView(box)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, height
            )
        }
    }

    /** Numeric input in the app's SdrEdit look, instead of the theme's underlined text box. */
    private fun numberEdit(value: Int) = EditText(this).apply {
        inputType = InputType.TYPE_CLASS_NUMBER
        setText(value.toString())
        textSize = 12f
        minHeight = dp(32)
        setBackgroundResource(R.drawable.bg_input)
        setPadding(dp(8), dp(2), dp(8), dp(2))
    }

    private fun numberField(box: LinearLayout, label: Int, value: Int): EditText {
        box.addView(fieldLabel(label))
        return numberEdit(value).apply { box.addView(this) }
    }

    /** Two compact number fields per row, label above the input: four values in two rows. */
    private fun numberGrid(box: LinearLayout, fields: List<Pair<Int, Int>>): List<EditText> {
        val edits = ArrayList<EditText>(fields.size)
        var row: LinearLayout? = null
        for ((i, field) in fields.withIndex()) {
            if (i % 2 == 0) {
                row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    if (i > 0) setPadding(0, dp(2), 0, 0)
                }
                box.addView(row)
            }
            val cell = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            cell.addView(fieldLabel(field.first))
            val edit = numberEdit(field.second)
            cell.addView(edit)
            edits.add(edit)
            row!!.addView(
                cell,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (i % 2 == 0) marginEnd = dp(8)
                }
            )
        }
        return edits
    }

    private fun slider(box: LinearLayout, label: String, value: Int, max: Int, onChange: (Int) -> Unit) {
        val caption = TextView(this).apply { text = "$label: $value" }
        val bar = SeekBar(this).apply {
            this.max = max
            progress = value
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    caption.text = "$label: $p"
                    onChange(p)
                }

                override fun onStartTrackingTouch(sb: SeekBar?) {}

                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        box.addView(caption)
        box.addView(bar)
    }

    /**
     * Label and slider on one line. The stacked version needs two rows per value, which does not
     * fit four sliders plus a pin and a mode selector into one dialog.
     */
    private fun compactSlider(box: LinearLayout, label: String, value: Int, max: Int, onChange: (Int) -> Unit) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val caption = TextView(this).apply {
            text = "$label: $value"
            textSize = 9f
            minWidth = dp(78)
            setTextColor(getColor(R.color.text_dim))
        }
        row.addView(caption, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        val bar = SeekBar(this).apply {
            this.max = max
            progress = value
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    caption.text = "$label: $p"
                    onChange(p)
                }

                override fun onStartTrackingTouch(sb: SeekBar?) {}

                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        row.addView(bar, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        box.addView(row)
    }

    /**
     * Writes the saved side settings to the board again. Pin levels, the LED colour and the tone
     * live in the firmware's RAM only, so this is how state comes back after a reconnect — and it
     * is what separates "save" from "done", which merely closes the panel.
     */
    private fun repushSide() {
        val d = dev ?: return
        if (d.hasLed) {
            // The strip boots dark, so an off switch is re-sent instead of the saved colour.
            if (!ledOn) sendSide("LED 0", null)
            else if (ledMode == LED_FIXED) applyLed(false)
            // A steady score keeps its stage, so without this the auto colour would not re-push.
            ledStage = -1
            ledScore = 0f
        }
        beepDuty = -1                       // the next sweep re-derives the tone from the conditions
        if (d.caps.contains("GPIO")) {
            for ((pin, state) in loadGpioPrefs()) sendSide("GPIO $pin $state", null)
        }
    }

    /** Colour scaled by the brightness slider, then handed to the capture thread. */
    private fun applyLed(notify: Boolean) {
        if (!ledOn) {                        // dark stays dark, whatever the saved colour says
            sendSide("LED 0", if (notify) getString(R.string.led_off) else null)
            return
        }
        val k = ledBright / 100f
        val r = (ledR * k).toInt().coerceIn(0, 255)
        val g = (ledG * k).toInt().coerceIn(0, 255)
        val b = (ledB * k).toInt().coerceIn(0, 255)
        sendSide("LED $r $g $b", if (notify) getString(R.string.led_applied, r, g, b) else null)
    }

    /**
     * Side commands ride between two captures: the firmware answers one line at a time, so
     * talking to it from the UI thread while the capture loop is mid-request corrupts both.
     */
    private fun sendSide(cmd: String, note: String?) {
        val d = dev
        if (d == null) {
            tell(getString(R.string.side_need_device))
            return
        }
        if (engine != null) {
            d.queue(cmd)
        } else {
            io.execute {
                try {
                    d.cmd(cmd)
                } catch (t: Throwable) {
                    main.post { toast(t.message.orEmpty()) }
                }
            }
        }
        note?.let { tell(it) }
    }

    /**
     * Plays exactly what the panel shows — one 0.8 s tone, or one cycle of the rhythm when the
     * pulsing is on — without writing anything back to the saved settings. Every phase is posted
     * up front, so the closing "BEEP 0" still fires if the panel is dismissed mid-test.
     */
    private fun testBeep(pin: Int, freq: Int, volume: Int) {
        if (dev == null) return
        val hz = if (beepActive) ACTIVE_BEEP_FREQ else freq.coerceIn(100, 20000)
        val tone = "BEEP ${pin.coerceIn(0, 48)} $hz ${dutyFor(volume).coerceAtLeast(1)}"
        if (!beepPattern) {
            beepCmd(tone)
            main.postDelayed({ beepCmd("BEEP 0") }, 800)
            return
        }
        var at = 0L
        repeat(beepBursts) {
            main.postDelayed({ beepCmd(tone) }, at)
            at += beepOnMs.toLong()
            main.postDelayed({ beepCmd("BEEP 0") }, at)
            at += beepOffMs.toLong()
        }
    }

    /**
     * One triggering channel sounds at 1/10 of the configured maximum, ten channels at 100 %.
     * "Red" means the same thing it does on screen: 26 dB above the floor on the occupancy
     * strip (30 dB is fully red) or 90 % on the detection bar.
     */
    private fun updateBeep(bars: FloatArray?, rates: FloatArray?, band: String) {
        if (bars == null || rates == null || dev?.hasBeep != true) return
        beepSeen = SystemClock.elapsedRealtime()
        if (beepChannels.isEmpty() || beepMax <= 0) {
            setBeepDuty(0)          // clearing the binding must also stop a tone in progress
            return
        }
        val chans = Bands.channels20(band)
        var hit = 0
        for (num in beepChannels) {
            var idx = -1
            for (i in chans.indices) {
                if (Bands.channelNumber(chans[i].toFloat()) == num) {
                    idx = i
                    break
                }
            }
            if (idx < 0 || idx >= bars.size || idx >= rates.size) continue
            val red = if (beepMode == 0) bars[idx] >= ACTIVITY_RED_DB else rates[idx] >= DETECT_RED_PCT
            if (red) hit++
        }
        setBeepDuty(beepMax * minOf(hit, MAX_BEEP_CHANNELS) / MAX_BEEP_CHANNELS)
    }

    /**
     * Status LED in "follow interference" mode: the stage colour of the quantised
     * [Dsp.interferenceScore] of the frame that just arrived. Only stage changes reach the wire,
     * at most every [LED_STAGE_MS]: the firmware spends about a millisecond per LED command (RMT
     * transfer plus a tick delay) and the capture loop has to keep its rhythm.
     */
    private fun updateLed(frame: SdrFrame) {
        if (!ledOn || ledMode != LED_AUTO || dev?.hasLed != true) return
        val score = Dsp.interferenceScore(frame.avgDb)
        val attack = if (score > ledScore) 0.5f else 0.1f    // jumps up, fades down
        ledScore += (score - ledScore) * attack
        val stage = staged(ledScore / 100f * LED_STAGES)
        if (stage == ledStage) return
        val now = SystemClock.elapsedRealtime()
        if (now - ledStamp < LED_STAGE_MS) return
        ledStage = stage
        ledStamp = now
        val s = ledStages[stage]
        val k = s[3] / 100f
        dev?.queue("LED ${(s[0] * k).toInt()} ${(s[1] * k).toInt()} ${(s[2] * k).toInt()}")
    }

    /** Quantises a position between stages, with hysteresis so a boundary value cannot flicker. */
    private fun staged(pos: Float): Int {
        val raw = pos.toInt().coerceIn(0, LED_STAGES - 1)
        if (ledStage < 0 || raw == ledStage) return raw
        if (raw > ledStage && pos < ledStage + 1 + LED_STAGE_HYST) return ledStage
        if (raw < ledStage && pos > ledStage - LED_STAGE_HYST) return ledStage
        return raw
    }

    /**
     * The loudness the condition asks for. Without a rhythm this is the tone itself; with one it
     * becomes the volume of the next on-phase and [beepTick] does the switching.
     */
    private fun setBeepDuty(pct: Int) {
        val v = pct.coerceIn(0, 100)
        if (v == beepDuty) return
        val now = SystemClock.elapsedRealtime()
        if (now - beepStamp < 200) return
        beepDuty = v
        beepStamp = now
        if (beepPattern) {
            when {
                v <= 0 -> beepSilence()
                // Only start a cycle when none is running: a volume change must not restart the
                // rhythm in the middle of a pause.
                !beepArmed -> {
                    beepBurst = 0
                    beepSchedule(0)
                }
            }
            return
        }
        val duty = dutyFor(v)
        beepSounding = duty != 0
        beepCmd(if (duty == 0) "BEEP 0" else "BEEP $beepPin ${toneHz()} $duty")
    }

    private fun beepSchedule(delayMs: Long) {
        beepArmed = true
        main.removeCallbacks(beepTick)
        main.postDelayed(beepTick, delayMs)
    }

    /** One step of the rhythm: flip the tone, then schedule the next flip. */
    private val beepTick = object : Runnable {
        override fun run() {
            beepArmed = false
            if (!beepPattern || beepDuty <= 0 || dev?.hasBeep != true) {
                beepSilence()
                return
            }
            // Only a completed sweep delivers the bars the condition is built from: once they stop
            // arriving (mode change, stalled sweep) the rhythm must not keep going on its own.
            if (SystemClock.elapsedRealtime() - beepSeen > BEEP_STALE_MS) {
                beepSilence()
                return
            }
            if (!beepSounding) {
                beepTone(true)
                beepSchedule(beepOnMs.toLong())
                return
            }
            beepTone(false)
            beepBurst++
            if (beepBurst < beepBursts) {
                beepSchedule(beepOffMs.toLong())
                return
            }
            beepBurst = 0
            beepSchedule(beepCycleMs.toLong())
        }
    }

    /** Puts the tone on or off; an on-phase uses the volume the condition currently asks for. */
    private fun beepTone(on: Boolean) {
        beepSounding = on
        beepCmd(if (on) "BEEP $beepPin ${toneHz()} ${dutyFor(beepDuty).coerceAtLeast(1)}" else "BEEP 0")
    }

    /** Stops the rhythm and the tone: the condition cleared, the settings changed, or we left. */
    private fun beepSilence() {
        beepCancel()
        if (!beepSounding) return
        beepSounding = false
        beepCmd("BEEP 0")
    }

    /** Drops the pending phase without touching the wire, for callers that silence on their own. */
    private fun beepCancel() {
        beepArmed = false
        main.removeCallbacks(beepTick)
        beepBurst = 0
    }

    /** Side command that works with and without the capture loop running. */
    private fun beepCmd(cmd: String) {
        val d = dev ?: return
        if (engine != null) {
            d.queue(cmd)
            return
        }
        io.execute {
            try {
                d.cmd(cmd)
            } catch (_: Throwable) {
            }
        }
    }

    /**
     * A passive buzzer is loudest at a 50 % square wave (RMS ∝ √duty) and falls silent at 100 %,
     * which is plain DC; so its volume is squeezed into 5-50 %. An active buzzer ignores the tone,
     * its loudness really is the average current, so the percentage maps straight onto the duty.
     */
    private fun dutyFor(volume: Int): Int {
        val v = volume.coerceIn(0, 100)
        if (v == 0) return 0
        return if (beepActive) v else (5 + v * 45 / 100).coerceIn(5, 50)
    }

    private fun toneHz(): Int = if (beepActive) ACTIVE_BEEP_FREQ else beepFreq

    private fun loadSideConfig() {
        val p = getSharedPreferences(PREFS, MODE_PRIVATE)
        ledPin = p.getInt(KEY_LED_PIN, DEFAULT_LED_PIN)
        ledR = p.getInt(KEY_LED_R, ledR)
        ledG = p.getInt(KEY_LED_G, ledG)
        ledB = p.getInt(KEY_LED_B, ledB)
        ledBright = p.getInt(KEY_LED_BRIGHT, ledBright)
        ledMode = p.getInt(KEY_LED_MODE, DEFAULT_LED_MODE)
        ledOn = p.getBoolean(KEY_LED_ON, true)
        ledStages = decodeStages(p.getString(KEY_LED_STAGES, null))
        beepPin = p.getInt(KEY_BEEP_PIN, DEFAULT_BEEP_PIN)
        beepFreq = p.getInt(KEY_BEEP_FREQ, DEFAULT_BEEP_FREQ)
        beepMax = p.getInt(KEY_BEEP_MAX, beepMax)
        beepMode = p.getInt(KEY_BEEP_MODE, 0)
        beepActive = p.getBoolean(KEY_BEEP_ACTIVE, false)
        beepPattern = p.getBoolean(KEY_BEEP_PATTERN, false)
        beepOnMs = p.getInt(KEY_BEEP_ON, DEFAULT_BEEP_ON_MS).coerceIn(BEEP_MIN_MS, BEEP_MAX_MS)
        beepOffMs = p.getInt(KEY_BEEP_OFF, DEFAULT_BEEP_OFF_MS).coerceIn(BEEP_MIN_MS, BEEP_MAX_MS)
        beepBursts = p.getInt(KEY_BEEP_BURSTS, DEFAULT_BEEP_BURSTS).coerceIn(1, MAX_BEEP_BURSTS)
        beepCycleMs = p.getInt(KEY_BEEP_CYCLE, DEFAULT_BEEP_CYCLE_MS)
            .coerceIn(BEEP_MIN_MS, BEEP_MAX_CYCLE_MS)
        beepChannels.clear()
        p.getString(KEY_BEEP_CHANS, "").orEmpty().split(',').forEach {
            it.trim().toIntOrNull()?.let(beepChannels::add)
        }
    }

    private fun saveSideConfig() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putInt(KEY_LED_PIN, ledPin)
            .putInt(KEY_LED_R, ledR)
            .putInt(KEY_LED_G, ledG)
            .putInt(KEY_LED_B, ledB)
            .putInt(KEY_LED_BRIGHT, ledBright)
            .putInt(KEY_LED_MODE, ledMode)
            .putBoolean(KEY_LED_ON, ledOn)
            .putString(KEY_LED_STAGES, encodeStages())
            .putInt(KEY_BEEP_PIN, beepPin)
            .putInt(KEY_BEEP_FREQ, beepFreq)
            .putInt(KEY_BEEP_MAX, beepMax)
            .putInt(KEY_BEEP_MODE, beepMode)
            .putBoolean(KEY_BEEP_ACTIVE, beepActive)
            .putString(KEY_BEEP_CHANS, beepChannels.joinToString(","))
            .putBoolean(KEY_BEEP_PATTERN, beepPattern)
            .putInt(KEY_BEEP_ON, beepOnMs)
            .putInt(KEY_BEEP_OFF, beepOffMs)
            .putInt(KEY_BEEP_BURSTS, beepBursts)
            .putInt(KEY_BEEP_CYCLE, beepCycleMs)
            .apply()
    }

    /** "r,g,b,bright;..." — one row per stage, so a mismatch keeps its per-field default. */
    private fun encodeStages(): String = ledStages.joinToString(";") { it.joinToString(",") }

    private fun decodeStages(text: String?): Array<IntArray> {
        val out = defaultLedStages()
        if (text.isNullOrBlank()) return out
        val rows = text.split(";")
        for (i in 0 until minOf(rows.size, LED_STAGES)) {
            val v = rows[i].split(",")
            if (v.size != 4) continue
            out[i] = IntArray(4) { j ->
                val max = if (j == 3) 100 else 255
                v[j].trim().toIntOrNull()?.coerceIn(0, max) ?: out[i][j]
            }
        }
        return out
    }

    private fun toast(text: String) =
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    // ---- connection -------------------------------------------------------
    private fun connect() {
        if (demoSource) {
            startDemo()
            return
        }
        val device = findBoard()
        if (device == null) {
            status(getString(R.string.status_no_board))
            return
        }
        if (!usb.hasPermission(device)) {
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            val pi = PendingIntent.getBroadcast(
                this, 0, Intent(ACTION_USB_PERMISSION).setPackage(packageName), flags
            )
            usb.requestPermission(device, pi)
            status(getString(R.string.status_wait_permission))
            return
        }
        open(device)
    }

    private fun open(device: UsbDevice) {
        status(getString(R.string.status_connecting, device.vendorId, device.productId))
        val freq = parseFreq()
        val manualGain = if (chkAgc.isChecked) -1 else seekGain.progress
        val bits = if (findViewById<Spinner>(R.id.spBits).selectedItemPosition == 1) 10 else 8
        val mode = currentMode
        val band = currentBand
        Thread {
            try {
                awaitClose()                     // the previous handle must be gone first
                val d = Espsdr(usb, device)
                d.open()
                d.upgradeBaud()      // lifts a UART bridge to 2 MBaud; a no-op on native USB
                val e = SdrEngine(d, frameListener, this@MainActivity)
                e.mode = mode
                e.band = band
                e.centerMhz = freq
                e.gain = manualGain
                e.iqBits = bits
                e.onChipSpectrum = findViewById<Spinner>(R.id.spCap).selectedItemPosition == 1
                e.paused = paused
                e.startEngine()
                main.post {
                    dev = d
                    engine = e
                    btnConnect.setText(R.string.disconnect)
                    btnRecord.isEnabled = true
                    btnGpio.isEnabled = true
                    Keepalive.start(this@MainActivity)   // keep the capture thread alive off-screen
                    // Pin levels, the LED colour and the tone live in the board's RAM only, so
                    // every reconnect replays what the panels left in the app's settings.
                    repushSide()
                    val link = when (d.transport) {
                        "USB" -> " · USB"
                        "UART" -> " · UART ${d.uartBaud / 1000} kBd"
                        else -> ""
                    }
                    val info = (if (d.buildDate.isEmpty()) d.info else "${d.info} · ${d.buildDate}") + link
                    status(getString(R.string.status_connected, info))
                }
            } catch (t: Throwable) {
                main.post {
                    status(getString(R.string.status_connect_failed, t.message.orEmpty()))
                    closeRadio()
                }
            }
        }.start()
    }

    /**
     * Demo source: no USB device and no permission dialog — the synthetic engine pushes frames
     * through exactly the same listener the board does, so every control stays testable on a desk.
     */
    private fun startDemo() {
        closeRadio()
        val e = DemoEngine(frameListener, this)
        e.mode = currentMode
        e.band = currentBand
        e.centerMhz = parseFreq()
        e.gain = if (chkAgc.isChecked) -1 else seekGain.progress
        e.rateIdx = findViewById<Spinner>(R.id.spRate).selectedItemPosition
        e.nfft = FFT_VAL[findViewById<Spinner>(R.id.spFft).selectedItemPosition]
        e.bandwidthMhz = BW_VAL[findViewById<Spinner>(R.id.spBw).selectedItemPosition]
        e.loOffsetMhz = LO_VAL[findViewById<Spinner>(R.id.spLo).selectedItemPosition]
        e.iqBits = if (findViewById<Spinner>(R.id.spBits).selectedItemPosition == 1) 10 else 8
        e.paused = paused
        e.startEngine()
        engine = e
        btnConnect.setText(R.string.disconnect)
        btnRecord.isEnabled = true
        status(getString(R.string.status_demo))
    }

    private fun disconnect() {
        closeRadio()
        status(getString(R.string.status_disconnected))
    }

    /** Author credit: hand the project page to a browser. */
    private fun openProjectPage() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB_URL)))
        } catch (_: Exception) {
            status(GITHUB_URL)
        }
    }

    // ---- self-update ------------------------------------------------------

    /** Asks GitHub for the newest release, then either reports "up to date" or offers it. */
    private fun checkForUpdates() {
        if (updateBusy) return
        val updater = Updater(this)
        updateBusy = true
        btnUpdate.isEnabled = false
        btnUpdate.setText("…")
        notify(getString(R.string.update_checking))
        Thread {
            val info = try {
                updater.fetchLatest()
            } catch (t: Throwable) {
                main.post {
                    endUpdate()
                    notify(getString(R.string.update_failed, t.message.orEmpty()))
                }
                return@Thread
            }
            main.post {
                endUpdate()
                val local = updater.currentVersion()
                when {
                    info == null -> notify(getString(R.string.update_no_release))
                    !Updater.isNewer(info.version, local) ->
                        notify(getString(R.string.update_latest, local))
                    info.apkUrl == null -> notify(getString(R.string.update_no_apk, info.version))
                    else -> askToInstall(updater, info, local)
                }
            }
        }.start()
    }

    private fun askToInstall(updater: Updater, info: UpdateInfo, local: String) {
        AlertDialog.Builder(this)
            .setTitle(R.string.update_available_title)
            .setMessage(getString(R.string.update_available_msg, info.version, local))
            .setPositiveButton(R.string.update_download) { _, _ -> downloadAndInstall(updater, info) }
            .setNegativeButton(R.string.update_later, null)
            .show()
    }

    /**
     * "Install unknown apps" is a per-app permission; without it the installer session will be
     * rejected, so send the user to the settings page first and let them tap again.
     */
    private fun downloadAndInstall(updater: Updater, info: UpdateInfo) {
        if (!updater.canInstall()) {
            status(getString(R.string.update_need_permission))
            updater.requestInstallPermission()
            return
        }
        updateBusy = true
        btnUpdate.isEnabled = false
        Thread {
            val apk = try {
                updater.download(info) { pct ->
                    main.post { status(getString(R.string.update_downloading, pct)) }
                }
            } catch (t: Throwable) {
                main.post {
                    endUpdate()
                    notify(getString(R.string.update_download_failed, t.message.orEmpty()))
                }
                return@Thread
            }
            main.post {
                endUpdate()
                // The installer has to be started from the visible activity, not a worker thread.
                try {
                    updater.install(apk)
                    status(getString(R.string.update_installing))
                } catch (t: Throwable) {
                    notify(getString(R.string.update_install_failed, t.message.orEmpty()))
                }
            }
        }.start()
    }

    private fun endUpdate() {
        updateBusy = false
        btnUpdate.isEnabled = true
        btnUpdate.setText(R.string.rail_update)
    }

    /** Results go to a toast as well: the status line is overwritten by the live spectrum. */
    private fun notify(text: String) {
        status(text)
        Toast.makeText(this, text, Toast.LENGTH_LONG).show()
    }

    private fun closeRadio() {
        val e = engine
        engine = null
        val rec = e?.recorder
        e?.recorder = null
        rec?.close()
        btnRecord.setText(R.string.record)
        btnRecord.isEnabled = false
        txtRecord.text = ""
        // The capture loop still owns the wire, so only drop the rhythm here: the close thread
        // silences the tone right after the loop has stopped.
        beepCancel()
        beepSounding = false
        val d = dev
        dev = null
        btnConnect.setText(R.string.connect)
        Keepalive.stop(this)
        if (e == null && d == null) return
        // Stop the capture loop *before* the handle goes away. Closing a connection under a
        // running bulkTransfer leaves the device half released, and the next openDevice() then
        // fails until the board is replugged — which is exactly the bug being fixed here.
        val t = Thread({
            try {
                e?.stopEngine()
            } catch (_: Throwable) {
            }
            if (e is Thread) {
                try {
                    e.join(2000)
                } catch (_: InterruptedException) {
                }
            }
            try {
                // A buzzer left sounding would keep howling after the app let go.
                if (d != null && d.hasBeep) d.setBeep(beepPin, beepFreq, 0)
            } catch (_: Throwable) {
            }
            beepDuty = -1
            try {
                d?.close()
            } catch (_: Throwable) {
            }
        }, "sdr-close")
        closing = t
        t.start()
    }

    /** Waits until the previous connection is really released; run this off the UI thread. */
    private fun awaitClose() {
        val t = closing ?: return
        closing = null
        try {
            t.join(3000)
        } catch (_: InterruptedException) {
        }
    }

    private fun findBoard(): UsbDevice? {
        val all = usb.deviceList.values
        // Prefer the native USB (/JTAG) port; the CH343 UART bridge is the fallback.
        return all.firstOrNull { it.vendorId == ESPRESSIF_VID }
            ?: all.firstOrNull { it.vendorId == CH343_VID }
    }

    private fun applyFreq() {
        val mhz = parseFreq()
        editFreq.setText(mhz.toString())
        engine?.centerMhz = mhz
        status(getString(R.string.status_tuned, mhz))
    }

    private fun parseFreq(): Int =
        editFreq.text.toString().toIntOrNull()?.coerceIn(100, 6000) ?: 2437

    private fun registerBatteryReceiver() {
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val sticky = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(batteryReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(batteryReceiver, filter)
        }
        sticky?.let { showBattery(it) }
    }

    /** Battery level in the top bar, with a bolt while charging. */
    private fun showBattery(intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) {
            txtBattery.text = ""
            return
        }
        val pct = level * 100 / scale
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        txtBattery.text = if (charging) "⚡$pct%" else "$pct%"
    }

    private fun registerUsbReceiver() {
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(permissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(permissionReceiver, filter)
        }
    }

    private fun status(text: String) {
        if (txtStatus.text.toString() == text) return   // skip the churn: identical text re-layouts anyway
        if (Looper.myLooper() == Looper.getMainLooper()) {
            txtStatus.text = text
        } else {
            main.post { txtStatus.text = text }
        }
    }
}
