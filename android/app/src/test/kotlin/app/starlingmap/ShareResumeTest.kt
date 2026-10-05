package app.starlingmap

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareResumeTest {
    private val now = 1_800_000_000_000L
    private val armedAt = now - 3_600_000L

    private fun due(armedAt: Long = this.armedAt, deadline: Long = 0L, route: String? = null, stopAt: Long = 0L) =
        ShareResume.due(armedAt, deadline, route, stopAt, now)

    @Test fun anArmedShareIsOffered() = assertTrue(due())

    @Test fun nothingArmedIsNotOffered() = assertFalse(due(armedAt = 0L))

    @Test fun aWindowStillOpenIsOffered() = assertTrue(due(deadline = now + 60_000L))

    @Test fun aWindowThatRanOutIsNot() = assertFalse(due(deadline = now - 1L))

    @Test fun aWindowEndingThisMillisecondIsNot() = assertFalse(due(deadline = now))

    @Test fun aStopOnTheNotificationAfterTheArmIsADecision() =
        assertFalse(due(route = "notif", stopAt = armedAt + 1L))

    @Test fun anUndatedStopOnTheNotificationIsADecisionToo() =
        assertFalse(due(route = "notif", stopAt = 0L))

    @Test fun aStopFromBeforeThisShareDoesNotVetoIt() =
        assertTrue(due(route = "notif", stopAt = armedAt - 1L))

    @Test fun stopsNobodyChoseAreOffered() {
        for (route in listOf("swipe", "system", "stalled", "renderer", "lock")) {
            assertTrue(route, due(route = route, stopAt = armedAt + 1L))
        }
    }
}
