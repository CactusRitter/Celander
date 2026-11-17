package com.celander.app.task

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.celander.app.R
import com.celander.app.calendar.components.TaskRow
import com.celander.app.core.model.TaskItem
import com.celander.app.core.ui.Formats
import com.celander.app.designsystem.CelanderTheme
import com.celander.app.designsystem.EmptyState
import com.celander.app.designsystem.PlainIconButton
import com.celander.app.designsystem.Radii
import com.celander.app.designsystem.Sizes
import com.celander.app.designsystem.SkeletonRows
import com.celander.app.designsystem.Spacing
import com.celander.app.designsystem.centeringPadding
import com.celander.app.designsystem.elastic
import com.celander.app.designsystem.fadingTopEdge
import com.celander.app.designsystem.glassTile
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/** Task overview: quick add on top, then overdue, today, upcoming, without date and (folded) done. */
@Composable
fun TaskListView(
    items: List<TaskItem>?,
    today: LocalDate,
    now: Instant,
    zone: ZoneId,
    formats: Formats,
    contentPadding: PaddingValues,
    onQuickAdd: (String) -> Unit,
    onNewTask: () -> Unit,
    onOpenTask: (TaskItem) -> Unit,
    onToggleTask: (TaskItem, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var doneExpanded by rememberSaveable { mutableStateOf(false) }
    val sections = remember(items, today) { items?.let { TaskGroups.build(it, today) } }
    val nowZoned = now.atZone(zone)
    // With the keyboard open (quick add) the list must still scroll its last entries into view.
    val bottom = maxOf(contentPadding.calculateBottomPadding(), WindowInsets.ime.asPaddingValues().calculateBottomPadding())

    BoxWithConstraints(modifier.fillMaxSize()) {
        // Rows stay at a readable width in wide windows; the whole width still scrolls.
        val side = centeringPadding(maxWidth, Sizes.maxListWidth, Spacing.m)
        val skeletonSide = centeringPadding(maxWidth, Sizes.maxListWidth, Spacing.xl)
        Column(Modifier.fillMaxSize().padding(top = contentPadding.calculateTopPadding())) {
            QuickAddField(onQuickAdd, Modifier.padding(horizontal = side, vertical = Spacing.xs))
            when {
                sections == null -> SkeletonRows(5, Modifier.padding(horizontal = skeletonSide, vertical = Spacing.l))
                sections.isEmpty() -> EmptyState(
                    icon = Icons.Outlined.TaskAlt,
                    title = stringResource(R.string.tasks_empty_title),
                    message = stringResource(R.string.tasks_empty_message),
                    primaryAction = stringResource(R.string.editor_new_task) to onNewTask,
                    modifier = Modifier.padding(top = Spacing.xl),
                )
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize().fadingTopEdge(Spacing.m).elastic(),
                    contentPadding = PaddingValues(
                        start = side,
                        end = side,
                        top = Spacing.s,
                        bottom = bottom + Spacing.l,
                    ),
                ) {
                    if (sections.all { it.group == TaskGroup.DONE }) {
                        item(key = "all_done") {
                            EmptyState(
                                icon = Icons.Outlined.TaskAlt,
                                title = stringResource(R.string.tasks_all_done_title),
                                message = stringResource(R.string.tasks_all_done_message),
                                modifier = Modifier.animateItem(),
                            )
                        }
                    }
                    sections.forEach { section ->
                        if (section.group == TaskGroup.DONE) {
                            item(key = "header:done") {
                                DoneHeader(section.items.size, doneExpanded, { doneExpanded = it }, Modifier.animateItem())
                            }
                            if (doneExpanded) taskRows(section, today, formats, nowZoned, onOpenTask, onToggleTask)
                        } else {
                            item(key = "header:${section.group}") {
                                SectionHeader(section.group, section.items.size, Modifier.animateItem())
                            }
                            taskRows(section, today, formats, nowZoned, onOpenTask, onToggleTask)
                        }
                    }
                }
            }
        }
    }
}

private fun LazyListScope.taskRows(
    section: TaskSection,
    today: LocalDate,
    formats: Formats,
    now: ZonedDateTime,
    onOpenTask: (TaskItem) -> Unit,
    onToggleTask: (TaskItem, Boolean) -> Unit,
) {
    items(section.items, key = { "task:${it.task.id}" }) { item ->
        TaskRow(
            item = item,
            formats = formats,
            overdue = item.task.isOverdue(now),
            onToggle = { onToggleTask(item, it) },
            onClick = { onOpenTask(item) },
            showDate = item.task.dueDate != null && item.task.dueDate != today,
            modifier = Modifier.animateItem(),
        )
    }
}

@Composable
private fun SectionHeader(group: TaskGroup, count: Int, modifier: Modifier = Modifier) {
    val colors = CelanderTheme.colors
    Row(
        modifier.fillMaxWidth().padding(start = Spacing.s, top = Spacing.l, bottom = Spacing.xs).semantics(mergeDescendants = true) { heading() },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(group.label),
            style = CelanderTheme.typography.titleSmall,
            // Overdue is highlighted, but only in the header, not as a red list.
            color = if (group == TaskGroup.OVERDUE) colors.danger else colors.textPrimary,
        )
        Text(
            "  $count",
            style = CelanderTheme.typography.labelLarge,
            color = colors.textTertiary,
        )
    }
}

@Composable
private fun DoneHeader(count: Int, expanded: Boolean, onExpandedChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val colors = CelanderTheme.colors
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, CelanderTheme.motion.standard(), label = "doneChevron")
    Row(
        modifier
            .fillMaxWidth()
            .padding(top = Spacing.l)
            .heightIn(min = Sizes.touchTarget)
            .toggleable(value = expanded, role = Role.Button, onValueChange = onExpandedChange)
            .semantics(mergeDescendants = true) { heading() }
            .padding(start = Spacing.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(TaskGroup.DONE.label), style = CelanderTheme.typography.titleSmall, color = colors.textSecondary)
        Text("  $count", style = CelanderTheme.typography.labelLarge, color = colors.textTertiary, modifier = Modifier.weight(1f))
        Box(Modifier.size(Sizes.touchTarget), contentAlignment = Alignment.Center) {
            Icon(
                Icons.Rounded.ExpandMore,
                contentDescription = stringResource(if (expanded) R.string.tasks_hide_done else R.string.tasks_show_done),
                tint = colors.textSecondary,
                modifier = Modifier.rotate(rotation),
            )
        }
    }
}

@Composable
private fun QuickAddField(onAdd: (String) -> Unit, modifier: Modifier = Modifier) {
    val colors = CelanderTheme.colors
    val motion = CelanderTheme.motion
    var text by rememberSaveable { mutableStateOf("") }
    val placeholder = stringResource(R.string.tasks_quick_add)
    val submit = {
        val title = text.trim()
        if (title.isNotEmpty()) {
            onAdd(title)
            text = ""
        }
    }
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .glassTile(RoundedCornerShape(Radii.card))
            .padding(start = Spacing.m, end = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Add, null, tint = colors.accent, modifier = Modifier.size(Sizes.icon))
        Box(Modifier.width(Spacing.m))
        BasicTextField(
            value = text,
            onValueChange = { text = it },
            singleLine = true,
            textStyle = CelanderTheme.typography.bodyLarge.copy(color = colors.textPrimary),
            cursorBrush = SolidColor(colors.accent),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
            // Keeps the keyboard open so several tasks can be noted in a row.
            keyboardActions = KeyboardActions(onDone = { submit() }),
            modifier = Modifier.weight(1f).semantics { contentDescription = placeholder },
            decorationBox = { inner ->
                Box {
                    if (text.isEmpty()) Text(placeholder, style = CelanderTheme.typography.bodyLarge, color = colors.textTertiary)
                    inner()
                }
            },
        )
        AnimatedVisibility(
            visible = text.isNotBlank(),
            enter = fadeIn(motion.fade()) + scaleIn(motion.standard(), initialScale = 0.6f),
            exit = fadeOut(motion.fade()) + scaleOut(motion.standard(), targetScale = 0.6f),
        ) {
            PlainIconButton(Icons.Rounded.ArrowUpward, stringResource(R.string.tasks_quick_add_send), { submit() }, tint = colors.accent)
        }
    }
}

private val TaskGroup.label: Int
    get() = when (this) {
        TaskGroup.OVERDUE -> R.string.tasks_group_overdue
        TaskGroup.TODAY -> R.string.tasks_group_today
        TaskGroup.UPCOMING -> R.string.tasks_group_upcoming
        TaskGroup.NO_DATE -> R.string.tasks_group_no_date
        TaskGroup.DONE -> R.string.tasks_group_done
    }
