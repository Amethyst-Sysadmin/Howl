package com.example.howl

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase

const val DB_NAME = "Howl"

@Entity(tableName = "preferences")
data class PreferenceEntity(
    @PrimaryKey val name: String,
    val value: String
)

@Dao
interface PreferencesDao {

    @Query("SELECT * FROM preferences")
    suspend fun getAll(): List<PreferenceEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(pref: PreferenceEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(prefs: List<PreferenceEntity>)
}

@Database(
    entities = [PreferenceEntity::class],
    version = 7,
    exportSchema = false
)
abstract class HowlDatabase : RoomDatabase() {

    abstract fun preferencesDao(): PreferencesDao

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
