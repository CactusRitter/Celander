package com.celander.app.ui

import com.celander.app.settings.AppSettings
import com.ce.kit.ui.theme.CeTheme
import com.ce.kit.ui.theme.ThemeMode
import android.app.Application
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Each segment takes taps over a full touch target, while the control keeps its height. */
@Config(application = Application::class, qualifiers = "w411dp-h891dp")
@RunWith(RobolectricTestRunner::class)
class SegmentedControlTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun segmentsAreFullTouchTargets() {
        var picked = 0
        compose.setContent {
            CeTheme(AppSettings(themeMode = ThemeMode.LIGHT, transparency = 0)) {
                SegmentedControl(listOf("Event", "Task"), selectedIndex = picked, onSelect = { picked = it }, modifier = Modifier.testTag("segments"))
            }
        }

        compose.onNodeWithTag("segments").assertHeightIsEqualTo(Sizes.touchTarget)
        compose.onNodeWithText("Event").assertHeightIsAtLeast(Sizes.touchTarget)
        compose.onNodeWithText("Task").assertHeightIsAtLeast(Sizes.touchTarget).performClick()

        assertEquals(1, picked)
    }
}
