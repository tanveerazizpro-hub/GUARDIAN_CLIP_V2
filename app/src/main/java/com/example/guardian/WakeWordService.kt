package com.example.guardian

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.SpeechService
import org.vosk.android.RecognitionListener
import java.io.File
import java.io.FileOutputStream

class WakeWordService : Service(), RecognitionListener {
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private var speechService: SpeechService? = null
    private var model: Model? = null
    @Volatile private var sosTriggered = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        serviceScope.launch {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(NOTIFICATION_ID, buildNotification("Initializing..."), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
                } else {
                    startForeground(NOTIFICATION_ID, buildNotification("Initializing..."))
                }

                val modelDirectory = File(filesDir, MODEL_DIRECTORY)
                // ALWAYS WIPE OLD FILES (this fixes the corrupted README folder from previous attempts)
                if (modelDirectory.exists()) {
                    modelDirectory.deleteRecursively()
                }

                updateNotification("Copying AI model... (Takes ~15 seconds)")
                copyAssetDirectory(MODEL_DIRECTORY, modelDirectory)

                updateNotification("Loading AI model into RAM...")
                val loadedModel = Model(modelDirectory.absolutePath)
                model = loadedModel
                
                updateNotification("Starting microphone listener...")
                val recognizer = Recognizer(loadedModel, SAMPLE_RATE, GRAMMAR)
                speechService = SpeechService(recognizer, SAMPLE_RATE).also {
                    it.startListening(this@WakeWordService)
                }
                
                updateNotification("✅ Listening for 'help'")
                Log.i(TAG, "Offline wake-word detection started.")
                
            } catch (error: Exception) {
                Log.e(TAG, "Vosk Init Error", error)
                val errorMsg = error.message ?: "Unknown error"
                updateNotification("❌ Error: $errorMsg")
            }
        }
    }

    private fun copyAssetDirectory(assetPath: String, destination: File) {
        val children = assets.list(assetPath) ?: emptyArray()
        if (children.isEmpty()) {
            // It's a FILE. Ensure its parent directory exists, then write the file.
            destination.parentFile?.mkdirs()
            assets.open(assetPath).use { input ->
                FileOutputStream(destination).use { output -> input.copyTo(output) }
            }
        } else {
            // It's a DIRECTORY. Create it and recurse into children.
            destination.mkdirs()
            children.forEach { child ->
                copyAssetDirectory("$assetPath/$child", File(destination, child))
            }
        }
    }

    override fun onResult(hypothesis: String?) = inspectResult(hypothesis)
    
    override fun onPartialResult(hypothesis: String?) {
        if (hypothesis != null) {
            try {
                val recognized = JSONObject(hypothesis).optString("partial").trim()
                if (recognized.isNotEmpty()) {
                    updateNotification("Heard: $recognized")
                }
            } catch (_: Exception) {}
        }
    }
    
    override fun onFinalResult(hypothesis: String?) = inspectResult(hypothesis)
    
    override fun onError(exception: Exception?) {
        Log.e(TAG, "Vosk recognition error", exception)
        updateNotification("Mic Error: ${exception?.message}")
    }
    
    override fun onTimeout() {
        if (speechService != null) {
            speechService?.startListening(this)
        }
    }

    private fun inspectResult(raw: String?) {
        if (raw.isNullOrBlank() || sosTriggered) return
        try {
            val recognized = JSONObject(raw).optString("text").trim().lowercase()
            if (recognized.contains("help")) {
                sosTriggered = true
                Log.w(TAG, "WAKE WORD DETECTED: help")
                updateNotification("🚨 SOS TRIGGERED! 🚨")
                triggerSOSProtocol()
            }
        } catch (error: Exception) {
            Log.e(TAG, "Could not parse Vosk result", error)
        }
    }

    private fun triggerSOSProtocol() {
        Log.w(TAG, "SOS protocol invoked. Activating GuardianService...")
        val intent = Intent(this, GuardianService::class.java).apply {
            action = GuardianService.ACTION_MANUAL_SOS
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.startForegroundService(this, intent)
        } else {
            startService(intent)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Guardian Wake Word", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Silent, on-device detection" }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(message: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_silent_mode)
            .setContentTitle("Guardian Status")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setOngoing(true)
            .setSilent(true)
            .build()

    private fun updateNotification(message: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(message))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        speechService?.stop()
        speechService?.shutdown()
        speechService = null
        model?.close()
        model = null
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "WakeWordService"
        private const val CHANNEL_ID = "guardian_wake_word"
        private const val NOTIFICATION_ID = 4102
        private const val MODEL_DIRECTORY = "vosk-model-small-en-us-0.15"
        private const val SAMPLE_RATE = 16_000.0f
        private const val GRAMMAR = """["help", "[unk]"]"""
    }
}
