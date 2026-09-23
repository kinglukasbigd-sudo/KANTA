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
import mk.kanta.app.core.data.local.PendingReportDao
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): KantaDatabase =
        Room.databaseBuilder(context, KantaDatabase::class.java, "kanta.db")
            // Version 1 held only the container cache, so moving from it by
            // dropping everything is harmless. It is deliberately limited to v1:
            // from v2 the database holds unsent reports, and a later schema change
            // without a Migration must fail loudly in development rather than
            // silently delete a user's queued reports.
            .fallbackToDestructiveMigrationFrom(true, 1)
            .build()

    @Provides
    fun provideContainerDao(database: KantaDatabase): ContainerDao = database.containerDao()

    @Provides
    fun providePendingReportDao(database: KantaDatabase): PendingReportDao = database.pendingReportDao()
}
