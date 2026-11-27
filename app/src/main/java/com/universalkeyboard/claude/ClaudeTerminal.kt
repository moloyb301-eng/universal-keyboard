package com.universalkeyboard.claude

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.universalkeyboard.hid.KeyStroke
import com.universalkeyboard.input.KeySink
import org.json.JSONObject
import java.io.ByteArrayInputStream

/**
 * The terminal for the Claude tab: a WebView running the bundled xterm.js page
 * (assets/terminal), which connects to the bridge on 127.0.0.1.
 *
 * It is also a [KeySink], so "Keyboard → Claude" feeds it the same strokes the
 * Bluetooth host would get; [TerminalKeys] turns them into terminal input.
 *
 * Main-thread only.
 */
@SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
class ClaudeTerminal(context: Context, private val onStatus: (Status, String) -> Unit) : KeySink {

    enum class Status { IDLE, CONNECTING, CONNECTED, DISCONNECTED, BAD_CODE, ENDED, REPLACED, ERROR }

    private val main = Handler(Looper.getMainLooper())
    private val pendingJs = mutableListOf<String>()
    private var pageReady = false
    private var heldMods = 0

    var status = Status.IDLE
        private set

    /** True once the WebView's renderer died; the owner must create a new terminal. */
    var isDead = false
        private set

    val view: WebView = WebView(context).apply {
        // The phone keyboard must stay attached to KeyCaptureView, so the
        // terminal never takes focus; typing reaches it through tap().
        isFocusable = false
        isFocusableInTouchMode = false
        isLongClickable = false
        setOnLongClickListener { true }
        setBackgroundColor(0xFF1B1D22.toInt())
        settings.javaScriptEnabled = true
        // Bundled assets still load with these off; nothing else needs files.
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        webViewClient = Client()
        addJavascriptInterface(JsHost(), "UkHost")
        loadUrl(PAGE)
    }

    override val isReady: Boolean get() = status == Status.CONNECTED

    override fun tap(strokes: List<KeyStroke>, firstMods: Int, raw: Boolean) {
        val text = TerminalKeys.encode(strokes, firstMods, if (raw) 0 else heldMods)
        if (text.isNotEmpty()) runJs("uk.input(${JSONObject.quote(text)})")
    }

    override fun setHeldModifiers(mods: Int) {
        // Nothing is "held" in a terminal; locked modifiers are applied to each key in tap().
        heldMods = mods
    }

    /** Connects to the bridge, replacing any current connection. */
    fun connect(port: Int, code: String) = runJs("uk.connect($port, ${JSONObject.quote(code)})")

    fun disconnect() = runJs("uk.disconnect()")

    fun destroy() {
        pendingJs.clear()
        view.destroy()
    }

    private fun runJs(js: String) {
        if (isDead) return
        if (pageReady) view.evaluateJavascript(js, null) else pendingJs += js
    }

    private fun setStatus(s: Status, detail: String) {
        status = s
        onStatus(s, detail)
    }

    /** Called by terminal.js. Runs on a WebView binder thread, so hop to main. */
    private inner class JsHost {
        @JavascriptInterface
        fun onStatus(state: String, detail: String) {
            val s = Status.entries.firstOrNull { it.name.equals(state, ignoreCase = true) } ?: return
            main.post { if (!isDead) setStatus(s, detail) }
        }
        // Phase B hook: a request from the bridge to type a script on the
        // Bluetooth host would arrive here, and must be confirmed by the user
        // before it goes to HidSink.
    }

    private inner class Client : WebViewClient() {
        // Only the bundled page; links printed in the terminal must not navigate it away.
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true

        // Enforces "no remote loads": anything outside the bundled folder is refused.
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            if (request.url.toString().startsWith(PAGE_DIR)) return null
            return WebResourceResponse("text/plain", "utf-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))
        }

        override fun onPageFinished(view: WebView, url: String) {
            if (pageReady) return
            pageReady = true
            pendingJs.forEach { view.evaluateJavascript(it, null) }
            pendingJs.clear()
        }

        // Without this, a crashed or reclaimed renderer would take the whole app
        // (and the Bluetooth link) down with it.
        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            isDead = true
            setStatus(Status.ERROR, "The terminal view stopped. Tap Reconnect to restart it.")
            return true
        }
    }

    private companion object {
        const val PAGE_DIR = "file:///android_asset/terminal/"
        const val PAGE = PAGE_DIR + "index.html"
    }
}
