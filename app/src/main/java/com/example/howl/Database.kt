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
    val channelAFrequencyBalance: Int = 160,
    val channelBFrequencyBalance: Int = 160,
    val channelAIntensityBalance: Int = 0,
    val channelBIntensityBalance: Int = 0,
    //Player advanced controls
    val frequencyInversionA: Boolean = false,
    val frequencyInversionB: Boolean = false,
    val channelBiasFactor: Float = 0.7f,
    val frequencyModEnable: Boolean = false,
    val frequencyModStrength: Float = 0.1f,
    val frequencyModPeriod: Float = 1.0f,
    val frequencyModInvert: Boolean = false,
    //Generator controls
    val autoCycle: Boolean = false,
    val autoCycleTime: Int = 90,
    //Misc options
    val powerStepSize: Int = 1
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
    version = 2,
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
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { Instance = it }
            }
        }
    }
}
