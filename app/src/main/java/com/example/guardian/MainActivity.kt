package com.example.guardian

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class MainActivity : ComponentActivity() {

    private lateinit var sharedPreferences: SharedPreferences

    companion object {
        private const val PREFS_NAME = "guardian_prefs"
        private const val KEY_EMERGENCY_NUMBER = "emergency_contact_number"
        private const val DEFAULT_EMERGENCY_NUMBER = "+15551234567"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        sharedPreferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedNumber = sharedPreferences.getString(KEY_EMERGENCY_NUMBER, DEFAULT_EMERGENCY_NUMBER) ?: DEFAULT_EMERGENCY_NUMBER

        GuardianService.emergencyPhoneNumber = savedNumber
        GuardianService.emergencySmsNumber = savedNumber

        setContent {
            GuardianAppTheme {
                GuardianMainScreen(
                    initialEmergencyNumber = savedNumber,
                    onSaveEmergencyNumber = { newNumber ->
                        sharedPreferences.edit().putString(KEY_EMERGENCY_NUMBER, newNumber).apply()
                        GuardianService.emergencyPhoneNumber = newNumber
                        GuardianService.emergencySmsNumber = newNumber
                        Toast.makeText(this, "Emergency contact saved: $newNumber", Toast.LENGTH_SHORT).show()
                    },
                    onStartService = { startGuardianService() },
                    onStopService = { stopGuardianService() },
                    onCancelAlarm = { cancelAlarm() },
                    onTriggerManualSos = { triggerManualSos() },
                    onStartWakeWord = { startWakeWordService() }
                )
            }
        }
    }

    private fun startGuardianService() {
        if (!hasForegroundServicePermissions()) {
            Toast.makeText(this, "Allow permissions before arming Guardian.", Toast.LENGTH_LONG).show()
            return
        }
        val intent = Intent(this, GuardianService::class.java).apply {
            action = GuardianService.ACTION_START_FGS
        }
        runCatching { ContextCompat.startForegroundService(this, intent) }
            .onSuccess { Toast.makeText(this, "Guardian protection is now armed", Toast.LENGTH_SHORT).show() }
            .onFailure { Toast.makeText(this, "Guardian could not start: ${it.message}", Toast.LENGTH_LONG).show() }
    }

    private fun stopGuardianService() {
        if (GuardianService.guardianState.value is GuardianService.GuardianState.INACTIVE) return
        val intent = Intent(this, GuardianService::class.java).apply {
            action = GuardianService.ACTION_STOP_FGS
        }
        runCatching { ContextCompat.startForegroundService(this, intent) }
            .onSuccess { Toast.makeText(this, "Guardian Protection Service Stopped", Toast.LENGTH_SHORT).show() }
    }

    private fun cancelAlarm() {
        if (GuardianService.guardianState.value !is GuardianService.GuardianState.COUNTDOWN) return
        val intent = Intent(this, GuardianService::class.java).apply {
            action = GuardianService.ACTION_CANCEL_ALARM
        }
        runCatching { ContextCompat.startForegroundService(this, intent) }
    }

    private fun triggerManualSos() {
        if (!hasForegroundServicePermissions()) {
            Toast.makeText(this, "Allow permissions before sending an SOS.", Toast.LENGTH_LONG).show()
            return
        }
        val intent = Intent(this, GuardianService::class.java).apply {
            action = GuardianService.ACTION_MANUAL_SOS
        }
        runCatching { ContextCompat.startForegroundService(this, intent) }
            .onFailure { Toast.makeText(this, "SOS could not start: ${it.message}", Toast.LENGTH_LONG).show() }
    }

    private fun startWakeWordService() {
        val serviceIntent = Intent(this, WakeWordService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.startForegroundService(this, serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    private fun hasForegroundServicePermissions(): Boolean {
        val permissions = buildList {
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            add(Manifest.permission.RECORD_AUDIO)
            add(Manifest.permission.SEND_SMS)
            add(Manifest.permission.CALL_PHONE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        return permissions.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GuardianMainScreen(
    initialEmergencyNumber: String,
    onSaveEmergencyNumber: (String) -> Unit,
    onStartService: () -> Unit,
    onStopService: () -> Unit,
    onCancelAlarm: () -> Unit,
    onTriggerManualSos: () -> Unit,
    onStartWakeWord: () -> Unit
) {
    val context = LocalContext.current
    var emergencyContactInput by remember { mutableStateOf(initialEmergencyNumber) }
    val guardianState by GuardianService.guardianState.collectAsStateWithLifecycle()

    val requiredPermissions = remember {
        val list = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.SEND_SMS,
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_SMS,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.BODY_SENSORS
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            list.add(Manifest.permission.BLUETOOTH_SCAN)
            list.add(Manifest.permission.BLUETOOTH_CONNECT)
            list.add(Manifest.permission.BLUETOOTH_ADVERTISE)
        }
        list.toTypedArray()
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val hasMic = results[Manifest.permission.RECORD_AUDIO] ?: false
        val hasNotif = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            results[Manifest.permission.POST_NOTIFICATIONS] ?: false
        } else true

        if (hasMic && hasNotif) {
            onStartWakeWord()
        }
        
        val grantedCount = results.values.count { it }
        val totalCount = results.size
        if (grantedCount < totalCount) {
            Toast.makeText(context, "Notice: ${totalCount - grantedCount} permissions pending. Full safety features require all granted permissions.", Toast.LENGTH_LONG).show()
        }
    }

    LaunchedEffect(Unit) {
        permissionLauncher.launch(requiredPermissions)
    }

    val currentCountdown = guardianState as? GuardianService.GuardianState.COUNTDOWN
    if (currentCountdown != null) {
        UrgentCountdownDialog(
            remainingSeconds = currentCountdown.remainingSeconds,
            reason = currentCountdown.reason,
            onCancel = onCancelAlarm,
            onContinue = onTriggerManualSos
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Security, contentDescription = null, tint = Color(0xFFE11D48), modifier = Modifier.size(28.dp))
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text("Guardian Safety Hub", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Color.White)
                            Text("Foreground Protection (API 34)", fontSize = 11.sp, color = Color(0xFF94A3B8))
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF0F172A))
            )
        },
        containerColor = Color(0xFF020617)
    ) { innerPadding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(innerPadding).padding(horizontal = 20.dp, vertical = 12.dp).verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)), shape = RoundedCornerShape(16.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("SYSTEM STATUS", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF94A3B8), letterSpacing = 1.sp)
                        val (statusBadgeText, statusBadgeColor) = when (guardianState) {
                            is GuardianService.GuardianState.INACTIVE -> "PROTECTION OFF" to Color(0xFF64748B)
                            is GuardianService.GuardianState.ARMED -> "ARMED & MONITORING" to Color(0xFF10B981)
                            is GuardianService.GuardianState.COUNTDOWN -> "COUNTDOWN ACTIVE" to Color(0xFFF59E0B)
                            is GuardianService.GuardianState.EXECUTING -> "EXECUTING ALARM" to Color(0xFFE11D48)
                        }
                        Surface(shape = RoundedCornerShape(20.dp), color = statusBadgeColor.copy(alpha = 0.2f), border = androidx.compose.foundation.BorderStroke(1.dp, statusBadgeColor.copy(alpha = 0.5f))) {
                            Text(statusBadgeText, color = statusBadgeColor, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = when (val state = guardianState) {
                            is GuardianService.GuardianState.INACTIVE -> "Protection is off. Start Guardian to monitor Bluetooth and movement triggers."
                            is GuardianService.GuardianState.ARMED -> "Monitoring Bluetooth HC-05 ('S' / 'SOS') and accelerometer violent struggle patterns."
                            is GuardianService.GuardianState.COUNTDOWN -> "DISTRESS TRIGGERED: ${state.reason}. ${state.remainingSeconds}s remaining before emergency dispatch."
                            is GuardianService.GuardianState.EXECUTING -> "ACTION IN PROGRESS: ${state.currentTask}"
                        },
                        fontSize = 13.sp, color = Color(0xFFE2E8F0), lineHeight = 18.sp
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(onClick = onStartService, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981), contentColor = Color.White), shape = RoundedCornerShape(10.dp)) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Start Service", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        }
                        OutlinedButton(onClick = onStopService, modifier = Modifier.weight(1f), colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFCBD5E1)), border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF334155)), shape = RoundedCornerShape(10.dp)) {
                            Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Stop Service", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(10.dp))
                    Button(
                        onClick = onStartWakeWord,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB), contentColor = Color.White),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Manual Start Wake Word", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)), shape = RoundedCornerShape(16.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("EMERGENCY CONTACT TARGET", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF94A3B8), letterSpacing = 1.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = emergencyContactInput,
                            onValueChange = { emergencyContactInput = it },
                            label = { Text("Emergency Phone Number") },
                            leadingIcon = { Icon(Icons.Default.Call, contentDescription = null, tint = Color(0xFF94A3B8)) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color(0xFFE11D48), unfocusedBorderColor = Color(0xFF334155), focusedLabelColor = Color(0xFFE11D48), cursorColor = Color(0xFFE11D48))
                        )
                        Button(onClick = { onSaveEmergencyNumber(emergencyContactInput.trim()) }, shape = RoundedCornerShape(10.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB)), modifier = Modifier.height(56.dp)) {
                            Text("Save", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))
            MassiveManualSosButton(onClick = onTriggerManualSos)
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Hardware Demo Mode: Tablet screen forced ON (keepScreenOn). Ready for Bluetooth HC-05 & Struggle Detection.",
                fontSize = 11.sp, color = Color(0xFF64748B), textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 16.dp)
            )
        }
    }
}

@Composable
fun MassiveManualSosButton(onClick: () -> Unit) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val scale by infiniteTransition.animateFloat(
        initialValue = 1f, targetValue = 1.05f,
        animationSpec = infiniteRepeatable(animation = tween(900, easing = FastOutSlowInEasing), repeatMode = RepeatMode.Reverse),
        label = "pulseScale"
    )
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(240.dp).scale(scale).clip(CircleShape).background(Color(0xFF881337).copy(alpha = 0.35f)).border(3.dp, Color(0xFFBE123C).copy(alpha = 0.6f), CircleShape)) {
            Button(onClick = onClick, shape = CircleShape, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE11D48), contentColor = Color.White), elevation = ButtonDefaults.buttonElevation(defaultElevation = 12.dp, pressedElevation = 4.dp), modifier = Modifier.size(200.dp).clip(CircleShape)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Icon(Icons.Default.Warning, contentDescription = "SOS", modifier = Modifier.size(54.dp), tint = Color.White)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("MANUAL SOS", fontSize = 22.sp, fontWeight = FontWeight.Black, letterSpacing = 1.5.sp, textAlign = TextAlign.Center)
                    Text("INSTANT TRIGGER", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFFFECDD3))
                }
            }
        }
    }
}

@Composable
fun UrgentCountdownDialog(remainingSeconds: Int, reason: String, onCancel: () -> Unit, onContinue: () -> Unit) {
    AlertDialog(
        onDismissRequest = { },
        containerColor = Color(0xFF0F172A),
        shape = RoundedCornerShape(24.dp),
        title = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Warning, contentDescription = null, tint = Color(0xFFF59E0B), modifier = Modifier.size(44.dp))
                Spacer(modifier = Modifier.height(8.dp))
                Text("EMERGENCY TRIGGERED", fontSize = 18.sp, fontWeight = FontWeight.Black, color = Color.White, textAlign = TextAlign.Center)
            }
        },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Text("Source: $reason", fontSize = 12.sp, color = Color(0xFFCBD5E1), textAlign = TextAlign.Center)
                Spacer(modifier = Modifier.height(16.dp))
                Box(contentAlignment = Alignment.Center, modifier = Modifier.size(110.dp).clip(CircleShape).background(Color(0xFFE11D48).copy(alpha = 0.15f)).border(3.dp, Color(0xFFE11D48), CircleShape)) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("$remainingSeconds", fontSize = 44.sp, fontWeight = FontWeight.ExtraBold, fontFamily = FontFamily.Monospace, color = Color(0xFFFDA4AF))
                        Text("SECONDS", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color(0xFF94A3B8))
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                Text("Emergency sequence will activate when countdown expires: GPS dispatch, SOS SMS, phone call, & audio recording.", fontSize = 11.sp, color = Color(0xFF94A3B8), textAlign = TextAlign.Center, lineHeight = 15.sp)
            }
        },
        confirmButton = {
            Button(onClick = onContinue, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE11D48), contentColor = Color.White), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                Text("CONTINUE (DISPATCH NOW)", fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onCancel, colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFCBD5E1)), border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF475569)), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                Text("CANCEL ALARM", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            }
        }
    )
}

@Composable
fun GuardianAppTheme(content: @Composable () -> Unit) {
    val darkColors = darkColorScheme(
        primary = Color(0xFFE11D48),
        onPrimary = Color.White,
        surface = Color(0xFF0F172A),
        background = Color(0xFF020617)
    )
    MaterialTheme(colorScheme = darkColors, content = content)
}
