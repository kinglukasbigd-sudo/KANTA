package mk.kanta.app.core.data.report

import android.content.Context
import androidx.work.WorkManager
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import mk.kanta.app.core.image.AndroidPhotoProcessor
import mk.kanta.app.core.image.PhotoProcessor
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class ReportModule {

    @Binds
    abstract fun bindReportGateway(impl: DefaultReportGateway): ReportGateway

    @Binds
    abstract fun bindPhotoProcessor(impl: AndroidPhotoProcessor): PhotoProcessor

    companion object {
        @Provides
        @Singleton
        fun provideWorkManager(@ApplicationContext context: Context): WorkManager =
            WorkManager.getInstance(context)
    }
}
