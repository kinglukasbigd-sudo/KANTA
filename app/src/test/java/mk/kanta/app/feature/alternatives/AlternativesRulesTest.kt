package mk.kanta.app.feature.alternatives

import mk.kanta.app.core.data.model.ContainerCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** §5.2 "Nearest containers with space": the rules that are the app's own. */
class AlternativesRulesTest {

    @Test fun `walking time is 80 m a minute, rounded up`() {
        assertEquals(1, AlternativesRules.walkingMinutes(80))
        assertEquals(2, AlternativesRules.walkingMinutes(81))
        assertEquals(4, AlternativesRules.walkingMinutes(300))
        assertEquals(8, AlternativesRules.walkingMinutes(600))
    }

    @Test fun `next door is still a minute, never zero`() =
        assertEquals(1, AlternativesRules.walkingMinutes(0))

    @Test fun `close means within 300 m, inclusive`() {
        assertTrue(AlternativesRules.isClose(300))
        assertFalse(AlternativesRules.isClose(301))
    }

    @Test fun `a recycling container looks for the same material`() =
        assertEquals(ContainerCategory.GLASS, AlternativesRules.categoryFor(ContainerCategory.GLASS))

    @Test fun `where can I throw this means general waste`() =
        assertEquals(ContainerCategory.GENERAL, AlternativesRules.categoryFor(null))

    @Test fun `bags only ever go to big containers, within 600 m, at most 10`() {
        assertEquals("big", AlternativesRules.KIND)
        assertEquals(600, AlternativesRules.MAX_METRES)
        assertEquals(10, AlternativesRules.LIMIT)
    }
}
