package com.universalkeyboard.claude

import com.universalkeyboard.hid.Hid
import com.universalkeyboard.hid.KeyMap
import com.universalkeyboard.hid.KeyStroke

/**
 * Turns HID key strokes into the bytes a terminal program expects from an
 * xterm-compatible terminal: characters, control codes (Ctrl+C = 0x03), Esc
 * prefixes for Alt, and CSI sequences for arrows, Home/End and F-keys.
 */
object TerminalKeys {
    private const val ESC = "\u001b"

    /** Bytes for [usage] pressed with [mods], or null if a terminal has no meaning for it. */
    fun encode(usage: Int, mods: Int): String? {
        // The Windows key only makes sense on the PC.
        if (mods and Hid.MOD_GUI != 0) return null
        val shift = mods and Hid.MOD_SHIFT != 0
        val alt = mods and Hid.MOD_ALT != 0
        val ctrl = mods and Hid.MOD_CTRL != 0
        // xterm modifier parameter: 1 + Shift(1) + Alt(2) + Ctrl(4).
        val param = 1 + (if (shift) 1 else 0) + (if (alt) 2 else 0) + (if (ctrl) 4 else 0)

        fun cursor(end: Char) = if (param == 1) "$ESC[$end" else "$ESC[1;$param$end"
        fun tilde(code: Int) = if (param == 1) "$ESC[$code~" else "$ESC[$code;$param~"
        fun altPrefix(s: String) = if (alt) ESC + s else s

        return when (usage) {
            Hid.ENTER -> altPrefix("\r")
            Hid.ESC -> ESC
            Hid.BACKSPACE -> altPrefix(if (ctrl) "\u0008" else "\u007f")
            Hid.TAB -> if (shift) "$ESC[Z" else altPrefix("\t")
            Hid.UP -> cursor('A')
            Hid.DOWN -> cursor('B')
            Hid.RIGHT -> cursor('C')
            Hid.LEFT -> cursor('D')
            Hid.HOME -> cursor('H')
            Hid.END -> cursor('F')
            Hid.INSERT -> tilde(2)
            Hid.DELETE -> tilde(3)
            Hid.PAGE_UP -> tilde(5)
            Hid.PAGE_DOWN -> tilde(6)
            in Hid.F1..Hid.F1 + 3 -> {
                val end = "PQRS"[usage - Hid.F1]
                if (param == 1) "${ESC}O$end" else "$ESC[1;$param$end"
            }
            in Hid.F1 + 4..Hid.F1 + 11 -> tilde(F5_TO_F12[usage - Hid.F1 - 4])
            else -> {
                val ch = KeyMap.charFor(usage, shift) ?: return null
                altPrefix(if (ctrl) controlCode(ch) else ch.toString())
            }
        }
    }

    /** Encodes a whole [KeySink.tap] call; see there for how modifiers combine. */
    fun encode(strokes: List<KeyStroke>, firstMods: Int, heldMods: Int): String = buildString {
        strokes.forEachIndexed { i, s ->
            encode(s.key, heldMods or s.mods or (if (i == 0) firstMods else 0))?.let { append(it) }
        }
    }

    /** Ctrl+letter and friends, as a terminal's line discipline expects them. */
    private fun controlCode(ch: Char): String = when (ch) {
        in 'a'..'z' -> Char(ch - 'a' + 1).toString()
        in 'A'..'Z' -> Char(ch - 'A' + 1).toString()
        ' ', '@', '2' -> "\u0000"
        '[', '3' -> ESC
        '\\', '4' -> "\u001c"
        ']', '5' -> "\u001d"
        '^', '6' -> "\u001e"
        '_', '-', '7' -> "\u001f"
        '?', '8' -> "\u007f"
        else -> ch.toString()
    }

    private val F5_TO_F12 = intArrayOf(15, 17, 18, 19, 20, 21, 23, 24)
}
