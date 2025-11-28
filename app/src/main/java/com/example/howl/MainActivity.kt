package com.example.howl

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
    
    override fun attachBaseContext(newBase: Context) {
        // 在这里应用语言设置，这是正确的生命周期方法
        val updatedContext = LanguageUtils.applyLanguage(newBase)
        super.attachBaseContext(updatedContext)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            HowlTheme {

                // 首先初始化数据库，加载设置
                val howlDatabase = HowlDatabase.getDatabase(this)
                DataRepository.initialise(db = howlDatabase)


                // 应用用户选择的语言设置
                val language = DataRepository.miscOptionsState.value.language
                LanguageUtils.saveLanguage(this, language)
                val mainOptionsViewModel: MainOptionsViewModel = viewModel()
                val tabLayoutViewModel: TabLayoutViewModel = viewModel()
                val playerViewModel: PlayerViewModel = viewModel()
                val generatorViewModel: GeneratorViewModel = viewModel()
                val activityHostViewModel: ActivityHostViewModel = viewModel()
                val settingsViewModel: SettingsViewModel = viewModel()
                var isInitialised by rememberSaveable { mutableStateOf(false) }

                LaunchedEffect(Unit) {
                    if (!isInitialised) {
                        isInitialised = true
                        val androidVersion = Build.VERSION.RELEASE
                        val androidSDK = Build.VERSION.SDK_INT
                        HLog.d("Howl", "Howl $howlVersion running on Android $androidVersion (SDK $androidSDK)")
                        
                        // 1. 首先加载设置
                        DataRepository.loadSettings()
                        
                        // 2. 然后保存语言设置到SharedPreferences，确保下次启动时能正确加载
                        val language = DataRepository.miscOptionsState.value.language
                        LanguageUtils.saveLanguage(this@MainActivity, language)
                        HLog.d("MainActivity", "Loaded and saved language setting: $language")
                        
                        // 3. 其他初始化操作
                        Player.switchOutput(DataRepository.outputState.value.outputType)
                        if (DataRepository.miscOptionsState.value.remoteAccess)
                            RemoteControlServer.start()
                    }
                }

                Generator.initialise()
                Player.initialise(context = this)
                BluetoothHandler.initialise(context = this,
                    onConnectionStatusUpdate = { DataRepository.setCoyoteConnectionStatus(it) },
                    )

                val connectionStatus by DataRepository.coyoteConnectionStatus.collectAsStateWithLifecycle()
                val batteryPercent by DataRepository.coyoteBatteryLevel.collectAsStateWithLifecycle()

                Scaffold(
                    bottomBar = {
                        ConnectionStatusBar(connectionStatus,
                            batteryPercent,
                            { BluetoothHandler.attemptConnection() },
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
                            settingsViewModel = settingsViewModel,
                            generatorViewModel = generatorViewModel,
                            activityHostViewModel = activityHostViewModel,
                        )
                    }
                }
            }
        }
    }
}

