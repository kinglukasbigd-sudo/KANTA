package mk.kanta.app.core.location

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Client-side distance, for UI hints only.
 *
 * The server is the authority on every distance rule (60 m to report, 30 m to add
 * — enforced in SQL with PostGIS). The app uses this to show "12 m" and to warn
 * *before* a send that would be refused, so the user can move closer instead of
 * waiting on a round trip to be told no.
 */
object GeoMath {

    private const val EARTH_RADIUS_M = 6_371_008.8

    /** Great-circle distance in metres (haversine). Accurate to well under a metre at city scale. */
    fun distanceMetres(a: LatLon, b: LatLon): Double {
        val lat1 = Math.toRadians(a.lat)
        val lat2 = Math.toRadians(b.lat)
        val dLat = lat2 - lat1
        val dLon = Math.toRadians(b.lon - a.lon)
        val h = sin(dLat / 2).pow(2) + cos(lat1) * cos(lat2) * sin(dLon / 2).pow(2)
        return 2 * EARTH_RADIUS_M * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }

    /**
     * A lat/lon box that comfortably contains a circle of [radiusMetres] around
     * [centre] — for the Room bbox query behind the container picker.
     */
    fun boundingBox(centre: LatLon, radiusMetres: Double): Pair<LatLon, LatLon> {
        val dLat = Math.toDegrees(radiusMetres / EARTH_RADIUS_M)
        val dLon = Math.toDegrees(radiusMetres / (EARTH_RADIUS_M * cos(Math.toRadians(centre.lat))))
        return LatLon(centre.lat - dLat, centre.lon - dLon) to LatLon(centre.lat + dLat, centre.lon + dLon)
    }
}
