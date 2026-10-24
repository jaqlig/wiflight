package io.github.jaqlig.wiflight

import org.junit.Assert.assertEquals
import org.junit.Test

class MonitoringModeTest {
    @Test fun `enabled automation uses foreground monitoring`() {
        assertEquals(MonitoringMode.FOREGROUND, monitoringMode(true, false, false))
    }
    @Test fun `paused automation still restores owned airplane mode`() {
        assertEquals(MonitoringMode.FOREGROUND, monitoringMode(false, true, false))
    }
    @Test fun `pending assistant recovery keeps the service available`() {
        assertEquals(MonitoringMode.FOREGROUND, monitoringMode(false, false, true))
    }
    @Test fun `fully paused automation needs no service`() {
        assertEquals(MonitoringMode.OFF, monitoringMode(false, false, false))
    }
}
