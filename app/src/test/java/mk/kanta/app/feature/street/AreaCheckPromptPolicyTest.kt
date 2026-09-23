package mk.kanta.app.feature.street

import mk.kanta.app.feature.street.AreaCheckPromptPolicy.Decision
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * When the "Are all containers near you on the map?" sheet may appear (§4.6), and
 * §4.2's rule that it never interrupts what the user signed in to do.
 */
class AreaCheckPromptPolicyTest {

    private fun decide(
        signedIn: Boolean = true,
        idle: Boolean = true,
        hasLocation: Boolean = true,
        handled: Boolean = false,
        firstLogin: Boolean = false,
    ) = AreaCheckPromptPolicy.decide(signedIn, idle, hasLocation, handled, firstLogin)

    @Test fun `first login shows it without asking the server`() =
        assertEquals(Decision.ShowNow, decide(firstLogin = true))

    @Test fun `later sessions ask the server (30 days, area recently checked)`() =
        assertEquals(Decision.AskServer, decide())

    @Test fun `never while the action that caused sign-in is still in flight`() =
        assertEquals(Decision.Wait, decide(firstLogin = true, idle = false))

    @Test fun `never more than once per session — even on first login`() {
        assertEquals(Decision.Done, decide(handled = true))
        assertEquals(Decision.Done, decide(handled = true, firstLogin = true))
    }

    @Test fun `signed out users are not asked`() =
        assertEquals(Decision.Wait, decide(signedIn = false, firstLogin = true))

    @Test fun `without a location there is no "near you" to ask about`() =
        assertEquals(Decision.Wait, decide(hasLocation = false))
}
