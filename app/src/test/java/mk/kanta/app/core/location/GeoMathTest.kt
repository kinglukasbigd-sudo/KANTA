package mk.kanta.app.core.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoMathTest {

    private val centar = LatLon(41.9965, 21.4314) // Skopje centre (§4.1)

    @Test fun `a point is zero metres from itself`() = assertEquals(0.0, GeoMath.distanceMetres(centar, centar), 1e-9)

    @Test fun `one thousandth of a degree north is about 111 m`() =
        assertEquals(111.2, GeoMath.distanceMetres(centar, LatLon(centar.lat + 0.001, centar.lon)), 0.5)

    @Test fun `east-west metres shrink with latitude`() {
        // At 42°N a degree of longitude is ~74% of one at the equator.
        val d = GeoMath.distanceMetres(centar, LatLon(centar.lat, centar.lon + 0.001))
        assertEquals(82.7, d, 0.8)
    }

    @Test fun `distance is symmetric`() {
        val other = LatLon(42.0045, 21.4090)
        assertEquals(GeoMath.distanceMetres(centar, other), GeoMath.distanceMetres(other, centar), 1e-9)
    }

    @Test fun `the 60 m report rule is distinguishable at street scale`() {
        val fiftyFive = LatLon(centar.lat + 55.0 / 111_195.0, centar.lon)
        val sixtyFive = LatLon(centar.lat + 65.0 / 111_195.0, centar.lon)
        assertTrue(GeoMath.distanceMetres(centar, fiftyFive) < 60.0)
        assertTrue(GeoMath.distanceMetres(centar, sixtyFive) > 60.0)
    }

    @Test fun `bounding box contains the whole radius`() {
        val (sw, ne) = GeoMath.boundingBox(centar, 120.0)
        assertTrue(GeoMath.distanceMetres(centar, LatLon(ne.lat, centar.lon)) >= 119.9)
        assertTrue(GeoMath.distanceMetres(centar, LatLon(centar.lat, sw.lon)) >= 119.9)
    }
}
