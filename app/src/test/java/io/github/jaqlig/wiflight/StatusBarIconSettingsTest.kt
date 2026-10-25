package io.github.jaqlig.wiflight

import org.junit.Assert.assertEquals
import org.junit.Test

class StatusBarIconSettingsTest {
    private val defaults = setOf("rotate", "headset")

    @Test fun `hiding airplane preserves icons hidden by another app`() {
        assertEquals(setOf("wifi", "clock", "vendor_icon", "airplane"),
            StatusBarIconList.icons(StatusBarIconList.withAirplaneHidden("wifi,clock,vendor_icon", defaults, true), defaults))
    }

    @Test fun `showing airplane removes every duplicate without changing other entries`() {
        assertEquals("wifi,clock,vendor_icon",
            StatusBarIconList.withAirplaneHidden("wifi,airplane,clock,airplane,vendor_icon", defaults, false))
    }

    @Test fun `missing value preserves OEM defaults and showing writes an explicit list`() {
        val hidden = StatusBarIconList.withAirplaneHidden(null, defaults, true)
        assertEquals(setOf("rotate", "headset", "airplane"), StatusBarIconList.icons(hidden, defaults))
        assertEquals("rotate,headset", StatusBarIconList.withAirplaneHidden(hidden, defaults, false))
    }

    @Test fun `explicit empty list overrides defaults and stays empty when showing airplane`() {
        assertEquals(emptySet<String>(), StatusBarIconList.icons("", defaults))
        assertEquals("", StatusBarIconList.withAirplaneHidden("", defaults, false))
        assertEquals("airplane", StatusBarIconList.withAirplaneHidden("", defaults, true))
    }

    @Test fun `explicit lists can be edited when OEM defaults cannot be read`() {
        assertEquals("wifi,airplane", StatusBarIconList.withAirplaneHidden("wifi", null, true))
        assertEquals("", StatusBarIconList.withAirplaneHidden("airplane", null, false))
    }

    @Test(expected = IllegalStateException::class)
    fun `unknown defaults are never guessed when the setting is absent`() {
        StatusBarIconList.withAirplaneHidden(null, null, true)
    }

    @Test fun `already requested visibility leaves the existing value untouched`() {
        assertEquals("wifi,,airplane,wifi", StatusBarIconList.withAirplaneHidden("wifi,,airplane,wifi", defaults, true))
        assertEquals("rotate,headset", StatusBarIconList.withAirplaneHidden(null, defaults, false))
    }

    @Test fun `devices that hide airplane by default can show and hide it explicitly`() {
        val hiddenDefaults = defaults + "airplane"
        val shown = StatusBarIconList.withAirplaneHidden(null, hiddenDefaults, false)
        assertEquals(defaults, StatusBarIconList.icons(shown, hiddenDefaults))
        assertEquals(hiddenDefaults, StatusBarIconList.icons(
            StatusBarIconList.withAirplaneHidden(shown, hiddenDefaults, true), hiddenDefaults))
    }

    @Test fun `showing the last hidden icon writes an empty string instead of clearing the setting`() {
        assertEquals("", StatusBarIconList.withAirplaneHidden("airplane", emptySet(), false))
    }
}
