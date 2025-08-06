package com.example.howl

import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.howl.ui.theme.HowlTheme

class MainActivity : ComponentActivity() {
    override fun onBackPressed() {
        //super.onBackPressed()
    }
    override fun onDestroy() {
        super.onDestroy()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            HowlTheme {
                val howlDatabase = HowlDatabase.getDatabase(this)
                DataRepository.initialise(db = howlDatabase)
                val mainOptionsViewModel: MainOptionsViewModel = viewModel()
                val tabLayoutViewModel: TabLayoutViewModel = viewModel()
                val playerViewModel: PlayerViewModel = viewModel()
                val generatorViewModel: GeneratorViewModel = viewModel()
                val activityHostViewModel: ActivityHostViewModel = viewModel()
                val coyoteParametersViewModel: CoyoteParametersViewModel = viewModel()
                LaunchedEffect(true) {
                    val androidVersion = Build.VERSION.RELEASE
                    val androidSDK = Build.VERSION.SDK_INT
                    HLog.d("Howl", "Howl $howlVersion running on Android $androidVersion (SDK $androidSDK)")
                    DataRepository.loadSettings()
                    if (DataRepository.miscOptionsState.value.remoteAccess)
                        RemoteControlServer.start()
                }
                Generator.initialise()
                Player.initialise(context = this)
                DGCoyote.initialise(context = this,
                    onConnectionStatusUpdate = { DataRepository.setCoyoteConnectionStatus(it) },
                    onBatteryLevelUpdate = { DataRepository.setCoyoteBatteryLevel(it) },
                    onPowerLevelUpdate = { channel:Int, power:Int ->
                        if(channel == 0)
                            DataRepository.setChannelAPower(power)
                        else if (channel == 1)
                            DataRepository.setChannelBPower(power)
                    } )

                val connectionStatus by DataRepository.coyoteConnectionStatus.collectAsStateWithLifecycle()
                val batteryPercent by DataRepository.coyoteBatteryLevel.collectAsStateWithLifecycle()

                Scaffold(
                    bottomBar = {
                        ConnectionStatusBar(connectionStatus,
                            batteryPercent,
                            { DGCoyote.connect(DataRepository.coyoteParametersState.value) },
                            modifier = Modifier.navigationBarsPadding()
                        )
                    }
                ) { innerPadding ->
                    Column (
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                            .verticalScroll(rememberScrollState())
                    ){
                        MainOptionsPanel(
                            viewModel = mainOptionsViewModel
                        )
                        TabLayout (
                            tabLayoutViewModel = tabLayoutViewModel,
                            playerViewModel = playerViewModel,
                            coyoteParametersViewModel = coyoteParametersViewModel,
                            generatorViewModel = generatorViewModel,
                            activityHostViewModel = activityHostViewModel,
                            frequencyRange = mainOptionsViewModel.mainOptionsState.collectAsStateWithLifecycle().value.frequencyRange
                        )
                    }
                }
            }
        }
    }
}

