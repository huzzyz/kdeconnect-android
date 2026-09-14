/*
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.kde.kdeconnect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiReconnectRetryControllerTest {
    private class FakeScheduler : DelayedTaskScheduler {
        data class Entry(val delayMillis: Long, val task: () -> Unit, var cancelled: Boolean = false)

        val entries = mutableListOf<Entry>()

        override fun schedule(delayMillis: Long, task: () -> Unit): CancellableTask {
            val entry = Entry(delayMillis, task)
            entries += entry
            return CancellableTask { entry.cancelled = true }
        }

        fun run(delayMillis: Long) {
            entries.single { it.delayMillis == delayMillis }.takeUnless { it.cancelled }?.task?.invoke()
        }
    }

    @Test
    fun schedulesOnlyTheBoundedRetryWindow() {
        val scheduler = FakeScheduler()
        val controller = WifiReconnectRetryController(scheduler, { false }, {})

        controller.start()

        assertEquals(listOf(5_000L, 20_000L, 60_000L), scheduler.entries.map { it.delayMillis })
    }

    @Test
    fun retriesWhileNoPairedDeviceIsReachable() {
        val scheduler = FakeScheduler()
        var refreshCount = 0
        val controller = WifiReconnectRetryController(scheduler, { false }, { refreshCount++ })
        controller.start()

        scheduler.run(5_000L)
        scheduler.run(20_000L)
        scheduler.run(60_000L)

        assertEquals(3, refreshCount)
    }

    @Test
    fun successfulConnectionCancelsRemainingRetries() {
        val scheduler = FakeScheduler()
        var connected = false
        var refreshCount = 0
        val controller = WifiReconnectRetryController(scheduler, { connected }, { refreshCount++ })
        controller.start()

        scheduler.run(5_000L)
        connected = true
        controller.cancelIfConnected()
        scheduler.run(20_000L)
        scheduler.run(60_000L)

        assertEquals(1, refreshCount)
        assertTrue(scheduler.entries.all { it.cancelled })
    }

    @Test
    fun restartingWindowCancelsTasksFromPreviousNetworkEvent() {
        val scheduler = FakeScheduler()
        var refreshCount = 0
        val controller = WifiReconnectRetryController(scheduler, { false }, { refreshCount++ })
        controller.start()
        val firstWindow = scheduler.entries.toList()

        controller.start()
        firstWindow.forEach { it.task() }

        assertEquals(0, refreshCount)
        assertTrue(firstWindow.all { it.cancelled })
        assertEquals(6, scheduler.entries.size)
    }
}
