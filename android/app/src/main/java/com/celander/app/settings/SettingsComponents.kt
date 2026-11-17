package com.celander.app.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.celander.app.R
import com.celander.app.core.ui.SnackbarMessage
import com.celander.app.designsystem.AppBackground
import com.celander.app.designsystem.CelanderTheme
import com.celander.app.designsystem.GlassIconButton
import com.celander.app.designsystem.GlassSnackbarHost
import com.celander.app.designsystem.GlassSurface
import com.celander.app.designsystem.HairlineDivider
import com.celander.app.designsystem.LocalGlass
import com.celander.app.designsystem.Radii
import com.celander.app.designsystem.SelectableChip
import com.celander.app.designsystem.Sizes
import com.celander.app.designsystem.Spacing
import com.celander.app.designsystem.elastic
import com.celander.app.designsystem.fadingTopEdge
import com.celander.app.designsystem.glassTile
import com.celander.app.designsystem.springClickable
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState

/**
 * Scaffold for settings pages: glass header with back button over a scrolling list. Sheets and
 * dialogs go into [overlay] so they blur the page; while [modalOpen], the page is hidden from
 * accessibility services.
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
    CompositionLocalProvider(LocalGlass provides LocalGlass.current.copy(hazeState = hazeState)) {
        Box(Modifier.fillMaxSize()) {
            val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
            val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
            val hiddenWhileModal = if (modalOpen) Modifier.clearAndSetSemantics { } else Modifier
            Box(Modifier.fillMaxSize().hazeSource(hazeState).then(hiddenWhileModal)) {
                AppBackground()
                LazyColumn(
                    Modifier
                        .fillMaxSize()
                        // Rows fade out under the status bar and the floating header instead of colliding with them.
                        .fadingTopEdge(top + Sizes.topBar)
                        .elastic()
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)),
                    contentPadding = PaddingValues(top = top + Sizes.topBar + Spacing.m, bottom = bottom + Spacing.xxl),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    content = content,
                )
            }
            Row(
                Modifier
                    .align(Alignment.TopCenter)
                    .then(hiddenWhileModal)
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                    // In wide windows the header lines up with the centred column of cards.
                    .widthIn(max = Sizes.maxFormWidth)
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.l, vertical = Spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GlassIconButton(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.action_back), onBack)
                GlassSurface(Modifier.padding(start = Spacing.s), shape = RoundedCornerShape(Radii.pill)) {
                    Text(
                        title,
                        style = CelanderTheme.typography.titleMedium,
                        color = CelanderTheme.colors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .padding(horizontal = Spacing.l, vertical = Spacing.m)
                            .semantics { heading() },
                    )
                }
            }
            GlassSnackbarHost(
                message = snackbar,
                onAction = onSnackbarAction,
                onTimeout = onSnackbarTimeout,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(Spacing.m),
            )
            overlay()
        }
    }
}

/** A titled group of settings rows on a translucent card. */
fun LazyListScope.settingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    item(key = "group:$title") {
        Column(Modifier.widthIn(max = Sizes.maxFormWidth).fillMaxWidth().padding(horizontal = Spacing.l, vertical = Spacing.s)) {
            Text(
                title,
                style = CelanderTheme.typography.labelLarge,
                color = CelanderTheme.colors.textSecondary,
                modifier = Modifier.padding(start = Spacing.s, bottom = Spacing.s).semantics { heading() },
            )
            Column(
                Modifier.fillMaxWidth().glassTile(RoundedCornerShape(Radii.card)).padding(vertical = Spacing.xs),
                content = content,
            )
        }
    }
}

/**
 * A collapsible section: an icon, the title and a one-line summary of its current values. Closed,
 * only this header shows; tapping it reveals the rows. [attention] colors the summary as a problem.
 */
fun LazyListScope.settingsSection(
    key: String,
    icon: ImageVector,
    title: String,
    summary: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    attention: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    item(key = "section:$key") {
        val colors = CelanderTheme.colors
        val motion = CelanderTheme.motion
        val shape = RoundedCornerShape(Radii.card)
        val rotation by animateFloatAsState(if (expanded) 180f else 0f, motion.standard(), label = "chevron")
        val state = stringResource(if (expanded) R.string.settings_section_expanded else R.string.settings_section_collapsed)
        val action = stringResource(if (expanded) R.string.settings_section_collapse else R.string.settings_section_expand)
        Column(
            Modifier
                .widthIn(max = Sizes.maxFormWidth)
                .fillMaxWidth()
                .padding(horizontal = Spacing.l, vertical = Spacing.xs)
                .glassTile(shape),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = SectionHeaderHeight)
                    .springClickable(role = Role.Button, onClickLabel = action, shape = shape, onClick = onToggle)
                    .semantics(mergeDescendants = true) {
                        heading()
                        stateDescription = state
                    }
                    .padding(horizontal = Spacing.l, vertical = Spacing.m),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.m),
            ) {
                Box(Modifier.size(SectionIconSize).background(colors.accentSoft, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(icon, null, tint = colors.accent, modifier = Modifier.size(Sizes.icon))
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(title, style = CelanderTheme.typography.titleMedium, color = colors.textPrimary)
                    Text(
                        summary,
                        style = CelanderTheme.typography.bodySmall,
                        color = if (attention) colors.danger else colors.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Icon(
                    Icons.Rounded.KeyboardArrowDown,
                    null,
                    tint = colors.textTertiary,
                    modifier = Modifier.graphicsLayer { rotationZ = rotation },
                )
            }
            AnimatedVisibility(expanded, enter = motion.expandIn(), exit = motion.shrinkOut()) {
                Column(Modifier.fillMaxWidth().padding(bottom = Spacing.xs)) {
                    HairlineDivider(Modifier.padding(horizontal = Spacing.l))
                    content()
                }
            }
        }
    }
}

private val SectionHeaderHeight = 72.dp
private val SectionIconSize = 40.dp

@Composable
fun SettingsRow(
    title: String,
    modifier: Modifier = Modifier,
    summary: String? = null,
    summaryColor: Color = CelanderTheme.colors.textSecondary,
    onClick: (() -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = Spacing.l, vertical = Spacing.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.m),
    ) {
        leading?.invoke()
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = CelanderTheme.typography.bodyLarge, color = CelanderTheme.colors.textPrimary)
            if (summary != null) Text(summary, style = CelanderTheme.typography.bodySmall, color = summaryColor)
        }
        when {
            trailing != null -> trailing()
            onClick != null -> Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = CelanderTheme.colors.textTertiary)
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
            .padding(horizontal = Spacing.l, vertical = Spacing.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = CelanderTheme.typography.bodyLarge, color = CelanderTheme.colors.textPrimary)
            if (summary != null) Text(summary, style = CelanderTheme.typography.bodySmall, color = CelanderTheme.colors.textSecondary)
        }
        Switch(
            checked = checked,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(checkedTrackColor = CelanderTheme.colors.accent),
        )
    }
}

/** A row with a title and a set of chips, e.g. "System / Light / Dark". */
@Composable
fun <T> SettingsChoiceRow(title: String, options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, summary: String? = null) {
    Column(Modifier.fillMaxWidth().padding(horizontal = Spacing.l, vertical = Spacing.s)) {
        Text(title, style = CelanderTheme.typography.bodyLarge, color = CelanderTheme.colors.textPrimary)
        if (summary != null) {
            Text(summary, style = CelanderTheme.typography.bodySmall, color = CelanderTheme.colors.textSecondary, modifier = Modifier.padding(top = 2.dp))
        }
        androidx.compose.foundation.layout.FlowRow(
            Modifier.fillMaxWidth().padding(top = Spacing.s),
            horizontalArrangement = Arrangement.spacedBy(Spacing.s),
            verticalArrangement = Arrangement.spacedBy(Spacing.s),
        ) {
            options.forEach { (value, label) -> SelectableChip(label, value == selected, onClick = { onSelect(value) }) }
        }
    }
}

@Composable
fun SettingsDivider() {
    HairlineDivider(Modifier.padding(horizontal = Spacing.l))
}
