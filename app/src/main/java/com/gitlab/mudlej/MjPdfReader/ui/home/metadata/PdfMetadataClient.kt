// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.home.metadata

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.RemoteException
import com.gitlab.mudlej.MjPdfReader.data.entity.SourceFingerprint
import com.gitlab.mudlej.MjPdfReader.ui.home.scan.ScanEvent
import java.util.concurrent.atomic.AtomicBoolean

class PdfMetadataClient(
    context: Context,
    private val emit: (ScanEvent) -> Unit,
) {
    private val context = context.applicationContext
    private val lock = Any()
    private var current: Connection? = null

    fun connect(generation: Long, connectionToken: Long): Boolean {
        disconnect(expected = true)
        val connection = Connection(generation = generation, token = connectionToken)
        synchronized(lock) { current = connection }
        val bound = runCatching {
            context.bindService(
                Intent(context, PdfMetadataService::class.java),
                connection,
                Context.BIND_AUTO_CREATE or Context.BIND_WAIVE_PRIORITY,
            )
        }.getOrDefault(false)
        if (!bound) reportDeath(connection)
        return bound
    }

    fun process(generation: Long, token: Long, fingerprint: SourceFingerprint, debugBehavior: Int) {
        val connection = connection(generation, token) ?: return
        call(connection) {
            process(
                generation,
                token,
                fingerprint.path,
                fingerprint.size,
                fingerprint.lastModified,
                debugBehavior,
            )
        }
    }

    fun acknowledge(generation: Long, token: Long, fingerprint: SourceFingerprint, action: Int) {
        val connection = connection(generation, token) ?: return
        if (action != MetadataProtocol.ACK_CONTINUE) connection.expectedDeath.set(true)
        call(connection) {
            acknowledge(
                generation,
                token,
                fingerprint.path,
                fingerprint.size,
                fingerprint.lastModified,
                action,
            )
        }
    }

    fun sampleMemory(generation: Long, token: Long) {
        val connection = connection(generation, token) ?: return
        call(connection) { sampleMemory(generation, token) }
    }

    fun disconnect(expected: Boolean) {
        val connection = synchronized(lock) {
            val connection = current ?: return
            connection.expectedDeath.set(expected)
            current = null
            connection
        }
        unlinkAndUnbind(connection)
    }

    private fun connection(generation: Long, token: Long): Connection? = synchronized(lock) {
        current?.takeIf { it.generation == generation && it.token == token }
    }

    private inline fun call(connection: Connection, block: IPdfMetadataService.() -> Unit) {
        val service = synchronized(lock) {
            connection.service.takeIf { current === connection }
        }
        if (service == null) {
            reportDeath(connection)
            return
        }
        try {
            service.block()
        } catch (_: RemoteException) {
            reportDeath(connection)
        } catch (_: RuntimeException) {
            reportDeath(connection)
        }
    }

    private fun reportDeath(connection: Connection) {
        synchronized(lock) {
            if (current !== connection) return
            if (!connection.deathReported.compareAndSet(false, true)) return
            current = null
        }
        unlinkAndUnbind(connection)
        emit(
            ScanEvent.WorkerDied(
                generation = connection.generation,
                connectionToken = connection.token,
                expected = connection.expectedDeath.get(),
            )
        )
    }

    private fun unlinkAndUnbind(connection: Connection) {
        connection.binder?.let { binder ->
            runCatching { binder.unlinkToDeath(connection.deathRecipient, 0) }
        }
        connection.binder = null
        connection.service = null
        runCatching { context.unbindService(connection) }
    }

    private fun isCurrent(connection: Connection): Boolean = synchronized(lock) { current === connection }

    private inner class Connection(
        val generation: Long,
        val token: Long,
    ) : ServiceConnection {
        val expectedDeath = AtomicBoolean(false)
        val deathReported = AtomicBoolean(false)
        @Volatile var binder: IBinder? = null
        @Volatile var service: IPdfMetadataService? = null
        val deathRecipient = IBinder.DeathRecipient { reportDeath(this@Connection) }

        private val callback = MetadataCallback(isCurrent = { isCurrent(this) }, emit = emit)

        override fun onServiceConnected(name: ComponentName?, connectedBinder: IBinder?) {
            if (connectedBinder == null || !isCurrent(this)) {
                reportDeath(this)
                return
            }
            val connectedService = IPdfMetadataService.Stub.asInterface(connectedBinder)
            binder = connectedBinder
            service = connectedService
            try {
                connectedBinder.linkToDeath(deathRecipient, 0)
                if (!isCurrent(this)) {
                    runCatching { connectedBinder.unlinkToDeath(deathRecipient, 0) }
                    return
                }
                connectedService.registerCallback(callback, generation, token)
            } catch (_: RemoteException) {
                reportDeath(this)
            } catch (_: RuntimeException) {
                reportDeath(this)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) = reportDeath(this)
        override fun onBindingDied(name: ComponentName?) = reportDeath(this)
        override fun onNullBinding(name: ComponentName?) = reportDeath(this)
    }
}
