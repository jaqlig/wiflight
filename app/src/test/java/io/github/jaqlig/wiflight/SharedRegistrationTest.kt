package io.github.jaqlig.wiflight

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SharedRegistrationTest {
    @Test fun `service and screen share one registration and release independently`() {
        var starts = 0
        var stops = 0
        val registration = SharedRegistration({ starts++ }, { stops++ })
        val service = registration.acquire()
        val screen = registration.acquire()
        assertEquals(1, starts)
        screen.close()
        assertEquals(0, stops) // Closing the screen must not break background automation.
        service.close()
        assertEquals(1, stops)
    }

    @Test fun `pausing service keeps monitoring for the visible screen`() {
        var stops = 0
        val registration = SharedRegistration({}, { stops++ })
        val service = registration.acquire()
        val screen = registration.acquire()
        service.close()
        assertEquals(0, stops)
        screen.close()
        assertEquals(1, stops)
    }

    @Test fun `duplicate disposal cannot release another consumer`() {
        var stops = 0
        val registration = SharedRegistration({}, { stops++ })
        val first = registration.acquire()
        val second = registration.acquire()
        first.close()
        first.close()
        assertEquals(0, stops)
        second.close()
        assertEquals(1, stops)
    }

    @Test fun `reopening after all consumers leave creates a fresh registration`() {
        var starts = 0
        var stops = 0
        val registration = SharedRegistration({ starts++ }, { stops++ })
        registration.acquire().close()
        val next = registration.acquire()
        assertEquals(2, starts)
        assertEquals(1, stops)
        next.close()
        assertEquals(2, stops)
    }

    @Test fun `failed registration does not strand a consumer or prevent retry`() {
        var attempts = 0
        var stops = 0
        val registration = SharedRegistration({
            attempts++
            check(attempts > 1) { "Simulated Android registration failure" }
        }, { stops++ })
        assertThrows(IllegalStateException::class.java) { registration.acquire() }
        assertEquals(0, stops)
        registration.acquire().close()
        assertEquals(2, attempts)
        assertEquals(1, stops)
    }
}
