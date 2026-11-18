package com.celander.app.calendar

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import com.celander.app.settings.AppSettings
import com.ce.kit.ui.theme.CeTheme
import com.ce.kit.ui.theme.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The pager under the year, month and day views: following its index, reporting swipes, nothing else. */
@Config(application = Application::class, qualifiers = "w411dp-h891dp")
@RunWith(RobolectricTestRunner::class)
class PeriodPagerTest {
    @get:Rule val compose = createComposeRule()

    private var index by mutableLongStateOf(0L)
    private val reported = mutableListOf<Long>()

    private fun showPager() {
        compose.setContent {
            CeTheme(AppSettings(themeMode = ThemeMode.LIGHT, transparency = 0)) {
                PeriodPager(
                    index = index,
                    onIndexChange = { reported += it; index = it },
                    modifier = Modifier.fillMaxSize(),
                ) { page ->
                    Box(Modifier.fillMaxSize().testTag("page $page")) { Text("Period $page") }
                }
            }
        }
        compose.waitForIdle()
    }

    /** Left edge of [page] in px: 0 when it fills the screen. */
    private fun left(page: Long): Float = compose.onNodeWithTag("page $page").fetchSemanticsNode().boundsInRoot.left

    @Test
    fun aSwipeIsReported() {
        showPager()
        compose.onNodeWithTag("page 0").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        assertEquals(listOf(1L), reported)
        assertEquals(0f, left(1), 1f)
    }

    @Test
    fun aMoveCalledBackHalfWayReturns() {
        showPager()
        compose.mainClock.autoAdvance = false
        index = 1
        // The slide to the next period is under way, not yet half way …
        compose.mainClock.advanceTimeBy(80)
        // … when the index goes back to where it came from.
        index = 0
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertEquals("not stuck between two pages", 0f, left(0), 1f)
        assertEquals(emptyList<Long>(), reported)
    }

    @Test
    fun twoQuickStepsBothCount() {
        showPager()
        compose.mainClock.autoAdvance = false
        index = 1
        // Past half way to the next period, a second Ctrl+→ asks for the one after.
        compose.mainClock.advanceTimeBy(160)
        index = 2
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertEquals("the page passed on the way is not reported", emptyList<Long>(), reported)
        assertEquals(2L, index)
        assertEquals(0f, left(2), 1f)
    }

    @Test
    fun aJumpBeyondThePagesLandsOnItsIndex() {
        showPager()
        // Decades of days away: further than the pages around the start reach.
        index = 50_000
        compose.waitForIdle()
        assertEquals(0f, left(50_000), 1f)
        assertEquals(emptyList<Long>(), reported)

        // From there the pager pages on as usual.
        index = 50_001
        compose.waitForIdle()
        assertEquals(0f, left(50_001), 1f)
        compose.onNodeWithTag("page 50001").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        assertEquals(listOf(50_002L), reported)
    }
}
