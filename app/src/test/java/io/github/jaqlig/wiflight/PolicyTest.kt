package io.github.jaqlig.wiflight

import org.junit.Assert.assertEquals
import org.junit.Test

class PolicyTest {
    @Test fun `stable wifi enables flight mode`() {
        assertEquals(Decision.ENABLE, decide(PolicyInput(true, true, false, false)))
    }
    @Test fun `wifi loss restores only app owned flight mode`() {
        assertEquals(Decision.DISABLE, decide(PolicyInput(true, false, true, true)))
        assertEquals(Decision.NONE, decide(PolicyInput(true, false, true, false)))
    }
    @Test fun `pause restores app owned mode regardless of wifi`() {
        assertEquals(Decision.DISABLE, decide(PolicyInput(false, true, true, true)))
        assertEquals(Decision.NONE, decide(PolicyInput(false, true, true, false)))
    }
    @Test fun `repeated observations do not toggle`() {
        assertEquals(Decision.NONE, decide(PolicyInput(true, true, true, true)))
        assertEquals(Decision.NONE, decide(PolicyInput(true, false, false, false)))
    }
    @Test fun `manual airplane off stays off on the same wifi while automation remains enabled`() {
        assertEquals(Decision.NONE, decide(PolicyInput(true, true, false, false, manualOverrideWifi = true)))
    }
    @Test fun `wifi connection change ends manual override`() {
        assertEquals(Decision.ENABLE, decide(PolicyInput(true, true, false, false, manualOverrideWifi = false)))
        assertEquals(Decision.DISABLE, decide(PolicyInput(true, false, true, true, manualOverrideWifi = true)))
    }
    @Test fun `explicit pause still restores owned airplane mode during an override`() {
        assertEquals(Decision.DISABLE, decide(PolicyInput(false, true, true, true, manualOverrideWifi = true)))
    }
    @Test fun `manual airplane on remains user owned after wifi disconnects`() {
        assertEquals(Decision.NONE, decide(PolicyInput(true, false, true, false, manualOverrideWifi = true)))
    }
    @Test fun `internet requirement blocks offline wifi and captive portals`() {
        assertEquals(Decision.NONE, decide(PolicyInput(true, true, false, false,
            requireValidatedInternet = true, wifiValidated = false)))
    }
    @Test fun `validated wifi allows enabling airplane mode`() {
        assertEquals(Decision.ENABLE, decide(PolicyInput(true, true, false, false,
            requireValidatedInternet = true, wifiValidated = true)))
    }
    @Test fun `validation loss restores app owned airplane mode even when wifi stays connected`() {
        assertEquals(Decision.DISABLE, decide(PolicyInput(true, true, true, true,
            requireValidatedInternet = true, wifiValidated = false)))
    }
    @Test fun `validation loss does not change user owned airplane mode`() {
        assertEquals(Decision.NONE, decide(PolicyInput(true, true, true, false,
            requireValidatedInternet = true, wifiValidated = false)))
    }
    @Test fun `optional validation preserves connection only behavior`() {
        assertEquals(Decision.ENABLE, decide(PolicyInput(true, true, false, false,
            requireValidatedInternet = false, wifiValidated = false)))
        assertEquals(Decision.NONE, decide(PolicyInput(true, true, true, true,
            requireValidatedInternet = false, wifiValidated = false)))
    }
    @Test fun `stale validation on a disconnected network cannot enable airplane mode`() {
        assertEquals(Decision.NONE, decide(PolicyInput(true, false, false, false,
            requireValidatedInternet = true, wifiValidated = true)))
    }
    @Test fun `manual choice remains respected during internet validation changes`() {
        assertEquals(Decision.NONE, decide(PolicyInput(true, true, false, false, manualOverrideWifi = true,
            requireValidatedInternet = true, wifiValidated = true)))
    }
}
