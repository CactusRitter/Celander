package com.celander.app.ui

import com.celander.app.settings.AppSettings
import com.ce.kit.ui.theme.CeTheme
import com.ce.kit.ui.theme.ThemeMode
import android.app.Application
import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performCustomAccessibilityActionWithLabel
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The sheet's grip carries its actions for TalkBack, a back gesture eases it back, and a closing sheet always finishes closing. */
@OptIn(ExperimentalTestApi::class)
@Config(application = Application::class, qualifiers = "w411dp-h891dp")
@RunWith(RobolectricTestRunner::class)
class GlassSheetTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private var dismissed = 0

    private fun showSheet(expandable: Boolean) {
        compose.setContent {
            CeTheme(AppSettings(themeMode = ThemeMode.LIGHT, transparency = 0)) {
                GlassSheet(onDismissed = { dismissed++ }, expandable = expandable, paneTitle = "Sheet") {
                    Text("Body", Modifier.testTag(BODY))
                }
            }
        }
        compose.waitForIdle()
    }

    private fun handle() = compose.onNodeWithContentDescription(HANDLE)

    private fun offers(clickLabel: String?, vararg actions: String) = SemanticsMatcher("click \"$clickLabel\", actions ${actions.toList()}") { node ->
        node.config.getOrNull(SemanticsActions.OnClick)?.label == clickLabel &&
            node.config.getOrNull(SemanticsActions.CustomActions)?.map { it.label } == actions.toList()
    }

    @Test
    fun theGripOpensAndClosesAHalfOpenSheet() {
        showSheet(expandable = true)
        handle().assertHeightIsAtLeast(Sizes.touchTarget)
        handle().assert(offers("Expand", "Close", "Expand"))

        handle().performClick()
        compose.waitForIdle()
        handle().assert(offers("Collapse", "Close", "Collapse"))

        handle().performCustomAccessibilityActionWithLabel("Close")
        compose.waitForIdle()
        assertEquals(1, dismissed)
    }

    @Test
    fun theGripOfAFixedSheetCanClose() {
        showSheet(expandable = false)
        handle().assert(SemanticsMatcher("actions [Close]") { node -> node.config.getOrNull(SemanticsActions.CustomActions)?.map { it.label } == listOf("Close") })

        handle().performCustomAccessibilityActionWithLabel("Close")
        compose.waitForIdle()
        assertEquals(1, dismissed)
    }

    @Test
    fun theGripCannotStopASheetOnItsWayOut() {
        showSheet(expandable = true)

        compose.mainClock.autoAdvance = false
        handle().performCustomAccessibilityActionWithLabel("Close")
        compose.mainClock.advanceTimeBy(SLIDING_MILLIS)
        // A quick second tap (the grip's click) or TalkBack still reaches the grip as it slides out.
        handle().performSemanticsAction(SemanticsActions.OnClick)
        handle().performCustomAccessibilityActionWithLabel("Expand")

        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertEquals("the sheet finishes closing", 1, dismissed)
    }

    @Test
    fun aCancelledBackGestureEasesTheSheetBack() {
        showSheet(expandable = false)
        val body = compose.onNodeWithTag(BODY)
        val rest = body.getBoundsInRoot().top
        val back = compose.activity.onBackPressedDispatcher

        compose.runOnUiThread {
            back.dispatchOnBackStarted(BackEventCompat(0f, 0f, 0f, BackEventCompat.EDGE_LEFT))
            back.dispatchOnBackProgressed(BackEventCompat(0f, 0f, 1f, BackEventCompat.EDGE_LEFT))
        }
        compose.waitForIdle()
        val preview = body.getBoundsInRoot().top
        assertTrue("the back preview moves the sheet down: $rest → $preview", preview > rest + 1.dp)

        compose.mainClock.autoAdvance = false
        compose.runOnUiThread { back.dispatchOnBackCancelled() }
        compose.mainClock.advanceTimeBy(EASING_MILLIS)
        val easing = body.getBoundsInRoot().top
        assertTrue("the sheet eases back instead of jumping: $rest ← $easing ← $preview", easing > rest + 1.dp)

        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertEquals(rest.value, body.getBoundsInRoot().top.value, 0.5f)
        assertEquals(0, dismissed)
    }

    private companion object {
        const val HANDLE = "Drag handle"
        const val BODY = "body"
        const val EASING_MILLIS = 48L

        /** A few frames into the exit; its spring runs for several hundred milliseconds. */
        const val SLIDING_MILLIS = 48L
    }
}
