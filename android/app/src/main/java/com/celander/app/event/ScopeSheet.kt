package com.celander.app.event

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.celander.app.R
import com.celander.app.core.recurrence.SeriesScope
import com.celander.app.designsystem.CelanderTheme
import com.celander.app.designsystem.GlassSheet
import com.celander.app.designsystem.Radii
import com.celander.app.designsystem.Spacing
import com.celander.app.designsystem.glassTile
import com.celander.app.designsystem.rememberGlassSheetController
import com.celander.app.designsystem.springClickable

/** Asks which occurrences of a series an action applies to. A sheet instead of a dialog. */
@Composable
fun ScopeSheet(
    @StringRes title: Int,
    destructive: Boolean,
    onChoose: (SeriesScope) -> Unit,
    onDismissed: () -> Unit,
    choices: List<SeriesScope> = SeriesScope.entries,
    /** Some scopes were left out because the calendar cannot store them (a series from Google). */
    limited: Boolean = false,
) {
    val controller = rememberGlassSheetController()
    var chosen by remember { mutableStateOf<SeriesScope?>(null) }
    GlassSheet(
        onDismissed = {
            onDismissed()
            chosen?.let(onChoose)
        },
        controller = controller,
        paneTitle = stringResource(title),
    ) {
        Column(Modifier.padding(horizontal = Spacing.xl).padding(bottom = Spacing.l)) {
            Text(
                stringResource(title),
                style = CelanderTheme.typography.titleMedium,
                color = CelanderTheme.colors.textPrimary,
                modifier = Modifier.padding(vertical = Spacing.s).semantics { heading() },
            )
            if (limited) {
                Text(stringResource(R.string.editor_series_limited), style = CelanderTheme.typography.bodySmall, color = CelanderTheme.colors.textSecondary)
            }
            listOf(
                SeriesScope.THIS to R.string.scope_this,
                SeriesScope.THIS_AND_FOLLOWING to R.string.scope_following,
                SeriesScope.ALL to R.string.scope_all,
            ).filter { it.first in choices }.forEach { (scope, label) ->
                Text(
                    stringResource(label),
                    style = CelanderTheme.typography.bodyLarge,
                    color = if (destructive) CelanderTheme.colors.danger else CelanderTheme.colors.textPrimary,
                    modifier = Modifier
                        .padding(top = Spacing.s)
                        .fillMaxWidth()
                        .springClickable(role = Role.Button, shape = RoundedCornerShape(Radii.cell)) {
                            chosen = scope
                            controller.close()
                        }
                        .glassTile(RoundedCornerShape(Radii.cell))
                        .heightIn(min = 52.dp)
                        .padding(horizontal = Spacing.l, vertical = Spacing.m + 2.dp),
                )
            }
        }
    }
}
