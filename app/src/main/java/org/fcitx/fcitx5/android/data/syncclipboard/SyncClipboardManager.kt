/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2024 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.syncclipboard

import android.content.ClipData
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.annotation.Keep
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.data.clipboard.ClipboardManager
import org.fcitx.fcitx5.android.data.clipboard.db.ClipboardEntry
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreference
import org.fcitx.fcitx5.android.utils.appContext
import org.fcitx.fcitx5.android.utils.clipboardManager
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest

/**
 * SyncClipboard 同步管理器
 * 自动同步本地剪切板与远程服务器
 */
object SyncClipboardManager : CoroutineScope by CoroutineScope(SupervisorJob() + Dispatchers.Default) {

    private val prefs = AppPrefs.getInstance().syncClipboard
    private val clipboardManager = appContext.clipboardManager

    private var syncJob: Job? = null
    private var lastSyncedHash: String = ""
    private var lastLocalHash: String = ""
    
    private var client: SyncClipboardClient? = null

    @Keep
    private val enabledListener = ManagedPreference.OnChangeListener<Boolean> { _, enabled ->
        if (enabled) {
            startSync()
        } else {
            stopSync()
        }
    }

    @Keep
    private val intervalListener = ManagedPreference.OnChangeListener<Int> { _, _ ->
        if (prefs.enabled.getValue()) {
            restartSync()
        }
    }

    fun init() {
        prefs.enabled.registerOnChangeListener(enabledListener)
        prefs.interval.registerOnChangeListener(intervalListener)
        
        if (prefs.enabled.getValue()) {
            startSync()
        }
        
        // Initialize screenshot detector
        ScreenshotDetector.init()
    }

    private fun createClient(): SyncClipboardClient? {
        val url = prefs.serverUrl.getValue()
        val username = prefs.username.getValue()
        val password = prefs.password.getValue()
        
        if (url.isBlank() || username.isBlank() || password.isBlank()) {
            Timber.w("SyncClipboard: Missing configuration")
            return null
        }
        
        return SyncClipboardClient(url.trimEnd('/'), username, password)
    }

    private fun startSync() {
        client = createClient() ?: return
        
        syncJob = launch {
            while (true) {
                try {
                    syncFromServer()
                    syncToServer()
                } catch (e: Exception) {
                    Timber.e(e, "SyncClipboard sync error")
                }
                
                val intervalSeconds = prefs.interval.getValue()
                delay(intervalSeconds * 1000L)
            }
        }
        Timber.i("SyncClipboard: Started with interval ${prefs.interval.getValue()}s")
    }

    private fun stopSync() {
        syncJob?.cancel()
        syncJob = null
        client = null
        Timber.i("SyncClipboard: Stopped")
    }

    private fun restartSync() {
        stopSync()
        startSync()
    }

    /**
     * 从服务器拉取剪切板内容
     */
    private suspend fun syncFromServer() {
        val client = client ?: return
        
        val result = client.getClipboard()
        result.onSuccess { data ->
            val hash = calculateHash(data.clipboard + data.file)
            if (hash == lastSyncedHash) {
                return@onSuccess
            }
            
            when (data.type) {
                SyncClipboardData.TYPE_TEXT -> {
                    if (data.clipboard.isNotBlank() && data.clipboard != getLocalClipboardText()) {
                        setLocalClipboard(data.clipboard)
                        lastSyncedHash = hash
                        Timber.d("SyncClipboard: Downloaded text from server")
                    }
                }
                SyncClipboardData.TYPE_IMAGE -> {
                    if (data.file.isNotBlank()) {
                        val imageResult = client.downloadFile(data.file)
                        imageResult.onSuccess { bytes ->
                            // 保存图片到剪贴板数据库并在 UI 中显示
                            saveImageToClipboardHistory(bytes, data.file)
                            lastSyncedHash = hash
                            Timber.d("SyncClipboard: Downloaded image from server: ${data.file}")
                        }
                    }
                }
            }
        }
    }

    /**
     * 上传本地剪切板到服务器
     */
    private suspend fun syncToServer() {
        val client = client ?: return
        
        val localText = getLocalClipboardText()
        if (localText.isNullOrBlank()) return
        
        val hash = calculateHash(localText)
        if (hash == lastLocalHash) return
        
        val data = SyncClipboardData.text(localText)
        val result = client.putClipboard(data)
        result.onSuccess {
            lastLocalHash = hash
            lastSyncedHash = hash
            Timber.d("SyncClipboard: Uploaded text to server")
        }
    }

    /**
     * 保存图片到剪贴板历史（会在键盘剪贴板面板显示）
     */
    private suspend fun saveImageToClipboardHistory(bytes: ByteArray, filename: String) {
        try {
            val imageDir = ClipboardManager.imageDir
            val entry = ClipboardEntry.fromImageBytes(bytes, imageDir, "image/png")
            if (entry != null) {
                ClipboardManager.insertImageEntry(entry)
                Timber.d("SyncClipboard: Image saved to clipboard history")
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to save image to clipboard history")
        }
    }

    /**
     * 上传图片到服务器
     */
    suspend fun uploadImage(bitmap: Bitmap, filename: String = "image.png"): Boolean {
        val client = client ?: createClient() ?: return false
        
        val bytes = ByteArrayOutputStream().use { stream ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
            stream.toByteArray()
        }
        
        val hash = calculateHash(bytes)
        val uploadResult = client.uploadFile(filename, bytes)
        
        return uploadResult.map {
            val data = SyncClipboardData.image(hash, filename)
            client.putClipboard(data).isSuccess
        }.getOrDefault(false)
    }

    /**
     * 上传剪贴板中最新的图片到服务器
     */
    suspend fun uploadLatestImage(): Boolean {
        val lastEntry = ClipboardManager.lastEntry ?: return false
        if (!lastEntry.isImage) return false
        
        val file = File(lastEntry.imagePath)
        if (!file.exists()) return false
        
        val bytes = file.readBytes()
        val client = client ?: createClient() ?: return false
        
        val filename = "sync_${System.currentTimeMillis()}.png"
        val hash = calculateHash(bytes)
        
        val uploadResult = client.uploadFile(filename, bytes)
        return uploadResult.map {
            val data = SyncClipboardData.image(hash, filename)
            client.putClipboard(data).isSuccess
        }.getOrDefault(false)
    }

    /**
     * 获取本地剪贴板文本
     * 
     * 由于 Android 10+ 的安全限制，后台应用无法直接读取系统剪贴板
     * (错误: "Denying clipboard access to app, application is not in focus")
     * 
     * 解决方案：使用内部 ClipboardManager.lastEntry，它在键盘显示时（IME 获得焦点）
     * 通过 OnPrimaryClipChangedListener 捕获剪贴板内容
     */
    private fun getLocalClipboardText(): String? {
        // 优先使用内部 ClipboardManager 的缓存数据
        // 这些数据是在 IME 获得焦点时通过 OnPrimaryClipChangedListener 捕获的
        val lastEntry = ClipboardManager.lastEntry
        if (lastEntry != null && !lastEntry.isImage && lastEntry.text.isNotBlank()) {
            return lastEntry.text
        }
        
        // 如果内部缓存为空，尝试直接读取系统剪贴板（仅在 IME 获得焦点时有效）
        return try {
            val clip = clipboardManager.primaryClip ?: return null
            if (clip.itemCount == 0) return null
            clip.getItemAt(0)?.text?.toString()
        } catch (e: Exception) {
            // Android 10+ 在后台时会抛出 SecurityException
            Timber.d("Cannot access system clipboard (expected on Android 10+ in background)")
            null
        }
    }

    private fun setLocalClipboard(text: String) {
        try {
            val clip = ClipData.newPlainText("SyncClipboard", text)
            clipboardManager.setPrimaryClip(clip)
        } catch (e: Exception) {
            Timber.e(e, "Failed to set local clipboard")
        }
    }

    private fun calculateHash(text: String): String {
        return calculateHash(text.toByteArray())
    }

    private fun calculateHash(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("MD5")
        val hashBytes = digest.digest(bytes)
        return hashBytes.joinToString("") { "%02x".format(it) }
    }
}
