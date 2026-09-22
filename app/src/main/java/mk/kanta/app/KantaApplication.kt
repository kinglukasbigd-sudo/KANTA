package mk.kanta.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * Application entry point. Provides the Hilt graph and wires WorkManager to it so workers
 * (upload queue retry, periodic "Fixed!" check — spec §2 "Background") can be injected.
 */
@HiltAndroidApp
class KantaApplication : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
}
