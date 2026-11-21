package com.celander.app.event

import androidx.compose.foundation.layout.Box
import com.ce.kit.ui.glass.glassTile
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.ce.kit.ui.theme.CeTheme
import com.ce.kit.ui.SelectableChip
import com.celander.app.ui.Sizes
import com.ce.kit.ui.springClickable

/** Borderless text field with a leading icon, used for location, description and notes. */
@Composable
fun EditorTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    icon: ImageVector?,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    textStyle: TextStyle = MaterialTheme.typography.bodyLarge,
    imeAction: ImeAction = if (singleLine) ImeAction.Next else ImeAction.Default,
    trailing: (@Composable () -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier.fillMaxWidth().heightIn(min = Sizes.touchTarget).padding(vertical = 4.dp),
        verticalAlignment = if (singleLine) Alignment.CenterVertically else Alignment.Top,
    ) {
        Box(Modifier.width(44.dp).padding(top = if (singleLine) 0.dp else 2.dp)) {
            if (icon != null) Icon(icon, null, tint = colors.onSurfaceVariant, modifier = Modifier.size(Sizes.icon))
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = singleLine,
            minLines = if (singleLine) 1 else 3,
            textStyle = textStyle.copy(color = colors.onSurface),
            cursorBrush = SolidColor(colors.primary),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = imeAction),
            modifier = Modifier.weight(1f).semantics { contentDescription = placeholder },
            decorationBox = { inner ->
                Box {
                    if (value.isEmpty()) Text(placeholder, style = textStyle, color = colors.onSurfaceVariant)
                    inner()
                }
            },
        )
        trailing?.invoke()
    }
}

/** A tappable value chip (date, time, calendar) that opens an inline picker. */
@Composable
fun ValueChip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, label: String? = null) {
    SelectableChip(
        text = text,
        selected = selected,
        onClick = onClick,
        modifier = modifier.then(if (label != null) Modifier.semantics { contentDescription = "$label, $text" } else Modifier),
        role = Role.Button,
    )
}

/** Label + content row with the standard leading icon column; without a label the content fills the row. */
@Composable
fun EditorRow(icon: ImageVector?, label: String?, modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(modifier.fillMaxWidth().heightIn(min = Sizes.touchTarget).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(44.dp)) {
            if (icon != null) Icon(icon, null, tint = colors.onSurfaceVariant, modifier = Modifier.size(Sizes.icon))
        }
        if (label != null) {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = colors.onSurface, modifier = Modifier.weight(1f))
        }
        content()
    }
}

/** Groups related editor rows on a translucent card. */
@Composable
fun EditorSection(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .glassTile(RoundedCornerShape(18.dp))
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) { content() }
}
