/*
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.kde.kdeconnect

import android.os.Handler

internal fun interface CancellableTask {
    fun cancel()
}

internal fun interface DelayedTaskScheduler {
    fun schedule(delayMillis: Long, task: () -> Unit): CancellableTask
}

internal class HandlerDelayedTaskScheduler(private val handler: Handler) : DelayedTaskScheduler {
    override fun schedule(delayMillis: Long, task: () -> Unit): CancellableTask {
        val runnable = Runnable(task)
        handler.postDelayed(runnable, delayMillis)
        return CancellableTask { handler.removeCallbacks(runnable) }
    }
}

/**
 * Adds a short reconnect window after joining Wi-Fi.
 *
 * Access points can announce a usable Wi-Fi network before peer traffic is ready. A single
 * discovery attempt in that window can fail even though the LAN becomes usable moments later.
 * These retries are bounded, event-driven, and stop as soon as a paired device is reachable.
 */
internal class WifiReconnectRetryController(
    private val scheduler: DelayedTaskScheduler,
    private val hasReachablePairedDevice: () -> Boolean,
    private val refreshConnections: () -> Unit,
    private val retryDelaysMillis: LongArray = DEFAULT_RETRY_DELAYS_MILLIS,
) {
    private val scheduledTasks = mutableListOf<CancellableTask>()
    private var generation = 0

    @Synchronized
    fun start() {
        cancelLocked()
        val currentGeneration = generation
        retryDelaysMillis.forEach { delayMillis ->
            scheduledTasks += scheduler.schedule(delayMillis) {
                runRetry(currentGeneration)
            }
        }
    }

    @Synchronized
    fun cancel() {
        cancelLocked()
    }

    fun cancelIfConnected() {
        if (hasReachablePairedDevice()) cancel()
    }

    private fun runRetry(expectedGeneration: Int) {
        synchronized(this) {
            if (expectedGeneration != generation) return
            if (hasReachablePairedDevice()) {
                cancelLocked()
                return
            }
        }
        refreshConnections()
    }

    private fun cancelLocked() {
        generation++
        scheduledTasks.forEach(CancellableTask::cancel)
        scheduledTasks.clear()
    }

    companion object {
        internal val DEFAULT_RETRY_DELAYS_MILLIS = longArrayOf(5_000L, 20_000L, 60_000L)
    }
}
