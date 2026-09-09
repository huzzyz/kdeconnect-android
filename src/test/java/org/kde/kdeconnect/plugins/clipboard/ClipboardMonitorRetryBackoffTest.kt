/*
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.kde.kdeconnect.plugins.clipboard

import org.junit.Assert.assertEquals
import org.junit.Test

class ClipboardMonitorRetryBackoffTest {
    @Test
    fun delayIncreasesAndIsBounded() {
        val backoff = ClipboardMonitorRetryBackoff(
            initialDelayMs = 10,
            maximumDelayMs = 40,
            maximumAttempts = 4,
        )

        assertEquals(10L, backoff.nextDelayMs())
        assertEquals(20L, backoff.nextDelayMs())
        assertEquals(40L, backoff.nextDelayMs())
        assertEquals(40L, backoff.nextDelayMs())
        assertEquals(null, backoff.nextDelayMs())
    }

    @Test
    fun resetRestoresInitialDelay() {
        val backoff = ClipboardMonitorRetryBackoff(
            initialDelayMs = 10,
            maximumDelayMs = 40,
            maximumAttempts = 2,
        )

        backoff.nextDelayMs()
        backoff.nextDelayMs()
        assertEquals(null, backoff.nextDelayMs())
        backoff.reset()

        assertEquals(10L, backoff.nextDelayMs())
    }
}
