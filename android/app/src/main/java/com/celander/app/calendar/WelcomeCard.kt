package com.celander.app.calendar

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.celander.app.R
import com.celander.app.designsystem.AccentButton
import com.celander.app.designsystem.CelanderTheme
import com.celander.app.designsystem.GlassSurface
import com.celander.app.designsystem.Radii
import com.celander.app.designsystem.SoftButton
import com.celander.app.designsystem.Spacing
import com.celander.app.designsystem.blockTouchesBelow

/** First-start card while the calendar is completely empty. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WelcomeCard(onCreate: () -> Unit, onLoadSamples: () -> Unit, modifier: Modifier = Modifier) {
    val colors = CelanderTheme.colors
    GlassSurface(modifier.widthIn(max = 560.dp).fillMaxWidth().blockTouchesBelow(), shape = RoundedCornerShape(Radii.card)) {
        Column(Modifier.padding(Spacing.l)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(painterResource(R.drawable.logo_mark), null, modifier = Modifier.size(28.dp))
                Spacer(Modifier.size(Spacing.s))
                Text(
                    stringResource(R.string.welcome_title),
                    style = CelanderTheme.typography.titleMedium,
                    color = colors.textPrimary,
                    modifier = Modifier.semantics { heading() },
                )
            }
            Text(
                stringResource(R.string.welcome_message),
                style = CelanderTheme.typography.bodyMedium,
                color = colors.textSecondary,
                modifier = Modifier.padding(top = Spacing.xs, bottom = Spacing.m),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                AccentButton(stringResource(R.string.welcome_create), onCreate, icon = Icons.Rounded.Add)
                SoftButton(stringResource(R.string.welcome_samples), onLoadSamples)
            }
        }
    }
}
