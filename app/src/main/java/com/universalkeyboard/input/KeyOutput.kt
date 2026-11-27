package com.universalkeyboard.input

import com.universalkeyboard.hid.HidController
import com.universalkeyboard.hid.KeyStroke
import com.universalkeyboard.hid.Modifiers

/**
 * Somewhere typed keys can go. Keys arrive as HID strokes because that's what
 * the input path ([HidInputConnection], shortcut bar) already produces; a sink
 * that isn't Bluetooth translates them.
 */
interface KeySink {
    /** True if keys sent now would arrive. */
    val isReady: Boolean

    /**
     * Presses each stroke in order. [firstMods] (one-shot modifiers) applies to
     * the first stroke only; held modifiers apply to all unless [raw] is set.
     */
    fun tap(strokes: List<KeyStroke>, firstMods: Int = 0, raw: Boolean = false)

    /** Modifiers locked on the shortcut bar, held until changed. */
    fun setHeldModifiers(mods: Int)
}

/** The Bluetooth host: the original, default target. */
object HidSink : KeySink {
    override val isReady: Boolean get() = HidController.state == HidController.State.CONNECTED
    override fun tap(strokes: List<KeyStroke>, firstMods: Int, raw: Boolean) = HidController.tap(strokes, firstMods, raw)
    override fun setHeldModifiers(mods: Int) = HidController.setHeldModifiers(mods)
}

/**
 * Sends the phone keyboard's output to the selected target: the Bluetooth host
 * ("Keyboard → PC", the default) or the Claude terminal ("Keyboard → Claude").
 * Mouse input is not routed here; it always goes to the host.
 *
 * Main-thread only.
 */
object KeyOutput {
    enum class Target { PC, CLAUDE }

    /** Set while the Claude terminal exists; null means only the host is available. */
    var terminal: KeySink? = null

    var listener: ((Target) -> Unit)? = null

    var target = Target.PC
        set(value) {
            if (field == value) return
            // Modifiers locked for one target must not leak into the other
            // (e.g. Alt left held down on the host while typing to Claude).
            Modifiers.clear()
            field = value
            listener?.invoke(value)
        }

    val sink: KeySink get() = if (target == Target.CLAUDE) terminal ?: HidSink else HidSink

    fun tap(strokes: List<KeyStroke>, firstMods: Int = 0, raw: Boolean = false) = sink.tap(strokes, firstMods, raw)

    fun setHeldModifiers(mods: Int) = sink.setHeldModifiers(mods)
}
