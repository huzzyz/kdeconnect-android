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

    private constructor(ctx: Context) {
        context = ctx.applicationContext
        Handler(Looper.getMainLooper()).post {
            cm = ContextCompat.getSystemService<ClipboardManager>(context, ClipboardManager::class.java)!!
            cm.addPrimaryClipChangedListener { this.onClipboardChanged() }
        }

        // Prioritize Shizuku
        if (isShizukuAvailableAndAuthorized()) {
            startShizukuLogcatListener()
        } else if (Shizuku.pingBinder()) {
            requestShizukuPermission()
        } else if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_LOGS) == PackageManager.PERMISSION_GRANTED) {
            // Fallback to legacy READ_LOGS implementation
            startLogcatListener()
        } else {
            // Notify user that Shizuku or ADB permissions are missing
            Handler(Looper.getMainLooper()).post {
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
        val listener = object : Shizuku.OnRequestPermissionResultListener {
            override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                if (grantResult == PackageManager.PERMISSION_GRANTED) {
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
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(context, "Shizuku service unavailable", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun startShizukuLogcatListener() {
        execute {
            try {
                val timeStamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
                val logcatFilter = if (Build.VERSION.SDK_INT > Build.VERSION_CODES.VANILLA_ICE_CREAM) { "E ClipboardService" } else { "ClipboardService:E" }
                val process = Shizuku.newProcess(arrayOf("logcat", "-T", timeStamp, logcatFilter, "*:S"), null, null)
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