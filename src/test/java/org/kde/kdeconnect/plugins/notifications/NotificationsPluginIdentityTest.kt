/*
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.kde.kdeconnect.plugins.notifications

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationsPluginIdentityTest {
    @Test
    fun excludesTheActualApplicationPackage() {
        assertTrue(isOwnNotificationPackage("org.kde.kdeconnect_tp.shizuku", "org.kde.kdeconnect_tp.shizuku"))
        assertTrue(isOwnNotificationPackage("org.kde.kdeconnect_tp.shizuku.debug", "org.kde.kdeconnect_tp.shizuku.debug"))
    }

    @Test
    fun doesNotExcludeOtherKdeConnectVariants() {
        assertFalse(isOwnNotificationPackage("org.kde.kdeconnect_tp", "org.kde.kdeconnect_tp.shizuku"))
    }
}
