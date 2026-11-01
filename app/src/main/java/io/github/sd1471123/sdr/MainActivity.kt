package io.github.sd1471123.sdr

import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.io.BufferedOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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

        /** Project page shown by the author credit. */
        const val GITHUB_URL = "https://github.com/sd1471123/"

        val RATES_TXT = arrayOf("80 MS/s", "40 MS/s", "20 MS/s", "10 MS/s", "8 MS/s", "4 MS/s")
        val FFT_TXT = arrayOf("FFT 256", "FFT 512", "FFT 1024", "FFT 2048")
        val FFT_VAL = intArrayOf(256, 512, 1024, 2048)
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

    private val main = Handler(Looper.getMainLooper())
    private var dev: Espsdr? = null
    private var engine: SdrEngine? = null
    private var updateBusy = false

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
                spectrum.setFrame(frame)
                val line = summary(frame)
                if (line.isNotEmpty()) status(line)
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
        btnRecord.isEnabled = false

        usb = getSystemService(Context.USB_SERVICE) as UsbManager
        registerUsbReceiver()
        registerBatteryReceiver()
        applyCutoutInsets()
        enterFullscreen()

        setupSpinners()
        setupLanguage()
        btnConnect.setOnClickListener { if (engine == null) connect() else disconnect() }
        findViewById<Button>(R.id.btnTune).setOnClickListener { applyFreq() }
        btnRecord.setOnClickListener { toggleRecord() }
        findViewById<Button>(R.id.btnPrevPreset).setOnClickListener { stepPreset(-1) }
        findViewById<Button>(R.id.btnNextPreset).setOnClickListener { stepPreset(1) }
        findViewById<TextView>(R.id.txtAuthor).setOnClickListener { openProjectPage() }
        btnUpdate.setOnClickListener { checkForUpdates() }
        spectrum.onTune = { freq -> tuneTo(freq) }
        seekGain.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                txtGain.text = value.toString()
                engine?.gain = value
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

    /** Status line: busiest channels, best detection rate and a clipping warning. */
    private fun summary(frame: SdrFrame): String {
        val parts = ArrayList<String>(3)
        val bars = frame.bars
        if (bars != null) {
            val busy = busiest(bars, frame.band)
            if (busy.isNotEmpty()) parts.add(busy)
        }
        val rates = frame.rates
        if (rates != null) {
            val top = topDetected(rates, frame.band)
            if (top.isNotEmpty()) parts.add(top)
        }
        if (frame.clipPct >= 0.5f) parts.add(getString(R.string.clip_pct, frame.clipPct))
        if (parts.isEmpty()) return ""
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

    private fun topDetected(rates: FloatArray, band: String): String {
        val chans = Bands.channels20(band)
        val n = minOf(rates.size, chans.size)
        if (n == 0) return ""
        var best = 0
        for (i in 1 until n) if (rates[i] > rates[best]) best = i
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

    // ---- connection -------------------------------------------------------
    private fun connect() {
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
        val manualGain = seekGain.progress
        val mode = currentMode
        val band = currentBand
        Thread {
            try {
                val d = Espsdr(usb, device)
                d.open()
                val e = SdrEngine(d, frameListener, this@MainActivity)
                e.mode = mode
                e.band = band
                e.centerMhz = freq
                e.gain = manualGain
                e.start()
                main.post {
                    dev = d
                    engine = e
                    btnConnect.setText(R.string.disconnect)
                    btnRecord.isEnabled = true
                    status(getString(R.string.status_connected, d.info))
                }
            } catch (t: Throwable) {
                main.post {
                    status(getString(R.string.status_connect_failed, t.message.orEmpty()))
                    closeRadio()
                }
            }
        }.start()
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
                status(getString(R.string.update_installing))
            }
            try {
                updater.install(apk)
            } catch (t: Throwable) {
                main.post { notify(getString(R.string.update_install_failed, t.message.orEmpty())) }
            }
        }.start()
    }

    private fun endUpdate() {
        updateBusy = false
        btnUpdate.isEnabled = true
        btnUpdate.setText(R.string.update_icon)
    }

    /** Results go to a toast as well: the status line is overwritten by the live spectrum. */
    private fun notify(text: String) {
        status(text)
        Toast.makeText(this, text, Toast.LENGTH_LONG).show()
    }

    private fun closeRadio() {
        val rec = engine?.recorder
        engine?.recorder = null
        rec?.close()
        btnRecord.setText(R.string.record)
        btnRecord.isEnabled = false
        txtRecord.text = ""
        engine?.stopEngine()
        engine = null
        val d = dev
        dev = null
        btnConnect.setText(R.string.connect)
        d?.let { old -> Thread { old.close() }.start() }
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
        if (Looper.myLooper() == Looper.getMainLooper()) {
            txtStatus.text = text
        } else {
            main.post { txtStatus.text = text }
        }
    }
}
