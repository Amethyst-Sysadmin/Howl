package com.example.howl

import android.util.Log
import android.app.Application
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

const val howlVersion = BuildConfig.VERSION_NAME

class HowlApp : Application() {
    val database: HowlDatabase by lazy { HowlDatabase.getDatabase(this) }

    override fun onCreate() {
        super.onCreate()

        Prefs.initialise(db = database)

        val androidVersion = Build.VERSION.RELEASE
        val androidSDK = Build.VERSION.SDK_INT
        HLog.d("Howl", "Howl $howlVersion running on Android $androidVersion (SDK $androidSDK)")

        // Load preferences asynchronously and initialise dependent components in a callback
        Prefs.loadAll {
            withContext(Dispatchers.Main) {
                Player.switchOutput(Prefs.outputType.value)
            }
            RemoteControlServer.initialise()
            Log.d("Howl", "Async initialisation complete.")
        }

        // Context-based initialisations that don't depend on Prefs
        BluetoothHandler.initialise(
            context = applicationContext,
            onConnectionStatusUpdate = { ConnectionManager.setConnectionStatus(it) }
        )
        Player.initialise(context = applicationContext)
        Generator.initialise()
    }
}