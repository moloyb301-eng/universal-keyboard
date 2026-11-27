package com.universalkeyboard.input

import android.text.Editable
import android.text.Selection
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.InputMethodManager
import com.universalkeyboard.hid.Hid
import com.universalkeyboard.hid.HidController
import com.universalkeyboard.hid.KeyMap
import com.universalkeyboard.hid.KeyStroke
import com.universalkeyboard.hid.Modifiers
import kotlin.math.max
import kotlin.math.min

/**
 * Lets the phone's own keyboard (Samsung Keyboard, Gboard, ...) type on the host.
 *
 * The keyboard edits a private text buffer exactly as if it were a normal text
 * field. After every edit we compare the buffer with what we've already typed on
 * the host ([hostText]) and send only the difference as HID keystrokes:
 * backspaces for removed characters, key presses for new ones, and arrow keys to
 * move the host caret when the edit isn't at the end. This makes autocorrect,
 * predictions, swipe typing and space-bar cursor control all work.
 *
 * The buffer is cleared (via restartInput) whenever the host's text field may
 * have changed under us: Enter, Tab, shortcuts, mouse clicks, etc.
 */
class HidInputConnection(private val owner: KeyCaptureView) : BaseInputConnection(owner, true) {

    private val buffer: Editable = Editable.Factory.getInstance().newEditable("")
    private var hostText = ""
    private var hostCursor = 0
    private var batchDepth = 0
    private val imm = owner.context.getSystemService(InputMethodManager::class.java)

    init {
        Selection.setSelection(buffer, 0)
    }

    val isEmpty: Boolean get() = buffer.isEmpty() && hostText.isEmpty()

    override fun getEditable(): Editable = buffer

    // ------------------------------------------------------------------ IME entry points

    override fun beginBatchEdit(): Boolean {
        batchDepth++
        return true
    }

    override fun endBatchEdit(): Boolean {
        if (batchDepth > 0) batchDepth--
        if (batchDepth == 0) syncNow()
        return batchDepth > 0
    }

    override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean =
        super.commitText(text, newCursorPosition).also { sync() }

    override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean =
        super.setComposingText(text, newCursorPosition).also { sync() }

    override fun setComposingRegion(start: Int, end: Int): Boolean =
        super.setComposingRegion(start, end).also { sync() }

    override fun finishComposingText(): Boolean =
        super.finishComposingText().also { sync() }

    override fun setSelection(start: Int, end: Int): Boolean =
        super.setSelection(start, end).also { sync() }

    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
        val missing = missingForDelete(beforeLength, afterLength)
        val result = super.deleteSurroundingText(beforeLength, afterLength)
        afterDelete(missing)
        return result
    }

    override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean {
        val missing = missingForDelete(beforeLength, afterLength)
        val result = super.deleteSurroundingTextInCodePoints(beforeLength, afterLength)
        afterDelete(missing)
        return result
    }

    /**
     * Backspace on an empty buffer must still delete on the host, so any part of
     * the requested deletion that falls outside our buffer is sent as raw keys.
     */
    private fun missingForDelete(before: Int, after: Int): Pair<Int, Int> {
        val (start, end) = selection()
        return (before - start).coerceAtLeast(0) to (after - (buffer.length - end)).coerceAtLeast(0)
    }

    private fun afterDelete(missing: Pair<Int, Int>) {
        val (missingBefore, missingAfter) = missing
        if (missingBefore > 0 || missingAfter > 0) {
            syncNow()
            send(List(missingBefore) { KeyStroke(Hid.BACKSPACE) } + List(missingAfter) { KeyStroke(Hid.DELETE) }, useOneShot = true)
        } else {
            sync()
        }
    }

    override fun sendKeyEvent(event: KeyEvent): Boolean {
        when (event.action) {
            KeyEvent.ACTION_DOWN -> handleKeyDown(event)
            KeyEvent.ACTION_MULTIPLE ->
                if (event.keyCode == KeyEvent.KEYCODE_UNKNOWN) event.characters?.let { commitText(it, 1) }
        }
        return true
    }

    override fun performEditorAction(actionCode: Int): Boolean {
        pressEnter()
        return true
    }

    override fun performContextMenuAction(id: Int): Boolean = true

    // ------------------------------------------------------------------ key events

    /** Handles a key press from the IME or a hardware keyboard. */
    fun handleKeyDown(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        val (start, end) = selection()

        // Hardware-keyboard chords like Ctrl+C go straight through.
        val chordMods = KeyMap.modsFor(event) and Hid.MOD_SHIFT.inv()
        if (chordMods != 0) {
            val usage = KeyMap.usageFor(keyCode) ?: return false
            send(listOf(KeyStroke(usage, KeyMap.modsFor(event))), useOneShot = true)
            requestReset()
            return true
        }

        when (keyCode) {
            KeyEvent.KEYCODE_DEL -> when {
                start != end -> { buffer.delete(start, end); syncNow() }
                start > 0 && !Modifiers.shortcutActive -> { buffer.delete(prevCharStart(start), start); syncNow() }
                else -> sendAndReset(Hid.BACKSPACE, resetIfShortcutOnly = true)
            }
            KeyEvent.KEYCODE_FORWARD_DEL -> when {
                start != end -> { buffer.delete(start, end); syncNow() }
                end < buffer.length && !Modifiers.shortcutActive -> { buffer.delete(end, nextCharEnd(end)); syncNow() }
                else -> sendAndReset(Hid.DELETE, resetIfShortcutOnly = true)
            }
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> pressEnter()
            KeyEvent.KEYCODE_DPAD_LEFT ->
                if (start == end && start > 0 && Modifiers.oneShot == 0) {
                    Selection.setSelection(buffer, prevCharStart(start)); syncNow()
                } else sendAndReset(Hid.LEFT)
            KeyEvent.KEYCODE_DPAD_RIGHT ->
                if (start == end && end < buffer.length && Modifiers.oneShot == 0) {
                    Selection.setSelection(buffer, nextCharEnd(end)); syncNow()
                } else sendAndReset(Hid.RIGHT)
            else -> {
                if (KeyMap.isNavigation(keyCode)) {
                    sendAndReset(KeyMap.usageFor(keyCode) ?: return false)
                } else {
                    val ch = event.getUnicodeChar(event.metaState)
                    if (ch == 0 || ch and KeyCharacterMap.COMBINING_ACCENT != 0) return false
                    commitText(String(Character.toChars(ch)), 1)
                }
            }
        }
        return true
    }

    private fun pressEnter() {
        BaseInputConnection.removeComposingSpans(buffer)
        syncNow()
        send(listOf(KeyStroke(Hid.ENTER)), useOneShot = true)
        requestReset()
    }

    // ------------------------------------------------------------------ mirroring

    private fun sync() {
        if (batchDepth == 0) syncNow()
    }

    private fun syncNow() {
        val text = buffer.toString()
        if (text != hostText) applyDiff(text)

        // Mirror caret moves (e.g. Samsung's space-bar swipe) with arrow keys.
        val (start, end) = selection()
        if (start == end && start != hostCursor && start <= hostText.length) {
            moveHostCaret(hostCursor, start)
            hostCursor = start
        }

        reportSelection()

        if (buffer.length > MAX_MIRROR_LENGTH && BaseInputConnection.getComposingSpanStart(buffer) < 0) {
            requestReset()
        }
    }

    private fun applyDiff(new: String) {
        val old = hostText
        val shorter = min(old.length, new.length)
        var prefix = 0
        while (prefix < shorter && old[prefix] == new[prefix]) prefix++
        var suffix = 0
        while (suffix < shorter - prefix && old[old.length - 1 - suffix] == new[new.length - 1 - suffix]) suffix++

        val removed = old.substring(prefix, old.length - suffix)
        val inserted = new.substring(prefix, new.length - suffix)

        moveHostCaret(hostCursor, old.length - suffix)
        val backspaces = List(KeyMap.width(removed)) { KeyStroke(Hid.BACKSPACE) }
        val typed = inserted.flatMap { KeyMap.strokesFor(it) }
        if (typed.isEmpty()) {
            send(backspaces, useOneShot = true)
        } else {
            send(backspaces, useOneShot = false, raw = true)
            send(typed, useOneShot = true)
        }

        hostText = new
        hostCursor = prefix + inserted.length
        if (inserted.contains('\n') || inserted.contains('\t')) requestReset()
    }

    /** Moves the host caret between two positions of [hostText] using arrow keys. */
    private fun moveHostCaret(from: Int, to: Int) {
        if (from == to) return
        val lo = min(from, to).coerceIn(0, hostText.length)
        val hi = max(from, to).coerceIn(0, hostText.length)
        val count = KeyMap.width(hostText.substring(lo, hi))
        val key = if (to < from) Hid.LEFT else Hid.RIGHT
        send(List(count) { KeyStroke(key) }, useOneShot = false, raw = true)
    }

    private fun send(strokes: List<KeyStroke>, useOneShot: Boolean, raw: Boolean = false) {
        if (strokes.isEmpty()) return
        if (!raw && Modifiers.shortcutActive) requestReset() // host ran a shortcut, not typed text
        HidController.tap(strokes, if (useOneShot) Modifiers.takeOneShot() else 0, raw)
    }

    private fun sendAndReset(usage: Int, resetIfShortcutOnly: Boolean = false) {
        val shortcut = Modifiers.shortcutActive
        send(listOf(KeyStroke(usage)), useOneShot = true)
        if (!resetIfShortcutOnly || shortcut) requestReset()
    }

    private fun requestReset() {
        owner.post { owner.resetMirror() }
    }

    private fun reportSelection() {
        imm.updateSelection(
            owner,
            Selection.getSelectionStart(buffer),
            Selection.getSelectionEnd(buffer),
            BaseInputConnection.getComposingSpanStart(buffer),
            BaseInputConnection.getComposingSpanEnd(buffer),
        )
    }

    private fun selection(): Pair<Int, Int> {
        val a = Selection.getSelectionStart(buffer)
        val b = Selection.getSelectionEnd(buffer)
        if (a < 0 || b < 0) return buffer.length to buffer.length
        return min(a, b) to max(a, b)
    }

    private fun prevCharStart(pos: Int): Int =
        if (pos >= 2 && Character.isLowSurrogate(buffer[pos - 1]) && Character.isHighSurrogate(buffer[pos - 2])) pos - 2 else pos - 1

    private fun nextCharEnd(pos: Int): Int =
        if (pos + 1 < buffer.length && Character.isHighSurrogate(buffer[pos]) && Character.isLowSurrogate(buffer[pos + 1])) pos + 2 else pos + 1

    private companion object {
        const val MAX_MIRROR_LENGTH = 200
    }
}
