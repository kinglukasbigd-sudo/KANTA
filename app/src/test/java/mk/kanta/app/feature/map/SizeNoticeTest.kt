package mk.kanta.app.feature.map

import mk.kanta.app.R
import mk.kanta.app.core.data.model.ContainerKind
import org.junit.Assert.assertEquals
import org.junit.Test

/** §4.6 "Bin size": what the person hears after answering Small or Big. */
class SizeNoticeTest {

    @Test fun `the first answer on an unknown bin marks it`() {
        assertEquals(R.string.notice_kind_changed_big, sizeNotice(ContainerKind.BIG, kindNow = "big", changed = true))
        assertEquals(
            R.string.notice_kind_changed_small,
            sizeNotice(ContainerKind.SMALL, kindNow = "small", changed = true),
        )
    }

    @Test fun `agreeing with what the map shows is counted`() =
        assertEquals(R.string.notice_size_counted, sizeNotice(ContainerKind.BIG, kindNow = "big", changed = false))

    @Test fun `outvoted for now, the map keeps the majority`() =
        assertEquals(R.string.notice_kind_vote, sizeNotice(ContainerKind.SMALL, kindNow = "big", changed = false))

    @Test fun `a size the app does not know never reads as a change`() =
        assertEquals(R.string.notice_kind_vote, sizeNotice(ContainerKind.BIG, kindNow = "huge", changed = true))
}
