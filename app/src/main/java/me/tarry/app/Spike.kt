package me.tarry.app

import android.app.Application
import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Room
import androidx.room.RoomDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.HiltAndroidApp
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@HiltAndroidApp
class SpikeApplication : Application()

@Entity
data class SpikeEntity(@PrimaryKey val id: String, val value: Long)

@Dao
interface SpikeDao {
    @Insert
    suspend fun insert(e: SpikeEntity)
}

@Database(entities = [SpikeEntity::class], version = 1, exportSchema = true)
abstract class SpikeDb : RoomDatabase() {
    abstract fun dao(): SpikeDao
}

@Module
@InstallIn(SingletonComponent::class)
object SpikeModule {
    @Provides
    @Singleton
    fun db(@ApplicationContext c: Context): SpikeDb = Room.databaseBuilder(c, SpikeDb::class.java, "spike").build()
}
