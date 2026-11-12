package com.tally.book.ui.theme

import com.tally.book.core.DevicePrefs

/*
 * The Appearance setting (Family page): "system" (follows the phone), "light" or "dark". It is a DEVICE setting
 * (DevicePrefs "tally-theme-mode"), not part of the book: it does not sync, is not in a backup and reads the same
 * before and after unlock, so the lock screen, the window behind the app and the system bars all follow it.
 * Pure Kotlin, so it is unit tested.
 */
object ThemeMode {
    const val KEY = "tally-theme-mode"
    val CHOICES = listOf("system", "light", "dark")

    /** Any stored value as one of "system", "light", "dark" or "black" (an older Black choice still shows as black). */
    fun normalize(raw: String?): String = when (raw) {
        "light", "normal" -> "light"
        "dark" -> "dark"
        "black" -> "black"
        else -> "system"
    }

    /** Whether the app is dark for [mode], given whether the phone is. */
    fun isDark(mode: String?, systemDark: Boolean): Boolean = when (normalize(mode)) {
        "dark", "black" -> true
        "light" -> false
        else -> systemDark
    }

    /** The row of the Appearance group that is ticked for [mode] (black counts as dark). */
    fun choiceOf(mode: String?): String = normalize(mode).let { if (it == "black") "dark" else it }

    /** The colour of the window behind the app (ARGB): the page background, so nothing flashes before the first frame. */
    fun windowColor(mode: String?, systemDark: Boolean): Int = when {
        normalize(mode) == "black" -> 0xFF000000.toInt()
        isDark(mode, systemDark) -> 0xFF111113.toInt()
        else -> 0xFFF2F2F7.toInt()
    }

    fun stored(): String = normalize(DevicePrefs.get(KEY))
    fun set(choice: String) = DevicePrefs.set(KEY, normalize(choice))
}
