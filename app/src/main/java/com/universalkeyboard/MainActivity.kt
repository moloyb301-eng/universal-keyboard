package com.universalkeyboard

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import com.universalkeyboard.hid.Hid
import com.universalkeyboard.hid.HidController
import com.universalkeyboard.hid.HidController.State
import com.universalkeyboard.hid.KeyStroke
import com.universalkeyboard.hid.Modifiers
import com.universalkeyboard.input.KeyCaptureView
import com.universalkeyboard.ui.TrackpadView
import kotlin.math.roundToInt

class MainActivity : Activity(), HidController.Listener {

    private lateinit var prefs: Prefs
    private lateinit var statusDot: TextView
    private lateinit var statusText: TextView
    private lateinit var trackpad: TrackpadView
    private lateinit var keyCapture: KeyCaptureView
    private lateinit var shortcutRow: LinearLayout

    private val main = Handler(Looper.getMainLooper())
    private val modifierButtons = mutableMapOf<Int, Button>()
    private var imeVisible = false
    private var askedToEnableBluetooth = false
    private var lastNotConnectedToast = 0L

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        prefs = Prefs(this)
        statusDot = findViewById(R.id.statusDot)
        statusText = findViewById(R.id.statusText)
        trackpad = findViewById(R.id.trackpad)
        keyCapture = findViewById(R.id.keyCapture)
        shortcutRow = findViewById(R.id.shortcutRow)

        findViewById<View>(R.id.statusBar).setOnClickListener { ensureReady(userInitiated = true) }
        findViewById<Button>(R.id.btnPair).setOnClickListener { startPairing() }
        findViewById<Button>(R.id.btnHosts).setOnClickListener { showHosts() }
        findViewById<Button>(R.id.btnKeyboard).setOnClickListener { toggleKeyboard() }
        findViewById<Button>(R.id.btnSettings).setOnClickListener { showSettings() }
        setupMouseButton(findViewById(R.id.btnLeft), Hid.MOUSE_LEFT)
        setupMouseButton(findViewById(R.id.btnRight), Hid.MOUSE_RIGHT)

        trackpad.onClick = { keyCapture.resetMirror() }
        buildShortcutRow()
        Modifiers.listener = { refreshModifierButtons() }
        applyPrefs()

        if (Build.VERSION.SDK_INT >= 30) {
            findViewById<View>(R.id.root).setOnApplyWindowInsetsListener { v, insets ->
                imeVisible = insets.isVisible(WindowInsets.Type.ime())
                v.onApplyWindowInsets(insets)
            }
        }

        HidController.init(applicationContext)
    }

    override fun onStart() {
        super.onStart()
        HidController.addListener(this)
        ensureReady(userInitiated = false)
    }

    override fun onResume() {
        super.onResume()
        main.postDelayed({ keyCapture.showKeyboard() }, 300)
    }

    override fun onStop() {
        HidController.removeListener(this)
        super.onStop()
    }

    override fun onDestroy() {
        if (isFinishing) {
            Modifiers.clear()
            HidController.shutdown()
        }
        super.onDestroy()
    }

    /** Back keeps the app (and the Bluetooth link) alive instead of closing it. */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        moveTaskToBack(true)
    }

    // ------------------------------------------------------------------ Bluetooth setup

    private fun ensureReady(userInitiated: Boolean) {
        if (!HidController.hasPermissions(this)) {
            if (Build.VERSION.SDK_INT >= 31) {
                requestPermissions(
                    arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE),
                    REQ_PERMISSIONS,
                )
            }
            HidController.start() // reports NO_PERMISSION
            return
        }
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter != null && !adapter.isEnabled && (userInitiated || !askedToEnableBluetooth)) {
            askedToEnableBluetooth = true
            startActivityForResult(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), REQ_ENABLE_BT)
        }
        HidController.start()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PERMISSIONS) return
        if (HidController.hasPermissions(this)) {
            ensureReady(userInitiated = false)
        } else if (Build.VERSION.SDK_INT >= 31 && !shouldShowRequestPermissionRationale(Manifest.permission.BLUETOOTH_CONNECT)) {
            Toast.makeText(this, "Allow “Nearby devices” for Universal Keyboard", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQ_ENABLE_BT -> HidController.start()
            REQ_DISCOVERABLE ->
                if (resultCode == RESULT_CANCELED) {
                    Toast.makeText(this, "Pairing mode cancelled", Toast.LENGTH_SHORT).show()
                } else {
                    showPairingInstructions(resultCode)
                }
        }
    }

    private fun startPairing() {
        if (HidController.state !in setOf(State.READY, State.CONNECTING, State.CONNECTED)) {
            Toast.makeText(this, statusText.text, Toast.LENGTH_SHORT).show()
            ensureReady(userInitiated = true)
            return
        }
        val intent = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
            .putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, DISCOVERABLE_SECONDS)
        try {
            startActivityForResult(intent, REQ_DISCOVERABLE)
        } catch (e: SecurityException) {
            Toast.makeText(this, "Bluetooth permission missing", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showPairingInstructions(seconds: Int) {
        val name = HidController.phoneName()
        val minutes = (seconds / 60).coerceAtLeast(1)
        dialog()
            .setTitle("Pairing mode on")
            .setMessage(
                "Your phone is visible as “$name” for about $minutes min.\n\n" +
                    "On your Windows handheld:\n" +
                    "1. Open Settings → Bluetooth & devices\n" +
                    "2. Tap “Add device” → Bluetooth\n" +
                    "3. Pick “$name” and confirm the code on both screens\n\n" +
                    "Next time, it reconnects by itself or via “Hosts”.",
            )
            .setPositiveButton("OK", null)
            .show()
    }

    private fun showHosts() {
        val devices = HidController.pairedDevices().sortedBy {
            if (it.bluetoothClass?.majorDeviceClass == BluetoothClass.Device.Major.COMPUTER) 0 else 1
        }
        if (devices.isEmpty()) {
            dialog().setTitle("No paired devices")
                .setMessage("Tap “Pair” first and add this phone from your handheld's Bluetooth settings.")
                .setPositiveButton("OK", null).show()
            return
        }
        val connected: BluetoothDevice? = HidController.host
        val labels = devices.map { d ->
            val n = HidController.nameOf(d)
            if (d == connected) "$n  ✓ connected" else n
        }.toTypedArray()
        val b = dialog().setTitle("Connect to")
            .setItems(labels) { _, i -> HidController.connect(devices[i]) }
            .setNegativeButton("Close", null)
        if (connected != null) b.setNeutralButton("Disconnect") { _, _ -> HidController.disconnect() }
        b.show()
    }

    override fun onHidStateChanged(state: State, host: BluetoothDevice?) {
        val (text, color) = when (state) {
            State.STARTING -> "Starting keyboard mode…" to R.color.status_wait
            State.NO_BLUETOOTH -> "This phone has no Bluetooth" to R.color.status_off
            State.NO_PERMISSION -> "Tap to allow Bluetooth (Nearby devices)" to R.color.status_off
            State.BLUETOOTH_OFF -> "Bluetooth is off. Tap to turn on" to R.color.status_off
            State.UNSUPPORTED -> "This phone can't act as a Bluetooth keyboard" to R.color.status_off
            State.NOT_REGISTERED -> "Keyboard mode stopped. Tap to restart" to R.color.status_off
            State.READY -> "Ready. Pair a new device or pick one in Hosts" to R.color.status_wait
            State.CONNECTING -> "Connecting…" to R.color.status_wait
            State.CONNECTED -> "Connected to ${host?.let { HidController.nameOf(it) } ?: "host"}" to R.color.status_ok
        }
        statusText.text = text
        statusDot.setTextColor(getColor(color))
        applyMouseRate()
    }

    private fun applyMouseRate() {
        HidController.mouseIntervalMs = (1000 / prefs.mouseRate(HidController.host?.address)).toLong()
    }

    // ------------------------------------------------------------------ keyboard / shortcuts

    private fun toggleKeyboard() {
        if (Build.VERSION.SDK_INT >= 30 && imeVisible) keyCapture.hideKeyboard() else keyCapture.showKeyboard()
    }

    private fun buildShortcutRow() {
        addModifier("Ctrl", Hid.MOD_CTRL)
        addModifier("Alt", Hid.MOD_ALT)
        addModifier("Shift", Hid.MOD_SHIFT)
        addModifier("Win", Hid.MOD_GUI)
        addKey("Esc", KeyStroke(Hid.ESC))
        addKey("Tab", KeyStroke(Hid.TAB))
        addKey("←", KeyStroke(Hid.LEFT), repeat = true)
        addKey("↑", KeyStroke(Hid.UP), repeat = true)
        addKey("↓", KeyStroke(Hid.DOWN), repeat = true)
        addKey("→", KeyStroke(Hid.RIGHT), repeat = true)
        addKey("Del", KeyStroke(Hid.DELETE), repeat = true)
        addKey("Home", KeyStroke(Hid.HOME))
        addKey("End", KeyStroke(Hid.END))
        addKey("PgUp", KeyStroke(Hid.PAGE_UP), repeat = true)
        addKey("PgDn", KeyStroke(Hid.PAGE_DOWN), repeat = true)
        addKey("Alt+Tab", KeyStroke(Hid.TAB, Hid.MOD_ALT))
        addKey("Task view", KeyStroke(Hid.TAB, Hid.MOD_GUI))
        addKey("Desktop", KeyStroke(Hid.KEY_D, Hid.MOD_GUI))
        addKey("Task Mgr", KeyStroke(Hid.ESC, Hid.MOD_CTRL or Hid.MOD_SHIFT))
        addKey("Alt+F4", KeyStroke(Hid.F4, Hid.MOD_ALT))
        addKey("PrtSc", KeyStroke(Hid.PRINT_SCREEN))
        addKey("Ctrl+Alt+Del", KeyStroke(Hid.DELETE, Hid.MOD_CTRL or Hid.MOD_ALT))
        for (i in 0 until 12) addKey("F${i + 1}", KeyStroke(Hid.F1 + i))
    }

    private fun addModifier(label: String, bit: Int) {
        val b = newKeyButton(label)
        b.setOnClickListener { Modifiers.cycle(bit) }
        modifierButtons[bit] = b
        shortcutRow.addView(b)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun addKey(label: String, stroke: KeyStroke, repeat: Boolean = false) {
        val b = newKeyButton(label)
        val press = {
            if (checkConnected()) {
                HidController.tap(listOf(stroke), Modifiers.takeOneShot())
                keyCapture.resetMirror()
            }
        }
        if (!repeat) {
            b.setOnClickListener { press() }
        } else {
            val repeater = object : Runnable {
                override fun run() {
                    press()
                    main.postDelayed(this, REPEAT_INTERVAL_MS)
                }
            }
            b.setOnTouchListener { v, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        v.isPressed = true
                        press()
                        main.postDelayed(repeater, REPEAT_DELAY_MS)
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        v.isPressed = false
                        main.removeCallbacks(repeater)
                    }
                }
                true
            }
        }
        shortcutRow.addView(b)
    }

    private fun newKeyButton(label: String): Button = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 14f
        setTextColor(getColor(R.color.text))
        minWidth = 0
        minimumWidth = 0
        minHeight = 0
        minimumHeight = 0
        stateListAnimator = null
        isFocusable = false
        background = getDrawable(R.drawable.key_bg)
        setPadding(dp(14), dp(10), dp(14), dp(10))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, dp(42),
        ).apply { marginEnd = dp(6) }
    }

    private fun refreshModifierButtons() {
        for ((bit, b) in modifierButtons) {
            when {
                Modifiers.locked and bit != 0 -> {
                    b.background = roundRect(getColor(R.color.accent), 0)
                    b.setTextColor(getColor(R.color.bg))
                }
                Modifiers.oneShot and bit != 0 -> {
                    b.background = roundRect(getColor(R.color.key), getColor(R.color.accent))
                    b.setTextColor(getColor(R.color.accent))
                }
                else -> {
                    b.background = getDrawable(R.drawable.key_bg)
                    b.setTextColor(getColor(R.color.text))
                }
            }
        }
    }

    private fun roundRect(fill: Int, stroke: Int) = GradientDrawable().apply {
        cornerRadius = dp(8).toFloat()
        setColor(fill)
        if (stroke != 0) setStroke(dp(2), stroke)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupMouseButton(button: Button, bit: Int) {
        button.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    HidController.setMouseButton(bit, true)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    HidController.setMouseButton(bit, false)
                    keyCapture.resetMirror()
                }
            }
            true
        }
    }

    private fun checkConnected(): Boolean {
        if (HidController.state == State.CONNECTED) return true
        val now = System.currentTimeMillis()
        if (now - lastNotConnectedToast > 2000) {
            lastNotConnectedToast = now
            Toast.makeText(this, "Not connected to a device yet", Toast.LENGTH_SHORT).show()
        }
        return false
    }

    // ------------------------------------------------------------------ settings

    private fun applyPrefs() {
        trackpad.pointerSpeed = prefs.pointerSpeed
        trackpad.scrollSpeed = prefs.scrollSpeed
        trackpad.naturalScroll = prefs.naturalScroll
        keyCapture.smartTyping = prefs.smartTyping
        applyMouseRate()
    }

    private fun showSettings() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(8), dp(22), 0)
        }
        fun label(t: String) = TextView(this).apply {
            text = t
            setTextColor(getColor(R.color.text))
            setPadding(0, dp(14), 0, dp(4))
        }
        val pointer = SeekBar(this).apply {
            max = 40; progress = ((prefs.pointerSpeed - 0.5f) * 10).roundToInt().coerceIn(0, 40)
        }
        val scroll = SeekBar(this).apply {
            max = 30; progress = ((prefs.scrollSpeed - 0.3f) * 10).roundToInt().coerceIn(0, 30)
        }
        val host = HidController.host
        val hostName = host?.let { HidController.nameOf(it) }
        val currentRate = prefs.mouseRate(host?.address)
        val rateGroup = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        Prefs.MOUSE_RATES.forEach { rate ->
            rateGroup.addView(RadioButton(this).apply {
                id = rate
                text = "$rate"
                setTextColor(getColor(R.color.text))
                isChecked = rate == currentRate
            })
        }
        val rateNote = TextView(this).apply {
            text = "Updates per second, saved for ${hostName ?: "new devices"}. Lower it if the cursor " +
                "trails behind your finger or keeps moving after you lift it (e.g. Steam Deck)."
            setTextColor(getColor(R.color.text_dim))
            textSize = 12f
        }
        val natural = CheckBox(this).apply {
            text = "Natural scrolling (content follows fingers)"
            isChecked = prefs.naturalScroll
        }
        val smart = CheckBox(this).apply {
            text = "Smart typing (predictions, autocorrect, swipe)"
            isChecked = prefs.smartTyping
        }
        val note = TextView(this).apply {
            text = "Leave smart typing off for the most reliable typing. Turn it on to use " +
                "Samsung Keyboard's predictions and swipe. Corrections are sent as backspaces and retyping."
            setTextColor(getColor(R.color.text_dim))
            textSize = 12f
            setPadding(0, dp(4), 0, dp(8))
        }
        box.addView(label("Pointer speed"))
        box.addView(pointer)
        box.addView(label("Scroll speed"))
        box.addView(scroll)
        box.addView(label(if (hostName != null) "Mouse update rate for $hostName" else "Mouse update rate"))
        box.addView(rateGroup)
        box.addView(rateNote)
        box.addView(natural)
        box.addView(smart)
        box.addView(note)

        dialog().setTitle("Settings")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Save") { _, _ ->
                prefs.pointerSpeed = 0.5f + pointer.progress / 10f
                prefs.scrollSpeed = 0.3f + scroll.progress / 10f
                prefs.naturalScroll = natural.isChecked
                prefs.smartTyping = smart.isChecked
                if (rateGroup.checkedRadioButtonId != View.NO_ID) {
                    prefs.setMouseRate(host?.address, rateGroup.checkedRadioButtonId)
                }
                applyPrefs()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ------------------------------------------------------------------ helpers

    private fun dialog() = AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()

    private companion object {
        const val REQ_PERMISSIONS = 1
        const val REQ_ENABLE_BT = 2
        const val REQ_DISCOVERABLE = 3
        const val DISCOVERABLE_SECONDS = 180
        const val REPEAT_DELAY_MS = 400L
        const val REPEAT_INTERVAL_MS = 50L
    }
}
