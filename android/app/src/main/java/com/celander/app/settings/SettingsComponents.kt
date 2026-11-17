package com.celander.app.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.celander.app.R
import com.celander.app.core.ui.SnackbarMessage
import com.celander.app.ui.ChoiceChip
import com.celander.app.ui.GlassSnackbarHost
import com.celander.app.ui.Sizes
import com.celander.app.ui.elastic
import com.celander.app.ui.glass.GlassBackground
import com.celander.app.ui.glass.GlassKind
import com.celander.app.ui.glass.GlassLayers
import com.celander.app.ui.glass.GlassPill
import com.celander.app.ui.glass.GlassSwitch
import com.celander.app.ui.glass.LocalHazeState
import com.celander.app.ui.glass.PillIcon
import com.celander.app.ui.glass.TopScrim
import com.celander.app.ui.glass.glass
import com.celander.app.ui.glass.onBackdrop
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState

/**
 * Scaffold for settings pages: the title in a glass pill with the back button over a scrolling list of cards. Sheets and
 * dialogs go into [overlay] so they blur the page; while [modalOpen], the page is hidden from accessibility services.
 */
@Composable
fun SettingsScaffold(
    title: String,
    onBack: () -> Unit,
    snackbar: SnackbarMessage? = null,
    onSnackbarAction: (SnackbarMessage) -> Unit = {},
    onSnackbarTimeout: (SnackbarMessage) -> Unit = {},
    modalOpen: Boolean = false,
    overlay: @Composable BoxScope.() -> Unit = {},
    content: LazyListScope.() -> Unit,
) {
    val hazeState = rememberHazeState()
    val listState = rememberLazyListState()
    CompositionLocalProvider(LocalHazeState provides hazeState) {
        Box(Modifier.fillMaxSize()) {
            val hiddenWhileModal = if (modalOpen) Modifier.clearAndSetSemantics { } else Modifier
            GlassBackground(Modifier.hazeSource(hazeState, zIndex = GlassLayers.BACKGROUND).then(hiddenWhileModal)) {
                Box(Modifier.fillMaxSize().hazeSource(hazeState, zIndex = GlassLayers.CONTENT)) {
                    LazyColumn(
                        Modifier
                            .fillMaxSize()
                            .elastic()
                            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)),
                        state = listState,
                        // The first card starts under the pill; the cards' shadows spill into the side padding instead of being cut.
                        contentPadding = PaddingValues(
                            top = WindowInsets.statusBars.asTop() + Sizes.topBar + 20.dp,
                            bottom = WindowInsets.navigationBars.asBottom() + 24.dp,
                        ),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        content = content,
                    )
                }
            }
            TopScrim(
                height = WindowInsets.statusBars.asTop() + Sizes.topBar + 20.dp,
                hazeState = hazeState,
                visible = listState.canScrollBackward,
            )
            GlassPill(
                Modifier
                    .align(Alignment.TopCenter)
                    .then(hiddenWhileModal)
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                    // In wide windows the pill lines up with the centred column of cards.
                    .widthIn(max = Sizes.maxFormWidth)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                PillIcon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back), onBack)
                Text(
                    title,
                    Modifier.weight(1f).padding(horizontal = 8.dp).semantics { heading() },
                    style = MaterialTheme.typography.titleMedium,
                    color = onBackdrop(MaterialTheme.colorScheme.onSurface),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            GlassSnackbarHost(
                message = snackbar,
                onAction = onSnackbarAction,
                onTimeout = onSnackbarTimeout,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(12.dp),
            )
            overlay()
        }
    }
}

/** A titled group of settings rows on a glass card. */
fun LazyListScope.settingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    item(key = "group:$title") {
        Column(Modifier.widthIn(max = Sizes.maxFormWidth).fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.labelLarge,
                color = onBackdrop(MaterialTheme.colorScheme.onSurfaceVariant),
                modifier = Modifier.padding(start = 8.dp, bottom = 8.dp).semantics { heading() },
            )
            Column(
                Modifier.fillMaxWidth().glass(RoundedCornerShape(24.dp), GlassKind.Card).padding(vertical = 4.dp),
                content = content,
            )
        }
    }
}

/** A row of a group: title, an optional line below it and an arrow when it opens something. */
@Composable
fun SettingsRow(
    title: String,
    modifier: Modifier = Modifier,
    summary: String? = null,
    summaryColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    onClick: (() -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        leading?.invoke()
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            if (summary != null) Text(summary, style = MaterialTheme.typography.bodySmall, color = summaryColor)
        }
        when {
            trailing != null -> trailing()
            onClick != null -> Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun SettingsSwitchRow(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit, summary: String? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            if (summary != null) Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        GlassSwitch(checked = checked, onCheckedChange = null)
    }
}

/** A row with a title and a set of chips, e.g. "System / Light / Dark". */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> SettingsChoiceRow(title: String, options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, summary: String? = null) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
        if (summary != null) {
            Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
        }
        FlowRow(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            options.forEach { (value, label) -> ChoiceChip(label, value == selected, onClick = { onSelect(value) }) }
        }
    }
}

@Composable
fun SettingsDivider() {
    HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun WindowInsets.asTop() = with(androidx.compose.ui.platform.LocalDensity.current) { getTop(this).toDp() }

@Composable
private fun WindowInsets.asBottom() = with(androidx.compose.ui.platform.LocalDensity.current) { getBottom(this).toDp() }
