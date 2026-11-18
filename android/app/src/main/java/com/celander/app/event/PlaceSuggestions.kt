package com.celander.app.event

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.celander.app.R
import com.celander.app.core.places.PlaceSuggestion
import com.ce.kit.ui.theme.CeTheme
import com.celander.app.ui.Sizes
import com.celander.app.ui.springClickable

/**
 * Places found for the typed location, under the field. A tap takes the place, which stays editable
 * text; OpenStreetMap is named as the source, as its licence asks.
 */
@Composable
fun PlaceSuggestions(places: List<PlaceSuggestion>, onPick: (PlaceSuggestion) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val useLabel = stringResource(R.string.action_use_place)
    // The field sits low in the editor, so the keyboard would cover the list: it scrolls into view.
    val requester = remember { BringIntoViewRequester() }
    LaunchedEffect(places) { requester.bringIntoView() }
    // Lined up with the text of the field, past its icon.
    Column(Modifier.fillMaxWidth().bringIntoViewRequester(requester).padding(start = 44.dp, bottom = 4.dp)) {
        places.forEach { place ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = Sizes.touchTarget)
                    .springClickable(role = Role.Button, onClickLabel = useLabel, shape = RoundedCornerShape(12.dp)) { onPick(place) }
                    .padding(vertical = 4.dp),
            ) {
                Text(place.name, style = MaterialTheme.typography.bodyMedium, color = colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (place.detail.isNotEmpty()) {
                    Text(place.detail, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Text(stringResource(R.string.editor_places_source), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
    }
}
