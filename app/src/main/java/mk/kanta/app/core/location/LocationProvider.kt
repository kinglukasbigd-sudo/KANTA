package mk.kanta.app.core.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/** A point on the map. Kept free of Android types so it can cross layers freely. */
data class LatLon(val lat: Double, val lon: Double) {
    companion object {
        /** Spec §4.1 fallback when we have no fix. */
        val SKOPJE_CENTRE = LatLon(41.9965, 21.4314)
    }
}

/**
 * Fused location (§2 "Location: Fused Location Provider — no API key").
 *
 * Every method returns null rather than throwing when permission is missing or
 * location is off: on this screen a missing fix is normal, not exceptional, and
 * the map simply falls back to Skopje centre.
 */
@Singleton
class LocationProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val client by lazy { LocationServices.getFusedLocationProviderClient(context) }

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Best available position. Tries the cached last location first because it is
     * instant, and only asks for a fresh fix if there is none — the map should
     * not sit empty waiting for GPS.
     */
    @SuppressLint("MissingPermission") // guarded by hasPermission()
    suspend fun current(): LatLon? {
        if (!hasPermission()) return null

        return runCatching {
            client.lastLocation.await()?.let { LatLon(it.latitude, it.longitude) }
                ?: client.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, null)
                    .await()
                    ?.let { LatLon(it.latitude, it.longitude) }
        }.getOrNull()
    }
}
