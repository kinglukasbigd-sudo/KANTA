package mk.kanta.app.feature.map

import mk.kanta.app.R
import mk.kanta.app.core.data.model.ContainerKind
import org.junit.Assert.assertEquals
import org.junit.Test

/** §4.6 / 0019: what the person hears after "Yes, it's here". */
class ConfirmedNoticeTest {

    @Test fun `a plain yes is thanked as before`() {
        assertEquals(R.string.notice_confirmed, confirmedNotice(null, verified = false, kindNow = null))
        assertEquals(R.string.notice_confirmed_verified, confirmedNotice(null, verified = true, kindNow = null))
    }

    @Test fun `the second big vote turns it into a big container`() =
        assertEquals(
            R.string.notice_kind_changed_big,
            confirmedNotice(ContainerKind.BIG, verified = true, kindNow = "big"),
        )

    @Test fun `and the other way round`() =
        assertEquals(
            R.string.notice_kind_changed_small,
            confirmedNotice(ContainerKind.SMALL, verified = true, kindNow = "small"),
        )

    @Test fun `the first big vote waits for one more`() =
        assertEquals(
            R.string.notice_kind_vote,
            confirmedNotice(ContainerKind.BIG, verified = false, kindNow = "small"),
        )

    @Test fun `verified by a plain yes before, so the kind stays`() =
        assertEquals(
            R.string.notice_confirmed_verified,
            confirmedNotice(ContainerKind.BIG, verified = true, kindNow = "small"),
        )
}
