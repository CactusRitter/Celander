package com.celander.app.task

import androidx.compose.animation.AnimatedVisibility
import com.ce.kit.ui.glass.fadingTopEdge
import com.ce.kit.ui.glass.glassTile
import androidx.compose.material3.MaterialTheme
import com.celander.app.ui.LocalMotion
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
import com.ce.kit.ui.theme.CeTheme
import com.celander.app.ui.EmptyState
import com.celander.app.ui.PlainIconButton
import com.celander.app.ui.Sizes
import com.celander.app.ui.SkeletonRows
import com.celander.app.ui.centeringPadding
import com.celander.app.ui.elastic
import com.ce.kit.ui.glass.fadingTopEdge
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
        val side = centeringPadding(maxWidth, Sizes.maxListWidth, 12.dp)
        val skeletonSide = centeringPadding(maxWidth, Sizes.maxListWidth, 20.dp)
        Column(Modifier.fillMaxSize().padding(top = contentPadding.calculateTopPadding())) {
            QuickAddField(onQuickAdd, Modifier.padding(horizontal = side, vertical = 4.dp))
            when {
                sections == null -> SkeletonRows(5, Modifier.padding(horizontal = skeletonSide, vertical = 16.dp))
                sections.isEmpty() -> EmptyState(
                    icon = Icons.Outlined.TaskAlt,
                    title = stringResource(R.string.tasks_empty_title),
                    message = stringResource(R.string.tasks_empty_message),
                    primaryAction = stringResource(R.string.editor_new_task) to onNewTask,
                    modifier = Modifier.padding(top = 20.dp),
                )
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize().fadingTopEdge(12.dp).elastic(),
                    contentPadding = PaddingValues(
                        start = side,
                        end = side,
                        top = 8.dp,
                        bottom = bottom + 16.dp,
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
    val colors = MaterialTheme.colorScheme
    Row(
        modifier.fillMaxWidth().padding(start = 8.dp, top = 16.dp, bottom = 4.dp).semantics(mergeDescendants = true) { heading() },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(group.label),
            style = MaterialTheme.typography.titleSmall,
            // Overdue is highlighted, but only in the header, not as a red list.
            color = if (group == TaskGroup.OVERDUE) colors.error else colors.onSurface,
        )
        Text(
            "  $count",
            style = MaterialTheme.typography.labelLarge,
            color = colors.onSurfaceVariant,
        )
    }
}

@Composable
private fun DoneHeader(count: Int, expanded: Boolean, onExpandedChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, LocalMotion.current.standard(), label = "doneChevron")
    Row(
        modifier
            .fillMaxWidth()
            .padding(top = 16.dp)
            .heightIn(min = Sizes.touchTarget)
            .toggleable(value = expanded, role = Role.Button, onValueChange = onExpandedChange)
            .semantics(mergeDescendants = true) { heading() }
            .padding(start = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(TaskGroup.DONE.label), style = MaterialTheme.typography.titleSmall, color = colors.onSurfaceVariant)
        Text("  $count", style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant, modifier = Modifier.weight(1f))
        Box(Modifier.size(Sizes.touchTarget), contentAlignment = Alignment.Center) {
            Icon(
                Icons.Rounded.ExpandMore,
                contentDescription = stringResource(if (expanded) R.string.tasks_hide_done else R.string.tasks_show_done),
                tint = colors.onSurfaceVariant,
                modifier = Modifier.rotate(rotation),
            )
        }
    }
}

@Composable
private fun QuickAddField(onAdd: (String) -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val motion = LocalMotion.current
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
            .glassTile(RoundedCornerShape(18.dp))
            .padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Add, null, tint = colors.primary, modifier = Modifier.size(Sizes.icon))
        Box(Modifier.width(12.dp))
        BasicTextField(
            value = text,
            onValueChange = { text = it },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface),
            cursorBrush = SolidColor(colors.primary),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
            // Keeps the keyboard open so several tasks can be noted in a row.
            keyboardActions = KeyboardActions(onDone = { submit() }),
            modifier = Modifier.weight(1f).semantics { contentDescription = placeholder },
            decorationBox = { inner ->
                Box {
                    if (text.isEmpty()) Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant)
                    inner()
                }
            },
        )
        AnimatedVisibility(
            visible = text.isNotBlank(),
            enter = fadeIn(motion.fade()) + scaleIn(motion.standard(), initialScale = 0.6f),
            exit = fadeOut(motion.fade()) + scaleOut(motion.standard(), targetScale = 0.6f),
        ) {
            PlainIconButton(Icons.Rounded.ArrowUpward, stringResource(R.string.tasks_quick_add_send), { submit() }, tint = colors.primary)
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
