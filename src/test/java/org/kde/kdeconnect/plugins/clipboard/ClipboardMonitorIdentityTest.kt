/*
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.kde.kdeconnect.plugins.clipboard

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipboardMonitorIdentityTest {
    @Test
    fun cleanupTargetsOnlyTheForkOwnedEnvironmentMarker() {
        val command = ClipboardListener.shizukuMonitorCleanupCommand()

        assertTrue(command.contains("/proc/\$pid/environ"))
        assertTrue(command.contains("grep -Fxq '${ClipboardListener.SHIZUKU_MONITOR_ENVIRONMENT}'"))
        assertFalse(command.contains("pkill"))
        assertFalse(command.contains("ClipboardService.*"))
    }
}
