package com.universalkeyboard.input

import android.content.Context
import android.os.Build
import android.text.InputType
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager

/**
 * Invisible, focusable view that keeps the phone's keyboard open and receives
 * everything typed on it through [HidInputConnection].
 */
class KeyCaptureView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    private val imm = context.getSystemService(InputMethodManager::class.java)
    private var connection: HidInputConnection? = null

    /**
     * false: plain keys only (no predictions/autocorrect) – most reliable.
     * true:  normal text field – predictions, autocorrect and swipe typing.
     */
    var smartTyping = false
        set(value) {
            if (field == value) return
            field = value
            imm.restartInput(this)
        }

    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = if (smartTyping) {
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_AUTO_CORRECT
        } else {
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        outAttrs.imeOptions = EditorInfo.IME_ACTION_NONE or
            EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            EditorInfo.IME_FLAG_NO_FULLSCREEN or
            EditorInfo.IME_FLAG_NO_ENTER_ACTION or
            (if (smartTyping) 0 else EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING)
        outAttrs.initialSelStart = 0
        outAttrs.initialSelEnd = 0
        return HidInputConnection(this).also { connection = it }
    }

    fun showKeyboard() {
        requestFocus()
        if (Build.VERSION.SDK_INT >= 30) windowInsetsController?.show(WindowInsets.Type.ime())
        imm.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
    }

    fun hideKeyboard() {
        imm.hideSoftInputFromWindow(windowToken, 0)
    }

    /** Forget typed text – call when the host's caret/focus may have changed. */
    fun resetMirror() {
        val c = connection ?: return
        if (c.isEmpty) return
        imm.restartInput(this)
    }

    // Hardware keyboards attached to the phone (and IMEs that dispatch raw events).
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (isSystemKey(keyCode)) return super.onKeyDown(keyCode, event)
        return connection?.handleKeyDown(event) ?: super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (isSystemKey(keyCode)) return super.onKeyUp(keyCode, event)
        return true
    }

    private fun isSystemKey(keyCode: Int) = when (keyCode) {
        KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_APP_SWITCH,
        KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE,
        KeyEvent.KEYCODE_POWER, KeyEvent.KEYCODE_MENU,
        KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT,
        KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT,
        KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT,
        KeyEvent.KEYCODE_META_LEFT, KeyEvent.KEYCODE_META_RIGHT -> true
        else -> false
    }
}
