/*
 * SPDX-FileCopyrightText: 2014 Albert Vaca Cintora <albertvaka@gmail.com>
 * SPDX-FileCopyrightText: 2021 Ilmaz Gumerov <ilmaz1309@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
*/
package org.kde.kdeconnect.plugins.clipboard

import android.Manifest
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import org.kde.kdeconnect.helpers.ThreadHelper.execute
import org.kde.kdeconnect_tp.BuildConfig
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ClipboardListener {
    enum class ClipboardContentType {
        Text,
        Password,
    }

    interface ClipboardObserver {
        fun clipboardChanged(content: String, contentType: ClipboardContentType)
    }

    private val observers: HashSet<ClipboardObserver> = HashSet()

    private val context: Context
    var currentContent: String? = null
        private set
    var currentContentType: ClipboardContentType = ClipboardContentType.Text
        private set
    var updateTimestamp: Long = 0
        private set

    private lateinit var cm: ClipboardManager

    private val mainHandler = Handler(Looper.getMainLooper())
    private val shizukuMonitorLock = Any()
    private var shizukuLogcatProcess: Process? = null
    private var shizukuMonitorStarting = false
    private var shizukuMonitorGeneration = 0L
    private var shizukuPermissionRequestPending = false
    private val shizukuRetryBackoff = ClipboardMonitorRetryBackoff()

    private val shizukuBinderReceivedListener = Shizuku.OnBinderReceivedListener {
        Log.i(TAG, "Shizuku binder received; refreshing clipboard monitor")
        stopShizukuLogcatListener()
        shizukuRetryBackoff.reset()
        if (isShizukuAvailableAndAuthorized()) {
            startShizukuLogcatListener()
        } else {
            requestShizukuPermission()
        }
    }

    private val shizukuBinderDeadListener = Shizuku.OnBinderDeadListener {
        Log.w(TAG, "Shizuku binder died; stopping stale clipboard monitor")
        stopShizukuLogcatListener()
    }

    private val shizukuRestart = Runnable {
        if (isShizukuAvailableAndAuthorized()) {
            startShizukuLogcatListener()
        }
    }

    private constructor(ctx: Context) {
        context = ctx.applicationContext
        mainHandler.post {
            cm = ContextCompat.getSystemService(context, ClipboardManager::class.java)!!
            cm.addPrimaryClipChangedListener { this.onClipboardChanged() }
        }

        Shizuku.addBinderDeadListener(shizukuBinderDeadListener)
        Shizuku.addBinderReceivedListenerSticky(shizukuBinderReceivedListener)

        if (!Shizuku.pingBinder() && Build.VERSION.SDK_INT > Build.VERSION_CODES.P &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_LOGS) == PackageManager.PERMISSION_GRANTED) {
            // Fallback to legacy READ_LOGS implementation
            startLogcatListener()
        } else if (!Shizuku.pingBinder()) {
            // Notify user that Shizuku or ADB permissions are missing
            mainHandler.post {
                Toast.makeText(context, "Clipboard sync requires Shizuku or ADB permissions", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun isShizukuAvailableAndAuthorized(): Boolean {
        return try {
            Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            false
        }
    }

    private fun requestShizukuPermission() {
        synchronized(shizukuMonitorLock) {
            if (shizukuPermissionRequestPending) {
                return
            }
            shizukuPermissionRequestPending = true
        }
        val listener = object : Shizuku.OnRequestPermissionResultListener {
            override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                synchronized(shizukuMonitorLock) {
                    shizukuPermissionRequestPending = false
                }
                if (grantResult == PackageManager.PERMISSION_GRANTED) {
                    shizukuRetryBackoff.reset()
                    startShizukuLogcatListener()
                } else {
                    Handler(Looper.getMainLooper()).post {
                        Toast.makeText(context, "Shizuku permission denied", Toast.LENGTH_LONG).show()
                    }
                }
                Shizuku.removeRequestPermissionResultListener(this)
            }
        }
        Shizuku.addRequestPermissionResultListener(listener)
        try {
            Shizuku.requestPermission(0)
        } catch (e: Exception) {
            synchronized(shizukuMonitorLock) {
                shizukuPermissionRequestPending = false
            }
            Shizuku.removeRequestPermissionResultListener(listener)
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(context, "Shizuku service unavailable", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun startShizukuLogcatListener() {
        val generation = synchronized(shizukuMonitorLock) {
            if (shizukuLogcatProcess != null || shizukuMonitorStarting || !isShizukuAvailableAndAuthorized()) {
                return
            }
            shizukuMonitorStarting = true
            ++shizukuMonitorGeneration
        }

        execute {
            var process: Process? = null
            try {
                stopOrphanedShizukuLogcatListeners()
                synchronized(shizukuMonitorLock) {
                    if (generation != shizukuMonitorGeneration || !isShizukuAvailableAndAuthorized()) {
                        shizukuMonitorStarting = false
                        return@execute
                    }
                }
                val timeStamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
                val logcatFilter = if (Build.VERSION.SDK_INT > Build.VERSION_CODES.VANILLA_ICE_CREAM) { "E ClipboardService" } else { "ClipboardService:E" }
                val newProcess = Shizuku.newProcess(
                    arrayOf("/system/bin/logcat", "-T", timeStamp, logcatFilter, "*:S"),
                    arrayOf(SHIZUKU_MONITOR_ENVIRONMENT),
                    null,
                )
                process = newProcess
                synchronized(shizukuMonitorLock) {
                    if (generation != shizukuMonitorGeneration || !isShizukuAvailableAndAuthorized()) {
                        shizukuMonitorStarting = false
                        newProcess.destroy()
                        return@execute
                    }
                    shizukuMonitorStarting = false
                    shizukuLogcatProcess = newProcess
                }
                val bufferedReader = BufferedReader(InputStreamReader(newProcess.inputStream))
                bufferedReader.forEachLine { line ->
                    if (line.contains(BuildConfig.APPLICATION_ID)) {
                        // Receiving an event proves this replacement monitor is healthy.
                        shizukuRetryBackoff.reset()
                        context.startActivity(ClipboardFloatingActivity.getIntent(context, false))
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Shizuku clipboard monitor stopped", e)
            } finally {
                process?.destroy()
                val shouldRestart = synchronized(shizukuMonitorLock) {
                    if (generation == shizukuMonitorGeneration) {
                        shizukuMonitorStarting = false
                        shizukuLogcatProcess = null
                        true
                    } else {
                        false
                    }
                }
                if (shouldRestart && isShizukuAvailableAndAuthorized()) {
                    scheduleShizukuRestart()
                }
            }
        }
    }

    private fun stopOrphanedShizukuLogcatListeners() {
        var cleanupProcess: Process? = null
        try {
            cleanupProcess = Shizuku.newProcess(
                arrayOf("/system/bin/sh", "-c", shizukuMonitorCleanupCommand()),
                null,
                null,
            )
            cleanupProcess.waitFor()
        } catch (e: Exception) {
            Log.w(TAG, "Could not clean up stale Shizuku clipboard monitors", e)
        } finally {
            cleanupProcess?.destroy()
        }
    }

    private fun stopShizukuLogcatListener() {
        val process = synchronized(shizukuMonitorLock) {
            ++shizukuMonitorGeneration
            shizukuMonitorStarting = false
            shizukuLogcatProcess.also { shizukuLogcatProcess = null }
        }
        mainHandler.removeCallbacks(shizukuRestart)
        process?.destroy()
    }

    private fun scheduleShizukuRestart() {
        val delay = shizukuRetryBackoff.nextDelayMs()
        if (delay == null) {
            Log.e(TAG, "Shizuku clipboard monitor restart limit reached; waiting for a new binder")
            return
        }
        Log.i(TAG, "Restarting Shizuku clipboard monitor in ${delay}ms")
        mainHandler.removeCallbacks(shizukuRestart)
        mainHandler.postDelayed(shizukuRestart, delay)
    }

    private fun startLogcatListener() {
        execute {
            try {
                val timeStamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
                val logcatFilter = if (Build.VERSION.SDK_INT > Build.VERSION_CODES.VANILLA_ICE_CREAM) { "E ClipboardService" } else { "ClipboardService:E" }
                val process = Runtime.getRuntime().exec(arrayOf("logcat", "-T", timeStamp, logcatFilter, "*:S"))
                val bufferedReader = BufferedReader(InputStreamReader(process.inputStream))
                bufferedReader.forEachLine { line ->
                    if (line.contains(BuildConfig.APPLICATION_ID)) {
                        context.startActivity(ClipboardFloatingActivity.getIntent(context, false))
                    }
                }
                process.destroy()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun registerObserver(observer: ClipboardObserver) {
        observers.add(observer)
    }

    fun removeObserver(observer: ClipboardObserver) {
        observers.remove(observer)
    }

    fun onClipboardChanged() {
        try {
            val clip = cm.primaryClip!!
            val item = clip.getItemAt(0)
            val content = item.coerceToText(context).toString()
            val contentType = detectContentType(clip)

            if (content == currentContent && contentType == currentContentType) {
                return
            }
            updateTimestamp = System.currentTimeMillis()
            currentContent = content
            currentContentType = contentType

            for (observer in observers) {
                observer.clipboardChanged(content, contentType)
            }
        } catch (_: Exception) {
            //Probably clipboard was not text
        }
    }

    @Suppress("deprecation")
    fun setText(text: String?) {
        if (this::cm.isInitialized) {
            updateTimestamp = System.currentTimeMillis()
            currentContent = text
            currentContentType = ClipboardContentType.Text
            cm.text = text
        }
    }

    companion object {
        private const val TAG = "ClipboardListener"
        internal const val SHIZUKU_MONITOR_ENVIRONMENT =
            "KDECONNECT_SHIZUKU_CLIPBOARD_MONITOR=org.kde.kdeconnect_tp.shizuku.v1"

        internal fun shizukuMonitorCleanupCommand(): String =
            "for pid in \$(/system/bin/pidof logcat 2>/dev/null); do " +
                "/system/bin/tr '\\000' '\\n' < /proc/\$pid/environ 2>/dev/null | " +
                "/system/bin/grep -Fxq '$SHIZUKU_MONITOR_ENVIRONMENT' && /system/bin/kill \"\$pid\"; " +
                "done; true"
        private var _instance: ClipboardListener? = null

        @JvmStatic
        fun instance(context: Context): ClipboardListener {
            // FIXME: The _instance we return won't be completely initialized yet since initialization happens on a new thread (why?)
            return _instance ?: ClipboardListener(context).also { _instance = it }
        }

        @JvmStatic
        fun detectContentType(clip: ClipData?): ClipboardContentType {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
                return ClipboardContentType.Text
            }
            if (clip?.description?.extras
                    ?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE, false) == true
            ) {
                return ClipboardContentType.Password
            }
            return ClipboardContentType.Text
        }
    }
}

internal class ClipboardMonitorRetryBackoff(
    private val initialDelayMs: Long = 1_000L,
    private val maximumDelayMs: Long = 30_000L,
    private val maximumAttempts: Int = 6,
) {
    private var nextDelayMs = initialDelayMs
    private var attempts = 0

    @Synchronized
    fun nextDelayMs(): Long? {
        if (attempts >= maximumAttempts) {
            return null
        }
        attempts++
        val delay = nextDelayMs
        nextDelayMs = (nextDelayMs * 2).coerceAtMost(maximumDelayMs)
        return delay
    }

    @Synchronized
    fun reset() {
        nextDelayMs = initialDelayMs
        attempts = 0
    }
}
