package com.celander.app.ui

import androidx.compose.animation.animateColorAsState
import com.celander.app.ui.theme.LocalSkin
import com.celander.app.ui.theme.LocalGlass
import com.celander.app.ui.glass.Elevation
import com.celander.app.ui.glass.softShadow
import com.celander.app.ui.glass.TaskCheckbox
import com.celander.app.ui.theme.Skin
import com.celander.app.ui.glass.glassTile
import androidx.compose.material3.MaterialTheme
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

/** Plain icon button without a surface, for use inside glass areas. */
@Composable
fun PlainIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
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
    val colors = MaterialTheme.colorScheme
    Row(
        modifier
            .springClickable(enabled = enabled, role = Role.Button, shape = CircleShape, onClick = onClick)
            .heightIn(min = Sizes.touchTarget)
            .background(if (enabled) colors.primary else colors.outline)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = colors.onPrimary, modifier = Modifier.size(Sizes.iconSmall + 2.dp))
            Box(Modifier.width(8.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, color = colors.onPrimary, maxLines = 1)
    }
}

@Composable
fun SoftButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    contentColor: Color = MaterialTheme.colorScheme.primary,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier
            .springClickable(role = Role.Button, shape = CircleShape, onClick = onClick)
            .heightIn(min = Sizes.touchTarget)
            .background(colors.primaryContainer.copy(alpha = if (colors.isDark) 0.9f else 0.85f))
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = contentColor, modifier = Modifier.size(Sizes.iconSmall + 2.dp))
            Box(Modifier.width(8.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, color = contentColor, maxLines = 1)
    }
}

@Composable
fun TextAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary) {
    Box(
        modifier
            .minimumInteractiveComponentSize()
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = color, maxLines = 1)
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
    val colors = MaterialTheme.colorScheme
    val bg by animateColorAsState(if (selected) colors.primaryContainer else colors.surface.copy(alpha = 0.5f), LocalMotion.current.fade(), label = "chipBg")
    Row(
        modifier
            .heightIn(min = 36.dp)
            .minimumInteractiveComponentSize()
            .clip(CircleShape)
            .background(bg)
            .border(Sizes.hairline, if (selected) colors.primary.copy(alpha = 0.5f) else colors.outline, CircleShape)
            .selectable(selected = selected, role = role, onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Box(Modifier.width(4.dp + 2.dp))
        }
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) colors.onSurface else colors.onSurfaceVariant,
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
    val colors = MaterialTheme.colorScheme
    val motion = LocalMotion.current
    val position by animateFloatAsState(selectedIndex.toFloat(), motion.standard(), label = "segment")
    BoxWithConstraints(
        modifier
            .height(Sizes.touchTarget)
            .background(colors.onSurface.copy(alpha = 0.08f), CircleShape),
    ) {
        val segment = maxWidth / options.size
        // Taps count over the control's full height; the pill drawn inside keeps its inset.
        Box(
            Modifier
                .offset { IntOffset((segment * position).roundToPx(), 0) }
                .width(segment)
                .fillMaxHeight()
                .padding(4.dp)
                .softShadow(CircleShape, LocalGlass.current.shadow, Elevation.lifted)
                .background(colors.surface, CircleShape)
                .border(Sizes.hairline, colors.outlineVariant, CircleShape),
        )
        Row(Modifier.fillMaxSize().selectableGroup()) {
            options.forEachIndexed { index, label ->
                val selected = index == selectedIndex
                val text by animateColorAsState(if (selected) colors.onSurface else colors.onSurfaceVariant, motion.fade(), label = "segmentText")
                val interactions = remember { MutableInteractionSource() }
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .selectable(selected = selected, interactionSource = interactions, indication = null, role = Role.Tab) { onSelect(index) }
                        .padding(4.dp)
                        .clip(CircleShape)
                        .indication(interactions, ripple()),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelLarge,
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
    Row(modifier.glassTile(CircleShape), verticalAlignment = Alignment.CenterVertically) {
        PlainIconButton(Icons.Rounded.Remove, stringResource(R.string.action_decrease), { onValueChange(value - 1) }, enabled = value > range.first)
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
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
    val colors = MaterialTheme.colorScheme
    Row(
        modifier
            .heightIn(min = 36.dp)
            .clip(CircleShape)
            .background(colors.primaryContainer)
            .padding(start = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = colors.onSurface, maxLines = 1)
        Box(
            Modifier
                .size(Sizes.touchTarget)
                .clip(CircleShape)
                .clickable(role = Role.Button, onClick = onRemove)
                .semantics { contentDescription = removeLabel },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Close, null, tint = colors.onSurfaceVariant, modifier = Modifier.size(Sizes.iconSmall))
        }
    }
}

/** Round checkbox used for tasks everywhere (lists, timeline, agenda), in the colour of its calendar; Retro 98 draws its own. */
@Composable
fun CalendarCheckbox(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    color: Color,
    contentDescription: String,
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = 22.dp,
) {
    val colors = MaterialTheme.colorScheme
    val haptics = rememberHaptics()
    if (LocalSkin.current.skin == Skin.RETRO) {
        TaskCheckbox(
            checked, onCheckedChange,
            modifier.minimumInteractiveComponentSize().semantics { this.contentDescription = contentDescription },
            default = {},
        )
        return
    }
    val fill by animateColorAsState(if (checked) color else Color.Transparent, LocalMotion.current.fade(), label = "check")
    val checkScale by animateFloatAsState(if (checked) 1f else 0.4f, LocalMotion.current.bouncy(), label = "checkScale")
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
    val colors = MaterialTheme.colorScheme
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
                .then(if (selected) Modifier.border(2.5.dp, colors.onSurface, CircleShape) else Modifier),
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
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(horizontal = 20.dp, vertical = 8.dp),
    )
}

@Composable
fun HairlineDivider(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(Sizes.hairline).background(MaterialTheme.colorScheme.outlineVariant))
}

/** A row with a leading icon slot, used in sheets and settings. */
@Composable
fun IconRow(
    icon: ImageVector?,
    modifier: Modifier = Modifier,
    iconTint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
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
