package com.example.howl

import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.howl.ui.theme.HowlTheme

class HowlActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            HowlTheme {
                HowlAppScreen()
            }
        }
    }
}

@Composable
fun HowlAppScreen(
    mainOptionsViewModel: MainOptionsViewModel = viewModel(),
    tabLayoutViewModel: TabLayoutViewModel = viewModel(),
    playerViewModel: PlayerViewModel = viewModel(),
    generatorViewModel: GeneratorViewModel = viewModel(),
    activityHostViewModel: ActivityHostViewModel = viewModel(),
    settingsViewModel: SettingsViewModel = viewModel(),
) {
    val connectionStatus by ConnectionManager.connectionStatus.collectAsStateWithLifecycle()
    val batteryPercent by ConnectionManager.batteryLevel.collectAsStateWithLifecycle()

    // Permission launcher
    val context = LocalContext.current
    val bluetoothPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            HLog.d("Howl", "Bluetooth permissions granted.")
            BluetoothHandler.attemptConnection()
        } else {
            HLog.d("Howl", "Bluetooth permissions denied.")
        }
    }

    // Wrapper function to check permissions before connecting
    fun onConnectClick() {
        val missingPermissions = BluetoothHandler.ALL_BLE_PERMISSIONS.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isEmpty()) {
            // Permissions already granted, proceed immediately
            HLog.d("Howl", "Required Bluetooth permissions are already granted.")
            BluetoothHandler.attemptConnection()
        } else {
            // Permissions missing, request them
            HLog.d("Howl", "Requested Bluetooth permissions.")
            bluetoothPermissionLauncher.launch(missingPermissions.toTypedArray())
        }
    }

    Scaffold(
        bottomBar = {
            ConnectionStatusBar(
                connectionStatus,
                batteryPercent,
                { onConnectClick() },
                modifier = Modifier.navigationBarsPadding()
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
        ) {
            MainOptionsPanel(viewModel = mainOptionsViewModel)
            TabLayout(
                tabLayoutViewModel = tabLayoutViewModel,
                playerViewModel = playerViewModel,
                settingsViewModel = settingsViewModel,
                generatorViewModel = generatorViewModel,
                activityHostViewModel = activityHostViewModel,
            )
        }
    }
}
