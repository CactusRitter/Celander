package com.celander.app.event

import androidx.annotation.StringRes
import com.ce.kit.ui.glass.glassTile
import androidx.compose.material3.MaterialTheme
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
import com.ce.kit.ui.theme.CeTheme
import com.ce.kit.ui.GlassSheet
import com.ce.kit.ui.rememberGlassSheetController
import com.ce.kit.ui.springClickable

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
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 16.dp)) {
            Text(
                stringResource(title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(vertical = 8.dp).semantics { heading() },
            )
            if (limited) {
                Text(stringResource(R.string.editor_series_limited), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            listOf(
                SeriesScope.THIS to R.string.scope_this,
                SeriesScope.THIS_AND_FOLLOWING to R.string.scope_following,
                SeriesScope.ALL to R.string.scope_all,
            ).filter { it.first in choices }.forEach { (scope, label) ->
                Text(
                    stringResource(label),
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .fillMaxWidth()
                        .springClickable(role = Role.Button, shape = RoundedCornerShape(12.dp)) {
                            chosen = scope
                            controller.close()
                        }
                        .glassTile(RoundedCornerShape(12.dp))
                        .heightIn(min = 52.dp)
                        .padding(horizontal = 16.dp, vertical = 12.dp + 2.dp),
                )
            }
        }
    }
}
