package com.celander.app.calendar

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import com.ce.kit.ui.glass.GlassKind
import com.ce.kit.ui.glass.glass
import androidx.compose.material3.MaterialTheme
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
import com.ce.kit.ui.AccentButton
import com.ce.kit.ui.theme.CeTheme
import com.ce.kit.ui.SoftButton
import com.ce.kit.ui.blockTouchesBelow

/** First-start card while the calendar is completely empty. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WelcomeCard(onCreate: () -> Unit, onLoadSamples: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Box(modifier.widthIn(max = 560.dp).fillMaxWidth().blockTouchesBelow().glass(RoundedCornerShape(18.dp), GlassKind.Chrome, elevation = 10.dp)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(painterResource(R.drawable.logo_mark), null, modifier = Modifier.size(28.dp))
                Spacer(Modifier.size(8.dp))
                Text(
                    stringResource(R.string.welcome_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.onSurface,
                    modifier = Modifier.semantics { heading() },
                )
            }
            Text(
                stringResource(R.string.welcome_message),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AccentButton(stringResource(R.string.welcome_create), onCreate, icon = Icons.Rounded.Add)
                SoftButton(stringResource(R.string.welcome_samples), onLoadSamples)
            }
        }
    }
}
