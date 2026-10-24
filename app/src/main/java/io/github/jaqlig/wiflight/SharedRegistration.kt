package io.github.jaqlig.wiflight

/** Main-thread leases: one system registration, released when the last consumer leaves. */
internal class SharedRegistration(
    private val start: () -> Unit,
    private val stop: () -> Unit,
) {
    private var consumers = 0

    fun acquire(): AutoCloseable {
        if (consumers == 0) start() // A failed registration must not leave a phantom consumer.
        consumers++
        var closed = false
        return AutoCloseable {
            if (!closed) {
                closed = true
                consumers--
                if (consumers == 0) stop()
            }
        }
    }
}
