package com.universalkeyboard.hid

/**
 * Sticky Ctrl / Alt / Shift / Win from the shortcut bar.
 *
 * Tap once  -> one-shot: applies to the next key, then clears.
 * Tap again -> locked: held down on the host until tapped a third time
 *              (lets you hold Alt and tap Tab repeatedly to switch windows).
 *
 * Main-thread only.
 */
object Modifiers {
    var oneShot = 0
        private set
    var locked = 0
        private set

    var listener: (() -> Unit)? = null

    private const val SHORTCUT_MASK = Hid.MOD_CTRL or Hid.MOD_ALT or Hid.MOD_GUI

    /** True if the next key would be a shortcut rather than plain text. */
    val shortcutActive: Boolean get() = ((oneShot or locked) and SHORTCUT_MASK) != 0

    fun cycle(bit: Int) {
        when {
            locked and bit != 0 -> locked = locked and bit.inv()
            oneShot and bit != 0 -> {
                oneShot = oneShot and bit.inv()
                locked = locked or bit
            }
            else -> oneShot = oneShot or bit
        }
        HidController.setHeldModifiers(locked)
        listener?.invoke()
    }

    /** Returns the one-shot modifiers and clears them. */
    fun takeOneShot(): Int {
        val m = oneShot
        if (m != 0) {
            oneShot = 0
            listener?.invoke()
        }
        return m
    }

    fun clear() {
        oneShot = 0
        locked = 0
        HidController.setHeldModifiers(0)
        listener?.invoke()
    }
}
