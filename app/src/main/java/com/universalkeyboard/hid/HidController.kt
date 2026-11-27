package com.universalkeyboard.hid

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppQosSettings
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Registers the phone as a Bluetooth HID keyboard + mouse and sends reports to
 * the connected host. State changes are delivered on the main thread; reports
 * are sent from a single background thread, in order.
 */
@SuppressLint("MissingPermission") // every entry point checks hasPermissions() first
object HidController {

    enum class State {
        STARTING, NO_BLUETOOTH, NO_PERMISSION, BLUETOOTH_OFF,
        UNSUPPORTED, NOT_REGISTERED, READY, CONNECTING, CONNECTED,
    }

    fun interface Listener {
        fun onHidStateChanged(state: State, host: BluetoothDevice?)
    }

    private const val TAG = "HidController"
    private const val SDP_NAME = "Universal Keyboard"
    private const val SDP_DESCRIPTION = "Keyboard and trackpad"
    private const val SDP_PROVIDER = "Universal Keyboard"
    private const val KEY_DELAY_MS = 4L
    private const val PREFS = "hid"
    private const val PREF_LAST_HOST = "last_host"

    private val main = Handler(Looper.getMainLooper())
    private val sender = Executors.newSingleThreadExecutor()
    private val listeners = CopyOnWriteArrayList<Listener>()

    private var appContext: Context? = null
    private var adapter: BluetoothAdapter? = null
    @Volatile private var hid: BluetoothHidDevice? = null
    private var proxyRequested = false
    private var appRegistered = false

    @Volatile var host: BluetoothDevice? = null
        private set
    var state = State.STARTING
        private set

    @Volatile private var heldMods = 0
    @Volatile private var buttons = 0
    private val pendX = AtomicInteger()
    private val pendY = AtomicInteger()
    private val pendWheel = AtomicInteger()
    private val pendPan = AtomicInteger()
    private val mouseQueued = AtomicBoolean(false)
    private var lastMouseSend = 0L

    /**
     * Minimum gap between mouse reports. Sending faster than the host's
     * Bluetooth link accepts queues reports up and makes the cursor trail
     * behind the finger, so slower hosts (e.g. Steam Deck) need a larger gap.
     */
    @Volatile var mouseIntervalMs = 8L

    // ------------------------------------------------------------------ lifecycle

    fun init(context: Context) {
        if (appContext != null) return
        val ctx = context.applicationContext
        appContext = ctx
        adapter = ctx.getSystemService(BluetoothManager::class.java)?.adapter
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= 33) {
            ctx.registerReceiver(bluetoothStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            ctx.registerReceiver(bluetoothStateReceiver, filter)
        }
    }

    fun hasPermissions(context: Context): Boolean =
        Build.VERSION.SDK_INT < 31 || (
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
                context.checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED
            )

    /** Gets the HID profile and registers the keyboard. Safe to call repeatedly. */
    fun start() {
        val ctx = appContext ?: return
        val a = adapter ?: return setState(State.NO_BLUETOOTH)
        if (!hasPermissions(ctx)) return setState(State.NO_PERMISSION)
        if (!a.isEnabled) return setState(State.BLUETOOTH_OFF)
        if (hid != null) {
            if (!appRegistered) registerApp()
            return
        }
        if (proxyRequested) return
        setState(State.STARTING)
        proxyRequested = try {
            a.getProfileProxy(ctx, serviceListener, BluetoothProfile.HID_DEVICE)
        } catch (e: SecurityException) {
            false
        }
        if (!proxyRequested) setState(State.UNSUPPORTED)
    }

    /** Unregisters the keyboard so the phone stops being a Bluetooth input device. */
    fun shutdown() {
        val h = hid
        try {
            if (appRegistered) h?.unregisterApp()
            if (h != null) adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, h)
        } catch (e: SecurityException) {
            Log.w(TAG, "shutdown", e)
        }
        hid = null
        proxyRequested = false
        appRegistered = false
        host = null
        setState(State.NOT_REGISTERED)
    }

    fun addListener(l: Listener) {
        listeners.add(l)
        l.onHidStateChanged(state, host)
    }

    fun removeListener(l: Listener) {
        listeners.remove(l)
    }

    // ------------------------------------------------------------------ hosts

    fun pairedDevices(): List<BluetoothDevice> = try {
        adapter?.bondedDevices?.toList().orEmpty()
    } catch (e: SecurityException) {
        emptyList()
    }

    fun nameOf(device: BluetoothDevice): String = try {
        device.name ?: device.address
    } catch (e: SecurityException) {
        device.address
    }

    fun phoneName(): String = try {
        adapter?.name ?: "this phone"
    } catch (e: SecurityException) {
        "this phone"
    }

    fun connect(device: BluetoothDevice) {
        try {
            if (hid?.connect(device) != true) Log.w(TAG, "connect() refused")
        } catch (e: SecurityException) {
            Log.w(TAG, "connect", e)
        }
    }

    fun disconnect() {
        val d = host ?: return
        try {
            hid?.disconnect(d)
        } catch (e: SecurityException) {
            Log.w(TAG, "disconnect", e)
        }
    }

    // ------------------------------------------------------------------ keyboard

    /**
     * Presses and releases each stroke in order. [firstMods] is added to the
     * first stroke only (one-shot modifiers). Locked modifiers stay held unless
     * [raw] is set, which sends exactly the stroke's own modifiers.
     */
    fun tap(strokes: List<KeyStroke>, firstMods: Int = 0, raw: Boolean = false) {
        if (strokes.isEmpty()) return
        sender.execute {
            strokes.forEachIndexed { i, s ->
                val held = heldMods
                val base = if (raw) 0 else held
                val mods = base or s.mods or (if (i == 0) firstMods else 0)
                sendKeyboard(mods, s.key)
                Thread.sleep(KEY_DELAY_MS)
                sendKeyboard(held, 0)
                Thread.sleep(KEY_DELAY_MS)
            }
        }
    }

    fun setHeldModifiers(mods: Int) {
        heldMods = mods
        sender.execute { sendKeyboard(heldMods, 0) }
    }

    private fun sendKeyboard(mods: Int, key: Int) {
        val d = host ?: return
        val report = ByteArray(HidDescriptor.KEYBOARD_REPORT_SIZE)
        report[0] = mods.toByte()
        report[2] = key.toByte()
        send(d, HidDescriptor.ID_KEYBOARD, report)
    }

    // ------------------------------------------------------------------ mouse

    fun moveMouse(dx: Int, dy: Int) {
        pendX.addAndGet(dx)
        pendY.addAndGet(dy)
        queueMouse()
    }

    fun scroll(wheel: Int, pan: Int) {
        pendWheel.addAndGet(wheel)
        pendPan.addAndGet(pan)
        queueMouse()
    }

    fun setMouseButton(bit: Int, down: Boolean) {
        val b = if (down) buttons or bit else buttons and bit.inv()
        buttons = b
        sender.execute { flushMouse(b, force = true) }
    }

    fun click(bit: Int) {
        setMouseButton(bit, true)
        setMouseButton(bit, false)
    }

    /**
     * Sends an empty mouse report as soon as a finger touches the trackpad, so
     * the Bluetooth link leaves power-saving mode before real movement starts.
     */
    fun wakeLink() {
        val b = buttons
        sender.execute { flushMouse(b, force = true) }
    }

    /** Coalesces touch moves into at most one report every few milliseconds. */
    private fun queueMouse() {
        if (!mouseQueued.compareAndSet(false, true)) return
        sender.execute {
            val wait = mouseIntervalMs - (System.currentTimeMillis() - lastMouseSend)
            if (wait > 0) Thread.sleep(wait)
            mouseQueued.set(false)
            flushMouse(buttons, force = false)
        }
    }

    private fun flushMouse(b: Int, force: Boolean) {
        var x = pendX.getAndSet(0)
        var y = pendY.getAndSet(0)
        var w = pendWheel.getAndSet(0)
        var p = pendPan.getAndSet(0)
        var sent = false
        while (x != 0 || y != 0 || w != 0 || p != 0 || (force && !sent)) {
            val cx = x.coerceIn(-127, 127)
            val cy = y.coerceIn(-127, 127)
            val cw = w.coerceIn(-127, 127)
            val cp = p.coerceIn(-127, 127)
            sendMouse(b, cx, cy, cw, cp)
            x -= cx; y -= cy; w -= cw; p -= cp
            sent = true
        }
        lastMouseSend = System.currentTimeMillis()
    }

    private fun sendMouse(b: Int, x: Int, y: Int, wheel: Int, pan: Int) {
        val d = host ?: return
        send(d, HidDescriptor.ID_MOUSE, byteArrayOf(b.toByte(), x.toByte(), y.toByte(), wheel.toByte(), pan.toByte()))
    }

    private fun send(device: BluetoothDevice, id: Int, report: ByteArray) {
        try {
            hid?.sendReport(device, id, report)
        } catch (e: SecurityException) {
            Log.w(TAG, "sendReport", e)
        }
    }

    // ------------------------------------------------------------------ internals

    private fun setState(s: State) {
        state = s
        val h = host
        listeners.forEach { it.onHidStateChanged(s, h) }
    }

    private fun registerApp() {
        val h = hid ?: return
        setState(State.STARTING)
        val sdp = BluetoothHidDeviceAppSdpSettings(
            SDP_NAME, SDP_DESCRIPTION, SDP_PROVIDER,
            BluetoothHidDevice.SUBCLASS1_COMBO, HidDescriptor.DESCRIPTOR,
        )
        // Ask for a low-latency link (11.25 ms, the usual figure for Bluetooth
        // mice). Some phones reject QoS settings, so fall back to defaults.
        val lowLatency = BluetoothHidDeviceAppQosSettings(
            BluetoothHidDeviceAppQosSettings.SERVICE_BEST_EFFORT,
            800, 9, 0, 11250, BluetoothHidDeviceAppQosSettings.MAX,
        )
        val executor = Executor { main.post(it) }
        val ok = try {
            h.registerApp(sdp, null, lowLatency, executor, hidCallback) ||
                h.registerApp(sdp, null, null, executor, hidCallback)
        } catch (e: SecurityException) {
            false
        }
        if (!ok) setState(State.NOT_REGISTERED)
    }

    private fun lastHost(): BluetoothDevice? {
        val address = appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ?.getString(PREF_LAST_HOST, null) ?: return null
        return pairedDevices().firstOrNull { it.address == address }
    }

    private fun rememberHost(device: BluetoothDevice) {
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ?.edit()?.putString(PREF_LAST_HOST, device.address)?.apply()
    }

    private fun resetLinkState() {
        hid = null
        proxyRequested = false
        appRegistered = false
        host = null
        buttons = 0
    }

    private val serviceListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (profile != BluetoothProfile.HID_DEVICE) return
            hid = proxy as BluetoothHidDevice
            registerApp()
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile != BluetoothProfile.HID_DEVICE) return
            resetLinkState()
            setState(if (adapter?.isEnabled == true) State.NOT_REGISTERED else State.BLUETOOTH_OFF)
        }
    }

    private val hidCallback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            appRegistered = registered
            if (!registered) {
                host = null
                setState(State.NOT_REGISTERED)
                return
            }
            setState(State.READY)
            // Reconnect to the last handheld we used, if it's around.
            (pluggedDevice ?: lastHost())?.let { connect(it) }
        }

        override fun onConnectionStateChanged(device: BluetoothDevice?, newState: Int) {
            device ?: return
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    host = device
                    rememberHost(device)
                    setState(State.CONNECTED)
                }
                BluetoothProfile.STATE_CONNECTING ->
                    if (host == null) setState(State.CONNECTING)
                BluetoothProfile.STATE_DISCONNECTED, BluetoothProfile.STATE_DISCONNECTING ->
                    if (host == null || host == device) {
                        host = null
                        buttons = 0
                        if (appRegistered) setState(State.READY)
                    }
            }
        }

        override fun onGetReport(device: BluetoothDevice?, type: Byte, id: Byte, bufferSize: Int) {
            val h = hid ?: return
            device ?: return
            try {
                when (id.toInt()) {
                    HidDescriptor.ID_KEYBOARD -> h.replyReport(device, type, id, ByteArray(HidDescriptor.KEYBOARD_REPORT_SIZE))
                    HidDescriptor.ID_MOUSE -> h.replyReport(device, type, id, ByteArray(HidDescriptor.MOUSE_REPORT_SIZE))
                    else -> h.reportError(device, BluetoothHidDevice.ERROR_RSP_INVALID_RPT_ID)
                }
            } catch (e: SecurityException) {
                Log.w(TAG, "onGetReport", e)
            }
        }

        override fun onSetReport(device: BluetoothDevice?, type: Byte, id: Byte, data: ByteArray?) {
            device ?: return
            try {
                hid?.reportError(device, BluetoothHidDevice.ERROR_RSP_SUCCESS)
            } catch (e: SecurityException) {
                Log.w(TAG, "onSetReport", e)
            }
        }

        override fun onVirtualCableUnplug(device: BluetoothDevice?) {
            if (device != null && device == host) {
                host = null
                if (appRegistered) setState(State.READY)
            }
        }
    }

    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)) {
                BluetoothAdapter.STATE_ON -> start()
                BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> {
                    resetLinkState()
                    setState(State.BLUETOOTH_OFF)
                }
            }
        }
    }
}
