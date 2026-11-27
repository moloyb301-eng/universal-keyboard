package com.universalkeyboard.hid

import android.view.KeyEvent

/** One key press: a HID usage plus the modifiers held with it. */
data class KeyStroke(val key: Int, val mods: Int = 0)

/** HID usage IDs (Keyboard/Keypad page) and modifier bits. */
object Hid {
    const val MOD_CTRL = 0x01
    const val MOD_SHIFT = 0x02
    const val MOD_ALT = 0x04
    const val MOD_GUI = 0x08 // Windows key

    const val ENTER = 0x28
    const val ESC = 0x29
    const val BACKSPACE = 0x2A
    const val TAB = 0x2B
    const val SPACE = 0x2C
    const val F1 = 0x3A
    const val F4 = 0x3D
    const val PRINT_SCREEN = 0x46
    const val INSERT = 0x49
    const val HOME = 0x4A
    const val PAGE_UP = 0x4B
    const val DELETE = 0x4C
    const val END = 0x4D
    const val PAGE_DOWN = 0x4E
    const val RIGHT = 0x4F
    const val LEFT = 0x50
    const val DOWN = 0x51
    const val UP = 0x52
    const val KEY_D = 0x07

    const val MOUSE_LEFT = 0x01
    const val MOUSE_RIGHT = 0x02
    const val MOUSE_MIDDLE = 0x04
}

/**
 * Translates text and Android key events into HID key strokes.
 * Assumes the host (Windows) uses the US keyboard layout.
 */
object KeyMap {
    private const val S = Hid.MOD_SHIFT

    private val table = HashMap<Char, KeyStroke>().apply {
        for (c in 'a'..'z') put(c, KeyStroke(0x04 + (c - 'a')))
        for (c in 'A'..'Z') put(c, KeyStroke(0x04 + (c - 'A'), S))
        "1234567890".forEachIndexed { i, c -> put(c, KeyStroke(0x1E + i)) }
        "!@#\$%^&*()".forEachIndexed { i, c -> put(c, KeyStroke(0x1E + i, S)) }
        put('\n', KeyStroke(Hid.ENTER))
        put('\t', KeyStroke(Hid.TAB))
        put(' ', KeyStroke(Hid.SPACE))
        listOf(
            "-_" to 0x2D, "=+" to 0x2E, "[{" to 0x2F, "]}" to 0x30, "\\|" to 0x31,
            ";:" to 0x33, "'\"" to 0x34, "`~" to 0x35, ",<" to 0x36, ".>" to 0x37, "/?" to 0x38,
        ).forEach { (pair, code) ->
            put(pair[0], KeyStroke(code))
            put(pair[1], KeyStroke(code, S))
        }
    }

    /** Key strokes needed to type [c] on the host; empty if it can't be typed. */
    fun strokesFor(c: Char): List<KeyStroke> {
        table[c]?.let { return listOf(it) }
        // Phone keyboards like to insert "smart" punctuation – send the plain ASCII version.
        val plain: String = when (c) {
            ' ', ' ', ' ' -> " "
            '‘', '’', '‚', '′' -> "'"
            '“', '”', '„', '″' -> "\""
            '–', '—', '−' -> "-"
            '…' -> "..."
            else -> return emptyList()
        }
        return plain.map { table.getValue(it) }
    }

    private val reverse: Map<KeyStroke, Char> by lazy {
        // '\n' and '\t' share their stroke with Enter/Tab; those are encoded as keys.
        table.entries.filter { it.key != '\n' && it.key != '\t' }.associate { it.value to it.key }
    }

    /** The character a key types with or without Shift (US layout), or null for non-character keys. */
    fun charFor(usage: Int, shift: Boolean): Char? = reverse[KeyStroke(usage, if (shift) S else 0)]

    /** Number of host keystrokes [text] turns into (used to count backspaces / arrows). */
    fun width(text: CharSequence): Int = text.sumOf { strokesFor(it).size }

    /** Keys that move the host caret or focus, so the typed-text mirror must reset. */
    fun isNavigation(keyCode: Int): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_TAB, KeyEvent.KEYCODE_ESCAPE,
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
        KeyEvent.KEYCODE_MOVE_HOME, KeyEvent.KEYCODE_MOVE_END,
        KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_PAGE_DOWN,
        KeyEvent.KEYCODE_INSERT, KeyEvent.KEYCODE_SYSRQ -> true
        in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12 -> true
        else -> false
    }

    /** HID usage for an Android key code, or null if unmapped. */
    fun usageFor(keyCode: Int): Int? = when (keyCode) {
        in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z -> 0x04 + (keyCode - KeyEvent.KEYCODE_A)
        in KeyEvent.KEYCODE_1..KeyEvent.KEYCODE_9 -> 0x1E + (keyCode - KeyEvent.KEYCODE_1)
        KeyEvent.KEYCODE_0 -> 0x27
        in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12 -> Hid.F1 + (keyCode - KeyEvent.KEYCODE_F1)
        KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> Hid.ENTER
        KeyEvent.KEYCODE_ESCAPE -> Hid.ESC
        KeyEvent.KEYCODE_DEL -> Hid.BACKSPACE
        KeyEvent.KEYCODE_FORWARD_DEL -> Hid.DELETE
        KeyEvent.KEYCODE_TAB -> Hid.TAB
        KeyEvent.KEYCODE_SPACE -> Hid.SPACE
        KeyEvent.KEYCODE_DPAD_LEFT -> Hid.LEFT
        KeyEvent.KEYCODE_DPAD_RIGHT -> Hid.RIGHT
        KeyEvent.KEYCODE_DPAD_UP -> Hid.UP
        KeyEvent.KEYCODE_DPAD_DOWN -> Hid.DOWN
        KeyEvent.KEYCODE_MOVE_HOME -> Hid.HOME
        KeyEvent.KEYCODE_MOVE_END -> Hid.END
        KeyEvent.KEYCODE_PAGE_UP -> Hid.PAGE_UP
        KeyEvent.KEYCODE_PAGE_DOWN -> Hid.PAGE_DOWN
        KeyEvent.KEYCODE_INSERT -> Hid.INSERT
        KeyEvent.KEYCODE_SYSRQ -> Hid.PRINT_SCREEN
        KeyEvent.KEYCODE_MINUS -> 0x2D
        KeyEvent.KEYCODE_EQUALS -> 0x2E
        KeyEvent.KEYCODE_LEFT_BRACKET -> 0x2F
        KeyEvent.KEYCODE_RIGHT_BRACKET -> 0x30
        KeyEvent.KEYCODE_BACKSLASH -> 0x31
        KeyEvent.KEYCODE_SEMICOLON -> 0x33
        KeyEvent.KEYCODE_APOSTROPHE -> 0x34
        KeyEvent.KEYCODE_GRAVE -> 0x35
        KeyEvent.KEYCODE_COMMA -> 0x36
        KeyEvent.KEYCODE_PERIOD -> 0x37
        KeyEvent.KEYCODE_SLASH -> 0x38
        else -> null
    }

    /** HID modifier bits from an Android KeyEvent's meta state. */
    fun modsFor(event: KeyEvent): Int {
        var m = 0
        if (event.isCtrlPressed) m = m or Hid.MOD_CTRL
        if (event.isShiftPressed) m = m or Hid.MOD_SHIFT
        if (event.isAltPressed) m = m or Hid.MOD_ALT
        if (event.isMetaPressed) m = m or Hid.MOD_GUI
        return m
    }
}
