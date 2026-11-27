package com.universalkeyboard

import android.content.Context

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var pointerSpeed: Float
        get() = sp.getFloat("pointer_speed", 1.6f)
        set(v) = sp.edit().putFloat("pointer_speed", v).apply()

    var scrollSpeed: Float
        get() = sp.getFloat("scroll_speed", 1.0f)
        set(v) = sp.edit().putFloat("scroll_speed", v).apply()

    var naturalScroll: Boolean
        get() = sp.getBoolean("natural_scroll", true)
        set(v) = sp.edit().putBoolean("natural_scroll", v).apply()

    /** Mouse updates per second, remembered separately for each host. */
    fun mouseRate(hostAddress: String?): Int =
        sp.getInt("mouse_rate_${hostAddress ?: "default"}", DEFAULT_MOUSE_RATE)

    fun setMouseRate(hostAddress: String?, rate: Int) =
        sp.edit().putInt("mouse_rate_${hostAddress ?: "default"}", rate).apply()

    var smartTyping: Boolean
        get() = sp.getBoolean("smart_typing", false)
        set(v) = sp.edit().putBoolean("smart_typing", v).apply()

    companion object {
        const val DEFAULT_MOUSE_RATE = 125
        val MOUSE_RATES = listOf(125, 90, 60, 45)
    }
}
