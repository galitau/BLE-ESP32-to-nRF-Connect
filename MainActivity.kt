package com.example.electrium

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.electrium.ui.theme.ElectriumTheme

class MainActivity : ComponentActivity() {

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* UI can re-check via Activity */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ElectriumTheme {
                val connected by MapsBridgeService.connectionState.collectAsStateWithLifecycle()
                BridgeScreen(
                    bleConnected = connected,
                    onOpenNotificationAccess = {
                        startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                    },
                    onRequestPermissions = { requestRuntimePermissions() },
                    onStartBridge = { address ->
                        val prefs = getSharedPreferences(MapsBridgePrefs.NAME, MODE_PRIVATE)
                        prefs.edit().putString(MapsBridgePrefs.DEVICE_ADDRESS, address.trim()).apply()
                        MapsBridgeService.start(this, address)
                    },
                    onStopBridge = { MapsBridgeService.stop(this) }
                )
            }
        }
    }

    private fun requestRuntimePermissions() {
        val needs = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.filter { perm ->
            ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()
        if (needs.isNotEmpty()) permissionLauncher.launch(needs)
    }
}

/** Shared prefs key for device address (mirrors internal MapsBridgeService prefs). */
private object MapsBridgePrefs {
    const val NAME = "maps_bridge_prefs"
    const val DEVICE_ADDRESS = "device_address"
}

@Composable
private fun BridgeScreen(
    bleConnected: Boolean,
    onOpenNotificationAccess: () -> Unit,
    onRequestPermissions: () -> Unit,
    onStartBridge: (String) -> Unit,
    onStopBridge: () -> Unit,
) {
    val context = LocalContext.current
    val prefs = remember {
        context.getSharedPreferences(MapsBridgePrefs.NAME, Context.MODE_PRIVATE)
    }
    var address by remember {
        mutableStateOf(prefs.getString(MapsBridgePrefs.DEVICE_ADDRESS, "") ?: "")
    }

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .padding(20.dp)
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Maps → ESP32 BLE bridge", style = MaterialTheme.typography.titleLarge)
            Text(
                text = if (bleConnected) "ESP32: Connected" else "ESP32: Disconnected",
                style = MaterialTheme.typography.bodyLarge,
                color = if (bleConnected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.error
            )
            OutlinedTextField(
                value = address,
                onValueChange = { address = it },
                label = { Text("ESP32 Bluetooth address (MAC)") },
                placeholder = { Text("e.g. AA:BB:CC:DD:EE:FF") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                onClick = onOpenNotificationAccess,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Open Notification Access")
            }
            Button(
                onClick = onRequestPermissions,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Request BLE & location permissions")
            }
            Button(
                onClick = {
                    if (address.isNotBlank()) onStartBridge(address)
                },
                enabled = address.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Start bridge (foreground service)")
            }
            Button(
                onClick = onStopBridge,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Stop bridge")
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Enable notification access for this app, grant permissions, enter your ESP32 MAC, then start navigation in Google Maps. Long instructions use MTU 517.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun BridgePreview() {
    ElectriumTheme {
        BridgeScreen(
            bleConnected = false,
            onOpenNotificationAccess = {},
            onRequestPermissions = {},
            onStartBridge = { _ -> },
            onStopBridge = {}
        )
    }
}
