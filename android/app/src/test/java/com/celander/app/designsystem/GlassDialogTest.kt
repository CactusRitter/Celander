package com.celander.app.designsystem

import android.app.Application
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Only the scrim and the buttons answer taps; the dialog's own surface keeps it open. */
@Config(application = Application::class, qualifiers = "w411dp-h891dp")
@RunWith(RobolectricTestRunner::class)
class GlassDialogTest {
    @get:Rule val compose = createComposeRule()

    private var confirmed = 0
    private var dismissed = 0

    @Before
    fun showDialog() {
        compose.setContent {
            CelanderTheme(darkTheme = false, accentHue = 240f, glassTransparency = 0f, reduceMotion = false) {
                GlassDialog(
                    visible = true,
                    title = TITLE,
                    message = MESSAGE,
                    confirmLabel = CONFIRM,
                    onConfirm = { confirmed++ },
                    onDismiss = { dismissed++ },
                    destructive = true,
                )
            }
        }
    }

    @Test
    fun tappingTheTitleOrMessageKeepsTheDialogOpen() {
        compose.onNodeWithText(TITLE).performClick()
        compose.onNodeWithText(MESSAGE).performClick()
        compose.waitForIdle()

        assertEquals(0, dismissed)
        assertEquals(0, confirmed)
    }

    @Test
    fun tappingTheScrimDismisses() {
        // The scrim fills the screen; its corner lies outside the dialog.
        compose.onNodeWithContentDescription(CANCEL).performTouchInput { click(Offset(1f, 1f)) }
        compose.waitForIdle()

        assertEquals(1, dismissed)
        assertEquals(0, confirmed)
    }

    @Test
    fun theButtonsWorkEvenWhenTheFingerTrembles() {
        compose.onNodeWithText(CONFIRM).performClick()
        compose.onNodeWithText(CONFIRM).performTouchInput {
            down(center)
            moveBy(Offset(1f, 1f))
            up()
        }
        compose.onNodeWithText(CANCEL).performClick()
        compose.waitForIdle()

        assertEquals(2, confirmed)
        assertEquals(1, dismissed)
    }

    private companion object {
        const val TITLE = "Delete all data?"
        const val MESSAGE = "Events and tasks are removed for good."
        const val CONFIRM = "Delete"
        const val CANCEL = "Cancel"
    }
}
