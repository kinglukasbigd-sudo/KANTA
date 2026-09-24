package mk.kanta.app.feature.map

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** §4.1: the compass shows only while the map is rotated or tilted. */
class MapNavigationTest {

    @Test fun `north and flat hides the compass`() =
        assertFalse(MapNavigation.isOffNorth(bearing = 0.0, tilt = 0.0))

    @Test fun `a tiny drift from an animation still counts as north`() {
        assertFalse(MapNavigation.isOffNorth(bearing = 0.3, tilt = 0.2))
        assertFalse(MapNavigation.isOffNorth(bearing = 359.8, tilt = 0.0))
    }

    @Test fun `any real turn shows it, whichever way round`() {
        assertTrue(MapNavigation.isOffNorth(bearing = 12.0, tilt = 0.0))
        assertTrue(MapNavigation.isOffNorth(bearing = 348.0, tilt = 0.0))
        assertTrue(MapNavigation.isOffNorth(bearing = -20.0, tilt = 0.0))
    }

    @Test fun `tilt alone shows it`() =
        assertTrue(MapNavigation.isOffNorth(bearing = 0.0, tilt = 30.0))
}
