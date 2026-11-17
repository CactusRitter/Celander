package com.celander.app.designsystem

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.celander.app.R

/** Round glass button for floating actions (today, search, settings). */
@Composable
fun GlassIconButton(
    icon: ImageVector?,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = CelanderTheme.colors.textPrimary,
    content: (@Composable () -> Unit)? = null,
) {
    val source = remember { MutableInteractionSource() }
    GlassSurface(modifier.pressScale(source).size(Sizes.touchTarget), shape = CircleShape) {
        Box(
            Modifier
                .size(Sizes.touchTarget)
                .clickable(interactionSource = source, indication = ripple(), role = Role.Button, onClick = onClick)
                .semantics { this.contentDescription = contentDescription },
            contentAlignment = Alignment.Center,
        ) {
            when {
                content != null -> content()
                icon != null -> Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(Sizes.icon))
            }
        }
    }
}

/** Plain icon button without a surface, for use inside glass areas. */
@Composable
fun PlainIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = CelanderTheme.colors.textSecondary,
    enabled: Boolean = true,
) {
    Box(
        modifier
            .size(Sizes.touchTarget)
            .clip(CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .then(if (contentDescription != null) Modifier.semantics { this.contentDescription = contentDescription } else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = tint.copy(alpha = if (enabled) 1f else 0.4f), modifier = Modifier.size(Sizes.icon))
    }
}

@Composable
fun AccentButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    val colors = CelanderTheme.colors
    Row(
        modifier
            .springClickable(enabled = enabled, role = Role.Button, shape = PillShape, onClick = onClick)
            .heightIn(min = Sizes.touchTarget)
            .background(if (enabled) colors.accent else colors.outline)
            .padding(horizontal = Spacing.xl),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = colors.onAccent, modifier = Modifier.size(Sizes.iconSmall + 2.dp))
            Box(Modifier.width(Spacing.s))
        }
        Text(text, style = CelanderTheme.typography.labelLarge, color = colors.onAccent, maxLines = 1)
    }
}

@Composable
fun SoftButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    contentColor: Color = CelanderTheme.colors.accent,
) {
    val colors = CelanderTheme.colors
    Row(
        modifier
            .springClickable(role = Role.Button, shape = PillShape, onClick = onClick)
            .heightIn(min = Sizes.touchTarget)
            .background(colors.accentSoft.copy(alpha = if (colors.isDark) 0.9f else 0.85f))
            .padding(horizontal = Spacing.l),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = contentColor, modifier = Modifier.size(Sizes.iconSmall + 2.dp))
            Box(Modifier.width(Spacing.s))
        }
        Text(text, style = CelanderTheme.typography.labelLarge, color = contentColor, maxLines = 1)
    }
}

@Composable
fun TextAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, color: Color = CelanderTheme.colors.accent) {
    Box(
        modifier
            .minimumInteractiveComponentSize()
            .clip(PillShape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = Spacing.m, vertical = Spacing.s),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = CelanderTheme.typography.labelLarge, color = color, maxLines = 1)
    }
}

/** Selectable chip for choices ([Role.RadioButton]) and multi-select filters ([Role.Checkbox]). */
@Composable
fun SelectableChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
    role: Role = Role.RadioButton,
) {
    val colors = CelanderTheme.colors
    val bg by animateColorAsState(if (selected) colors.accentSoft else colors.tile.copy(alpha = 0.5f), CelanderTheme.motion.fade(), label = "chipBg")
    Row(
        modifier
            .heightIn(min = 36.dp)
            .minimumInteractiveComponentSize()
            .clip(PillShape)
            .background(bg)
            .border(Sizes.hairline, if (selected) colors.accent.copy(alpha = 0.5f) else colors.outline, PillShape)
            .selectable(selected = selected, role = role, onClick = onClick)
            .padding(horizontal = Spacing.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Box(Modifier.width(Spacing.xs + 2.dp))
        }
        Text(
            text,
            style = CelanderTheme.typography.labelLarge,
            color = if (selected) colors.textPrimary else colors.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Two or more equally sized segments, e.g. "Event / Task". The selected one is a raised pill that
 * slides over a recessed track, so the choice reads at a glance in light and dark.
 */
@Composable
fun SegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CelanderTheme.colors
    val motion = CelanderTheme.motion
    val position by animateFloatAsState(selectedIndex.toFloat(), motion.standard(), label = "segment")
    BoxWithConstraints(
        modifier
            .height(Sizes.touchTarget)
            .background(colors.segmentTrack, PillShape),
    ) {
        val segment = maxWidth / options.size
        // Taps count over the control's full height; the pill drawn inside keeps its inset.
        Box(
            Modifier
                .offset { IntOffset((segment * position).roundToPx(), 0) }
                .width(segment)
                .fillMaxHeight()
                .padding(Spacing.xs)
                .softShadow(PillShape, colors.shadow, Elevation.lifted)
                .background(colors.segmentThumb, PillShape)
                .border(Sizes.hairline, colors.segmentThumbEdge, PillShape),
        )
        Row(Modifier.fillMaxSize().selectableGroup()) {
            options.forEachIndexed { index, label ->
                val selected = index == selectedIndex
                val text by animateColorAsState(if (selected) colors.textPrimary else colors.textSecondary, motion.fade(), label = "segmentText")
                val interactions = remember { MutableInteractionSource() }
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .selectable(selected = selected, interactionSource = interactions, indication = null, role = Role.Tab) { onSelect(index) }
                        .padding(Spacing.xs)
                        .clip(PillShape)
                        .indication(interactions, ripple()),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label,
                        style = CelanderTheme.typography.labelLarge,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                        color = text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** "− value +" for small counts such as a repeat interval; [label] describes the current value. */
@Composable
fun Stepper(value: Int, onValueChange: (Int) -> Unit, range: IntRange, label: String, modifier: Modifier = Modifier) {
    Row(modifier.glassTile(PillShape), verticalAlignment = Alignment.CenterVertically) {
        PlainIconButton(Icons.Rounded.Remove, stringResource(R.string.action_decrease), { onValueChange(value - 1) }, enabled = value > range.first)
        Text(
            label,
            style = CelanderTheme.typography.labelLarge,
            color = CelanderTheme.colors.textPrimary,
            textAlign = TextAlign.Center,
            maxLines = 1,
            modifier = Modifier.widthIn(min = 24.dp).semantics { liveRegion = LiveRegionMode.Polite },
        )
        PlainIconButton(Icons.Rounded.Add, stringResource(R.string.action_increase), { onValueChange(value + 1) }, enabled = value < range.last)
    }
}

/** Chip for a chosen value that can be removed again, e.g. a reminder. */
@Composable
fun RemovableChip(text: String, removeLabel: String, onRemove: () -> Unit, modifier: Modifier = Modifier) {
    val colors = CelanderTheme.colors
    Row(
        modifier
            .heightIn(min = 36.dp)
            .clip(PillShape)
            .background(colors.accentSoft)
            .padding(start = Spacing.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = CelanderTheme.typography.labelLarge, color = colors.textPrimary, maxLines = 1)
        Box(
            Modifier
                .size(Sizes.touchTarget)
                .clip(CircleShape)
                .clickable(role = Role.Button, onClick = onRemove)
                .semantics { contentDescription = removeLabel },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Close, null, tint = colors.textSecondary, modifier = Modifier.size(Sizes.iconSmall))
        }
    }
}

/** Round checkbox used for tasks everywhere (lists, timeline, agenda). */
@Composable
fun TaskCheckbox(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    color: Color,
    contentDescription: String,
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = 22.dp,
) {
    val colors = CelanderTheme.colors
    val haptics = rememberHaptics()
    val fill by animateColorAsState(if (checked) color else Color.Transparent, CelanderTheme.motion.fade(), label = "check")
    val checkScale by animateFloatAsState(if (checked) 1f else 0.4f, CelanderTheme.motion.bouncy(), label = "checkScale")
    Box(
        modifier
            .minimumInteractiveComponentSize()
            .toggleable(value = checked, role = Role.Checkbox) {
                if (it) haptics.confirm()
                onCheckedChange(it)
            }
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(size)
                .clip(CircleShape)
                .background(fill)
                .border(1.6.dp, color, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) {
                Icon(
                    Icons.Rounded.Check,
                    null,
                    tint = if (colors.isDark) colors.background else Color.White,
                    modifier = Modifier.size(size * 0.7f).scale(checkScale),
                )
            }
        }
    }
}

/** Color swatch for calendar color selection; the name is announced for accessibility. */
@Composable
fun ColorSwatch(color: Color, selected: Boolean, label: String, onClick: () -> Unit, modifier: Modifier = Modifier, checkTint: Color = Color.White) {
    val colors = CelanderTheme.colors
    Box(
        modifier
            .size(Sizes.touchTarget)
            .clip(CircleShape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(color)
                .then(if (selected) Modifier.border(2.5.dp, colors.textPrimary, CircleShape) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) Icon(Icons.Rounded.Check, null, tint = checkTint, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        style = CelanderTheme.typography.labelMedium,
        color = CelanderTheme.colors.textSecondary,
        modifier = modifier.padding(horizontal = Spacing.xl, vertical = Spacing.s),
    )
}

@Composable
fun HairlineDivider(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(Sizes.hairline).background(CelanderTheme.colors.divider))
}

/** A row with a leading icon slot, used in sheets and settings. */
@Composable
fun IconRow(
    icon: ImageVector?,
    modifier: Modifier = Modifier,
    iconTint: Color = CelanderTheme.colors.textSecondary,
    content: @Composable RowScope.() -> Unit,
) {
    Row(modifier.fillMaxWidth().heightIn(min = Sizes.touchTarget), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(44.dp), contentAlignment = Alignment.CenterStart) {
            if (icon != null) Icon(icon, null, tint = iconTint, modifier = Modifier.size(Sizes.icon))
        }
        content()
    }
}

fun Modifier.dimmed(dim: Boolean): Modifier = if (dim) alpha(0.55f) else this
