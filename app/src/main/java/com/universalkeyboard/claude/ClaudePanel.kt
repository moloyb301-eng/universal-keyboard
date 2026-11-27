package com.universalkeyboard.claude

import android.app.Activity
import android.app.AlertDialog
import android.text.InputFilter
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.universalkeyboard.Prefs
import com.universalkeyboard.R
import com.universalkeyboard.claude.ClaudeTerminal.Status
import com.universalkeyboard.input.KeyOutput
import kotlin.math.roundToInt

/**
 * The Claude tab: connection status, Reconnect / Pair buttons and the terminal.
 *
 * The terminal (a WebView) is only created the first time it's needed, so the
 * app costs nothing extra for people who never open the tab.
 *
 * [onDialogClosed] gives the phone keyboard back to the key-capture view after
 * the pairing dialog borrowed it.
 */
class ClaudePanel(
    private val activity: Activity,
    root: View,
    private val prefs: Prefs,
    private val onDialogClosed: () -> Unit,
) {
    private val statusDot: TextView = root.findViewById(R.id.claudeStatusDot)
    private val statusText: TextView = root.findViewById(R.id.claudeStatusText)
    private val actionButton: Button = root.findViewById(R.id.btnClaudeAction)
    private val terminalHost: FrameLayout = root.findViewById(R.id.claudeTerminalHost)

    private var terminal: ClaudeTerminal? = null

    init {
        actionButton.setOnClickListener { reconnect() }
        root.findViewById<Button>(R.id.btnClaudePair).setOnClickListener { showPairingDialog() }
        showStatus(Status.IDLE, "")
    }

    /** Creates the terminal if needed and connects it, asking for the pairing code on first use. */
    fun start() {
        val t = terminal?.takeUnless { it.isDead } ?: createTerminal()
        when {
            prefs.bridgeCode.isEmpty() -> showPairingDialog()
            t.status == Status.IDLE -> t.connect(prefs.bridgePort, prefs.bridgeCode)
        }
    }

    fun destroy() {
        terminal?.let {
            if (KeyOutput.terminal === it) KeyOutput.terminal = null
            terminalHost.removeView(it.view)
            it.destroy()
        }
        terminal = null
    }

    private fun createTerminal(): ClaudeTerminal {
        destroy()
        val t = ClaudeTerminal(activity) { status, detail -> showStatus(status, detail) }
        terminalHost.addView(t.view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        terminal = t
        KeyOutput.terminal = t
        return t
    }

    private fun reconnect() {
        if (prefs.bridgeCode.isEmpty()) {
            showPairingDialog()
            return
        }
        val t = terminal?.takeUnless { it.isDead } ?: createTerminal()
        t.connect(prefs.bridgePort, prefs.bridgeCode)
    }

    private fun showStatus(status: Status, detail: String) {
        val notPaired = prefs.bridgeCode.isEmpty()
        val (text, color) = when (status) {
            Status.IDLE ->
                (if (notPaired) "Not paired. Start the bridge in Horus, then tap Pair." else "Not connected") to R.color.status_off
            Status.CONNECTING -> "Connecting to the bridge…" to R.color.status_wait
            Status.CONNECTED ->
                (if (detail == "resumed") "Connected · session resumed" else "Connected · new session") to R.color.status_ok
            Status.DISCONNECTED ->
                "Bridge not running? Start it in Horus. $detail".trim() to R.color.status_off
            Status.BAD_CODE -> "Pairing code rejected. Tap Pair and enter the current code." to R.color.status_off
            Status.ENDED -> "Session ended. Tap Restart for a new one." to R.color.status_wait
            Status.REPLACED -> "Another window took over the session." to R.color.status_wait
            Status.ERROR -> detail.ifEmpty { "Something went wrong" } to R.color.status_off
        }
        statusText.text = text
        statusDot.setTextColor(activity.getColor(color))
        actionButton.text = if (status == Status.ENDED) "Restart" else "Reconnect"
        actionButton.visibility = if (status == Status.CONNECTED || notPaired) View.GONE else View.VISIBLE
    }

    private fun showPairingDialog() {
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(8), dp(22), 0)
        }
        val note = TextView(activity).apply {
            text = "In Horus, start the bridge:\n\n    python3 bridge/claude_bridge.py\n\n" +
                "On first run it prints a pairing code. Enter it here once. " +
                "To see it again, add --show-code."
            setTextColor(activity.getColor(R.color.text_dim))
            textSize = 13f
        }
        val code = EditText(activity).apply {
            hint = "Pairing code (XXXX-XXXX-XXXX)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            filters = arrayOf(InputFilter.LengthFilter(24))
            setText(prefs.bridgeCode)
        }
        val port = EditText(activity).apply {
            hint = "Port"
            inputType = InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(InputFilter.LengthFilter(5))
            setText(prefs.bridgePort.toString())
        }
        box.addView(note)
        box.addView(code)
        box.addView(TextView(activity).apply {
            text = "Port (change only if you started the bridge with --port)"
            setTextColor(activity.getColor(R.color.text_dim))
            textSize = 12f
            setPadding(0, dp(10), 0, 0)
        })
        box.addView(port)

        AlertDialog.Builder(activity, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Pair with Claude Code")
            .setView(box)
            .setPositiveButton("Connect") { _, _ ->
                // Same normalisation as the bridge: dashes, spaces and case don't matter.
                val entered = code.text.toString().filter { it.isLetterOrDigit() }.uppercase()
                prefs.bridgeCode = entered
                prefs.bridgePort = port.text.toString().toIntOrNull()?.takeIf { it in 1..65535 } ?: Prefs.DEFAULT_BRIDGE_PORT
                if (entered.isNotEmpty()) reconnect() else showStatus(Status.IDLE, "")
            }
            .setNegativeButton("Cancel", null)
            .setOnDismissListener { onDialogClosed() }
            .show()
    }

    private fun dp(v: Int): Int = (v * activity.resources.displayMetrics.density).roundToInt()
}
