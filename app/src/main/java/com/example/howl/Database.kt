package com.example.howl

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert

const val DB_NAME = "Howl"

@Entity(tableName = "settings")
data class SavedSettings(
    @PrimaryKey
    val id: Long = 0,
    //All the Coyote parameters
    val channelALimit: Int = 70,
    val channelBLimit: Int = 70,
    val channelAFrequencyBalance: Int = 200,
    val channelBFrequencyBalance: Int = 200,
    val channelAIntensityBalance: Int = 0,
    val channelBIntensityBalance: Int = 0,
    //Player advanced controls
    val showSyncFineTune: Boolean = false,
    val playbackSpeed: Float = 1.0f,
    var funscriptVolume: Float = 0.75f,
    val funscriptPositionalEffectStrength: Float = 1.0f,
    var funscriptFrequencyTimeOffset: Float = 0.1f,
    var funscriptFrequencyVarySpeed: Float = 0.5f,
    var funscriptFrequencyBlendRatio: Float = 0.5f,
    val funscriptFrequencyAlgorithm: FrequencyAlgorithmType = FrequencyAlgorithmType.BLEND,
    val funscriptAmplitudeAlgorithm: AmplitudeAlgorithmType = AmplitudeAlgorithmType.DEFAULT,
    val funscriptRemoteLatency: Float = 0.18f,
    //Player special effects
    val specialEffectsEnabled: Boolean = false,
    val frequencyInversionA: Boolean = false,
    val frequencyInversionB: Boolean = false,
    val scaleAmplitudeA: Float = 1.0f,
    val scaleAmplitudeB: Float = 1.0f,
    var frequencyFeel: Float = 1.0f,
    val amplitudeNoiseSpeed: Float = 5.0f,
    val amplitudeNoiseAmount: Float = 0.0f,
    val frequencyNoiseSpeed: Float = 5.0f,
    val frequencyNoiseAmount: Float = 0.0f,
    //Generator controls
    val autoChange: Boolean = true,
    val speedChangeProbability: Double = 0.2,
    val amplitudeChangeProbability: Double = 0.2,
    val frequencyChangeProbability: Double = 0.2,
    val waveChangeProbability: Double = 0.2,
    //Activity settings
    val activityChangeProbability: Float = 0.0f,
    //Misc options
    val remoteAccess: Boolean = false,
    val showPowerMeter: Boolean = true,
    val smootherCharts: Boolean = true,
    val showDebugLog: Boolean = false,
    val powerStepSizeA: Int = 1,
    val powerStepSizeB: Int = 1,
    val powerAutoIncrementDelayA: Int = 120,
    val powerAutoIncrementDelayB: Int = 120,
    //Output options
    val outputType: OutputType = OutputType.COYOTE3,
    val audioWaveShape: AudioWaveShape = AudioWaveShape.SINE,
    val audioCarrierShape: AudioWaveShape = AudioWaveShape.SINE,
    val audioOutputMaxFrequency: Int = 200,
    val audioOutputMinFrequency: Int = 50,
    val audioCarrierPhaseType: AudioPhaseType = AudioPhaseType.OFFSET,
    val audioCarrierFrequency: Int = 1000,
    val audioWaveletWidth: Int = 5,
    val audioWaveletFade: Float = 0.5f,
)

@Dao
interface SavedSettingsDao {
    @Upsert
    suspend fun updateSettings(settings: SavedSettings)

    @Query("SELECT * FROM settings WHERE id = 0")
    suspend fun getSettings(): SavedSettings?
}


@Database(
    entities = [SavedSettings::class],
    version = 6,
    exportSchema = false
)
abstract class HowlDatabase : RoomDatabase() {

    abstract fun savedSettingsDao(): SavedSettingsDao

    companion object {
        @Volatile
        private var Instance: HowlDatabase? = null

        fun getDatabase(context: Context): HowlDatabase {
            // if the Instance is not null, return it, otherwise create a new database instance.
            return Instance ?: synchronized(this) {
                Room.databaseBuilder(context, HowlDatabase::class.java, DB_NAME)
                    .fallbackToDestructiveMigration(true)
                    .build()
                    .also { Instance = it }
            }
        }
    }
}
