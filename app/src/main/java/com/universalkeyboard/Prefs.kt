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

    /** Port of the Claude bridge on 127.0.0.1 (see bridge/claude_bridge.py). */
    var bridgePort: Int
        get() = sp.getInt("bridge_port", DEFAULT_BRIDGE_PORT)
        set(v) = sp.edit().putInt("bridge_port", v).apply()

    /** Pairing code printed by the bridge. Kept in app-private storage only (backups are off). */
    var bridgeCode: String
        get() = sp.getString("bridge_code", "") ?: ""
        set(v) = sp.edit().putString("bridge_code", v).apply()

    var lastUpdateCheck: Long
        get() = sp.getLong("last_update_check", 0L)
        set(v) = sp.edit().putLong("last_update_check", v).apply()

    var skippedVersionCode: Long
        get() = sp.getLong("skipped_version_code", 0L)
        set(v) = sp.edit().putLong("skipped_version_code", v).apply()

    companion object {
        const val DEFAULT_MOUSE_RATE = 125
        const val DEFAULT_BRIDGE_PORT = 8765
        val MOUSE_RATES = listOf(125, 90, 60, 45)
    }
}
