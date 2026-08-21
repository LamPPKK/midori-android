package io.github.lamppkk.xanhbrowser.sync

import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Process-wide lease for Mozilla Application Services Sync engine registries.
 *
 * Application Services 155 stores the registered Places, Tabs and Logins
 * engines globally. Allowing two live runtimes would let one account resolve
 * the other profile's engines. Xanh therefore permits exactly one runtime in
 * an Android process and releases the lease only after every native store has
 * closed.
 */
internal object ApplicationServicesRuntimeRegistry {
    private val active = AtomicBoolean(false)

    fun acquire(): Lease {
        check(active.compareAndSet(false, true)) {
            "Only one Mozilla Sync runtime may be open in a process"
        }
        return Lease()
    }

    fun isAvailable(): Boolean = !active.get()

    internal class Lease internal constructor() : Closeable {
        private val released = AtomicBoolean(false)

        override fun close() {
            if (released.compareAndSet(false, true)) active.set(false)
        }
    }
}
