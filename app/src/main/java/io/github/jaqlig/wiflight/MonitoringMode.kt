package io.github.jaqlig.wiflight

enum class MonitoringMode { OFF, FOREGROUND }

fun monitoringMode(enabled: Boolean, owned: Boolean, recovery: Boolean): MonitoringMode =
    if (enabled || owned || recovery) MonitoringMode.FOREGROUND else MonitoringMode.OFF
