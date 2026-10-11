package com.example.guardian

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.MediaRecorder
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Environment // 👈 ADDED
import android.os.IBinder
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.telephony.PhoneNumberUtils
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.sqrt

class GuardianService : Service(), SensorEventListener {

    sealed class GuardianState {
        object INACTIVE : GuardianState()
        object ARMED : GuardianState()
        data class COUNTDOWN(val remainingSeconds: Int, val reason: String) : GuardianState()
        data class EXECUTING(val currentTask: String) : GuardianState()
    }

    inner class LocalBinder : Binder() {
        fun getService(): GuardianService = this@GuardianService
    }

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private lateinit var sensorManager: SensorManager
    private lateinit var vibrator: Vibrator
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var powerManager: PowerManager
    private var wakeLock: PowerManager.WakeLock? = null

    private var accelerometer: Sensor? = null
    private var gyroscope: Sensor? = null

    private var countdownJob: Job? = null
    private var bluetoothJob: Job? = null

    private var lastStruggleTimestamp = 0L
    private var currentGyroMagnitude = 0.0f

    private var bluetoothSocket: BluetoothSocket? = null
    private var inputStream: InputStream? = null

    private var mediaRecorder: MediaRecorder? = null
    private var audioRecordingFile: File? = null

    companion object {
        private const val TAG = "GuardianService"
        private const val PREFS_NAME = "guardian_prefs"
        private const val KEY_EMERGENCY_NUMBER = "emergency_contact_number"
        const val NOTIFICATION_ID = 9001
        const val CHANNEL_ID = "guardian_fgs_channel"
        const val COUNTDOWN_DURATION_SECONDS = 30
        const val AUDIO_RECORDING_DURATION_MS = 180_000L

        var emergencyPhoneNumber: String = "+15551234567"
        var emergencySmsNumber: String = "+15551234567"

        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

        private val _guardianState = MutableStateFlow<GuardianState>(GuardianState.INACTIVE)
        val guardianState: StateFlow<GuardianState> = _guardianState.asStateFlow()

        const val ACTION_START_FGS = "ACTION_START_FGS"
        const val ACTION_STOP_FGS = "ACTION_STOP_FGS"
        const val ACTION_CANCEL_ALARM = "ACTION_CANCEL_ALARM"
        const val ACTION_MANUAL_SOS = "ACTION_MANUAL_SOS"
        const val ACTION_WAKE_WORD_SOS = "ACTION_WAKE_WORD_SOS"
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "GuardianService initialized")

        initializeHardware()
        createNotificationChannel()
        startAsForegroundService()

        registerSensors()
        startBluetoothSerialListener()
        _guardianState.value = GuardianState.ARMED
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL_ALARM -> cancelAlarm()
            ACTION_MANUAL_SOS -> triggerManualSos()
            ACTION_WAKE_WORD_SOS -> triggerAlarm("Wake word 'help' detected")
            ACTION_STOP_FGS -> stopServiceGracefully()
            else -> Log.d(TAG, "GuardianService running in foreground")
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private fun initializeHardware() {
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Guardian:HardwareDemoWakeLock").apply {
            setReferenceCounted(false)
            acquire(10 * 60 * 1000L)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Guardian Active Protection Service",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Monitors safety triggers"
                setShowBadge(true)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun startAsForegroundService() {
        val notification = buildServiceNotification("Guardian Active Protection: Armed & Monitoring")
        val foregroundServiceType = foregroundServiceTypeForGrantedPermissions()
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, foregroundServiceType)
    }

    private fun foregroundServiceTypeForGrantedPermissions(): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return 0
        var types = 0
        if (hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) || hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        }
        if (hasPermission(Manifest.permission.RECORD_AUDIO)) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        }
        return types
    }

    private fun hasPermission(permission: String): Boolean =
        ActivityCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun buildServiceNotification(statusText: String): Notification {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName) ?: Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, launchIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Guardian Safety System")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
    }

    private fun updateNotification(statusText: String) {
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, buildServiceNotification(statusText))
    }

    private fun registerSensors() {
        accelerometer?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        gyroscope?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return
        when (event.sensor.type) {
            Sensor.TYPE_GYROSCOPE -> {
                val rx = event.values[0]; val ry = event.values[1]; val rz = event.values[2]
                currentGyroMagnitude = sqrt(rx * rx + ry * ry + rz * rz)
            }
            Sensor.TYPE_ACCELEROMETER -> {
                val ax = event.values[0]; val ay = event.values[1]; val az = event.values[2]
                val netAcc = sqrt(ax * ax + ay * ay + az * az) - SensorManager.GRAVITY_EARTH
                val isViolentSpike = netAcc > 26.0f && currentGyroMagnitude > 4.5f
                val now = System.currentTimeMillis()
                if (isViolentSpike && (now - lastStruggleTimestamp > 8000L)) {
                    lastStruggleTimestamp = now
                    triggerAlarm("Violent Struggle Detected")
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun startBluetoothSerialListener() {
        bluetoothJob = serviceScope.launch(Dispatchers.IO) {
            val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            val adapter: BluetoothAdapter? = bluetoothManager?.adapter
            if (adapter == null || !adapter.isEnabled) return@launch

            while (isActive) {
                try {
                    if (ActivityCompat.checkSelfPermission(this@GuardianService, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        delay(5000); continue
                    }
                    val bondedDevices = adapter.bondedDevices
                    val hc05Device = bondedDevices.find { it.name?.contains("HC-05", ignoreCase = true) == true || it.name?.contains("Guardian", ignoreCase = true) == true } ?: bondedDevices.firstOrNull()
                    if (hc05Device == null) { delay(10000); continue }

                    bluetoothSocket = hc05Device.createRfcommSocketToServiceRecord(SPP_UUID)
                    adapter.cancelDiscovery()
                    bluetoothSocket?.connect()
                    inputStream = bluetoothSocket?.inputStream
                    val buffer = ByteArray(1024)

                    while (isActive) {
                        val bytesRead = inputStream?.read(buffer) ?: -1
                        if (bytesRead > 0) {
                            val receivedText = String(buffer, 0, bytesRead).trim()
                            if (receivedText.contains("S") || receivedText.contains("SOS")) {
                                withContext(Dispatchers.Main) { triggerAlarm("HC-05 Bluetooth Signal") }
                            }
                        } else if (bytesRead == -1) break
                    }
                } catch (e: Exception) {
                    try { bluetoothSocket?.close() } catch (_: Exception) {}
                    delay(5000)
                }
            }
        }
    }

    @Synchronized
    fun triggerAlarm(reason: String) {
        if (_guardianState.value is GuardianState.EXECUTING) return
        countdownJob?.cancel()
        playHeartbeatVibration()

        countdownJob = serviceScope.launch(Dispatchers.Main) {
            for (sec in COUNTDOWN_DURATION_SECONDS downTo 1) {
                _guardianState.value = GuardianState.COUNTDOWN(remainingSeconds = sec, reason = reason)
                updateNotification("EMERGENCY COUNTDOWN: ${sec}s remaining! (Reason: $reason)")
                delay(1000L)
            }
            executeEmergencyProtocol()
        }
    }

    fun triggerManualSos() {
        countdownJob?.cancel()
        vibrator.cancel()
        serviceScope.launch(Dispatchers.Main) { executeEmergencyProtocol() }
    }

    fun cancelAlarm() {
        countdownJob?.cancel()
        countdownJob = null
        vibrator.cancel()
        resetStruggleDetection()
        _guardianState.value = GuardianState.ARMED
        updateNotification("Guardian Active Protection: Armed & Monitoring")
    }

    private fun resetStruggleDetection() {
        lastStruggleTimestamp = 0L; currentGyroMagnitude = 0f
    }

    private fun playHeartbeatVibration() {
        val timings = longArrayOf(0, 150, 100, 150, 600)
        val amplitudes = intArrayOf(0, 255, 0, 255, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createWaveform(timings, amplitudes, 0))
        } else {
            @Suppress("DEPRECATION") vibrator.vibrate(timings, 0)
        }
    }

    private fun playShortVibration() {
        vibrator.cancel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION") vibrator.vibrate(500)
        }
    }

    private suspend fun executeEmergencyProtocol() {
        _guardianState.value = GuardianState.EXECUTING("Initiating Emergency Actions")
        playShortVibration()

        updateNotification("EXECUTING: Acquiring precise GPS coordinates...")
        _guardianState.value = GuardianState.EXECUTING("Fetching GPS Location")
        val locationString = fetchCurrentGpsLocation()

        updateNotification("EXECUTING: Dispatching emergency SMS with GPS link...")
        _guardianState.value = GuardianState.EXECUTING("Sending Emergency SMS")
        val mapsLink = if (locationString != null) "https://maps.google.com/?q=$locationString" else "Location unavailable"
        val smsMessage = "SOS! I need help. Location: $mapsLink"
        sendEmergencySms(emergencySmsRecipient(), smsMessage)

        updateNotification("EXECUTING: Placing phone call to emergency contact...")
        _guardianState.value = GuardianState.EXECUTING("Calling Emergency Contact")
        placeEmergencyCall(emergencyPhoneNumber)

        updateNotification("EXECUTING: Recording 3 minutes of ambient evidence...")
        _guardianState.value = GuardianState.EXECUTING("Recording 3-min Ambient Audio")
        startAudioRecording()

        delay(AUDIO_RECORDING_DURATION_MS)
        stopAudioRecording()

        _guardianState.value = GuardianState.ARMED
        updateNotification("Guardian Active Protection: Armed & Monitoring")
    }

    @SuppressLint("MissingPermission")
    private suspend fun fetchCurrentGpsLocation(): String? = withContext(Dispatchers.IO) {
        if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) && !hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)) return@withContext null
        try {
            val cts = CancellationTokenSource()
            val location = fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.token)
            var elapsed = 0
            while (!location.isComplete && elapsed < 70) { delay(100); elapsed++ }
            if (location.isSuccessful && location.result != null) {
                return@withContext "${location.result.latitude},${location.result.longitude}"
            }
        } catch (e: Exception) { Log.e(TAG, "GPS Error", e) }
        return@withContext null
    }

    private fun getTargetSmsManager(): SmsManager {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val baseSmsManager = getSystemService(SmsManager::class.java)
            val defaultSubId = SubscriptionManager.getDefaultSmsSubscriptionId()
            if (defaultSubId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) baseSmsManager.createForSubscriptionId(defaultSubId) else baseSmsManager
        } else {
            @Suppress("DEPRECATION") SmsManager.getDefault()
        }
    }

    private fun sendEmergencySms(phoneNumber: String, message: String) {
        if (!hasPermission(Manifest.permission.SEND_SMS)) return
        val sanitizedNumber = PhoneNumberUtils.normalizeNumber(phoneNumber) ?: return
        if (sanitizedNumber.isBlank()) return
        try {
            val smsManager = getTargetSmsManager()
            val parts = smsManager.divideMessage(message)
            val sentIntents = ArrayList<PendingIntent>(parts.size)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            for (index in parts.indices) {
                sentIntents.add(PendingIntent.getBroadcast(this, index, Intent("com.example.guardian.SMS_SENT").apply { setPackage(packageName) }, flags))
            }
            smsManager.sendMultipartTextMessage(sanitizedNumber, null, parts, sentIntents, null)
        } catch (e: Exception) { Log.e(TAG, "SMS Error", e) }
    }

    private fun emergencySmsRecipient(): String {
        val storedNumber = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_EMERGENCY_NUMBER, emergencySmsNumber).orEmpty()
        return PhoneNumberUtils.normalizeNumber(storedNumber) ?: ""
    }

    private fun placeEmergencyCall(phoneNumber: String) {
        if (!hasPermission(Manifest.permission.CALL_PHONE)) return
        try {
            startActivity(Intent(Intent.ACTION_CALL).apply { data = Uri.parse("tel:$phoneNumber"); addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
        } catch (e: Exception) { Log.e(TAG, "Call Error", e) }
    }

    private fun startAudioRecording() {
        if (!hasPermission(Manifest.permission.RECORD_AUDIO)) return
        try {
            // 👇 CHANGED: Save to external visible folder instead of private internal folder
            val dir = File(getExternalFilesDir(Environment.DIRECTORY_MUSIC), "GuardianEvidence").apply { mkdirs() }
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            audioRecordingFile = File(dir, "EVIDENCE_${timeStamp}.m4a")
            mediaRecorder = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(this) else @Suppress("DEPRECATION") MediaRecorder()).apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setOutputFile(audioRecordingFile?.absolutePath)
                prepare(); start()
            }
            Log.d(TAG, "Recording started: ${audioRecordingFile?.absolutePath}")
        } catch (e: Exception) { Log.e(TAG, "Recorder Error", e) }
    }

    private fun stopAudioRecording() {
        val recorder = mediaRecorder ?: return
        mediaRecorder = null
        try { recorder.stop() } catch (_: RuntimeException) {} finally { recorder.release() }
        Log.d(TAG, "Recording saved: ${audioRecordingFile?.absolutePath}")
    }

    private fun stopServiceGracefully() {
        cancelAlarm()
        stopAudioRecording()
        try { bluetoothSocket?.close() } catch (_: Exception) {}
        serviceScope.cancel()
        sensorManager.unregisterListener(this)
        wakeLock?.let { if (it.isHeld) it.release() }
        stopForeground(STOP_FOREGROUND_REMOVE)
        _guardianState.value = GuardianState.INACTIVE
        stopSelf()
    }

    override fun onDestroy() {
        stopServiceGracefully()
        super.onDestroy()
    }
}
