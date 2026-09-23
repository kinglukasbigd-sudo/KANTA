package mk.kanta.app.core.data.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import mk.kanta.app.core.data.local.ContainerDao
import mk.kanta.app.core.data.local.KantaDatabase
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): KantaDatabase =
        Room.databaseBuilder(context, KantaDatabase::class.java, "kanta.db")
            // The container table is a cache of server state, never the source of
            // truth, so throwing it away on a schema change is correct and avoids
            // carrying migrations for data we can re-fetch.
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()

    @Provides
    fun provideContainerDao(database: KantaDatabase): ContainerDao = database.containerDao()
}
