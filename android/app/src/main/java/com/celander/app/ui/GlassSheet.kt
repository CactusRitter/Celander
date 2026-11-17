package com.celander.app.ui

import androidx.activity.compose.PredictiveBackHandler
import com.celander.app.ui.glass.GlassKind
import com.celander.app.ui.glass.glass
import androidx.compose.material3.MaterialTheme
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animate
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import com.celander.app.R
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private const val PARTIAL_FRACTION = 0.68f

/** A sheet floats as a card once the window leaves at least this much room on each side of it. */
private val FloatingSideRoom = 24.dp

/** Lets callers close a sheet with its exit animation. */
@Stable
class GlassSheetController {
    internal var closeAction: ((force: Boolean) -> Unit)? = null

    /** Animates the sheet out. Without [force], `confirmDismiss` of the sheet decides. */
    fun close(force: Boolean = true) {
        closeAction?.invoke(force)
    }
}

@Composable
fun rememberGlassSheetController(): GlassSheetController = remember { GlassSheetController() }

/**
 * Bottom sheet in the glass style, drawn inside the screen so the content behind it is blurred.
 * Dismissed by swiping down, tapping the scrim or (predictive) back. [expandable] sheets open at
 * two thirds of the height and can be pulled to full height; others wrap their content.
 *
 * In wide windows (tablets, not phones in landscape) the sheet floats: a card rounded on all
 * sides, clear of the bottom edge and at most [Sizes.maxFloatingSheetHeight] tall. It opens
 * fully right away, since the content stays visible beside it anyway.
 *
 * [confirmDismiss] returning false keeps the sheet open (e.g. to ask about unsaved changes).
 * [onDismissed] runs after the exit animation; the caller then removes the sheet.
 */
@Composable
fun GlassSheet(
    onDismissed: () -> Unit,
    modifier: Modifier = Modifier,
    controller: GlassSheetController = rememberGlassSheetController(),
    expandable: Boolean = false,
    paneTitle: String? = null,
    confirmDismiss: () -> Boolean = { true },
    content: @Composable ColumnScope.() -> Unit,
) {
    val density = LocalDensity.current
    val motion = LocalMotion.current
    val colors = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val currentConfirm by rememberUpdatedState(confirmDismiss)
    val currentOnDismissed by rememberUpdatedState(onDismissed)
    val closeLabel = stringResource(R.string.action_close)
    val expandLabel = stringResource(R.string.action_expand)
    val collapseLabel = stringResource(R.string.action_collapse)
    val handleLabel = stringResource(R.string.sheet_drag_handle)

    BoxWithConstraints(modifier.fillMaxSize()) {
        val floating = maxWidth >= Sizes.maxFormWidth + FloatingSideRoom * 2 && maxHeight >= WindowTokens.mediumHeight
        val containerPx = constraints.maxHeight.toFloat()
        val topGapPx = WindowInsets.statusBars.getTop(density) + with(density) { (if (floating) 24.dp else 8.dp).toPx() }
        // A floating sheet rests above the navigation bar with a margin; it slides out past both.
        val bottomGapPx = if (floating) WindowInsets.navigationBars.getBottom(density) + with(density) { 16.dp.toPx() } else 0f
        val maxSheetPx = (containerPx - topGapPx - bottomGapPx)
            .let { if (floating) minOf(it, with(density) { Sizes.maxFloatingSheetHeight.toPx() }) else it }
            .coerceAtLeast(1f)
        var sheetPx by remember { mutableFloatStateOf(0f) }
        var offset by remember { mutableFloatStateOf(containerPx) }
        var backProgress by remember { mutableFloatStateOf(0f) }
        var backJob by remember { mutableStateOf<Job?>(null) }
        var settleJob by remember { mutableStateOf<Job?>(null) }
        var dismissing by remember { mutableStateOf(false) }
        // Pull past fully open, in px: the sheet stretches elastically instead of moving on.
        var stretch by remember { mutableFloatStateOf(0f) }
        var stretchJob by remember { mutableStateOf<Job?>(null) }
        val stretchMaxPx = with(density) { MotionTokens.SheetStretch.toPx() }
        val flingPx = with(density) { MotionTokens.SheetFlingVelocity.toPx() }

        val expandedOffset = 0f
        val halfOpen = expandable && !floating
        val partialOffset = if (halfOpen) (maxSheetPx - containerPx * PARTIAL_FRACTION).coerceIn(0f, maxSheetPx) else 0f
        val hiddenOffset = if (sheetPx > 0f) sheetPx + bottomGapPx else containerPx

        // With reduced motion the sheet does not slide: it snaps into place and only fades.
        val fade = remember { Animatable(if (motion.reduced) 0f else 1f) }

        fun animateOffset(target: Float, velocity: Float = 0f, then: (() -> Unit)? = null) {
            // A sheet on its way out finishes leaving, so onDismissed runs: nothing may stop it halfway.
            if (dismissing && target != hiddenOffset) return
            settleJob?.cancel()
            settleJob = scope.launch {
                if (motion.reduced) {
                    if (target == hiddenOffset) fade.animateTo(0f, motion.fade()) else offset = target
                } else {
                    animate(offset, target, velocity, motion.sheetSpring()) { value, _ -> offset = value }
                }
                then?.invoke()
            }
        }

        fun dismiss(force: Boolean, velocity: Float = 0f) {
            if (dismissing) return
            if (!force && !currentConfirm()) {
                animateOffset(if (offset < partialOffset / 2) expandedOffset else partialOffset)
                return
            }
            dismissing = true
            focusManager.clearFocus()
            animateOffset(hiddenOffset, velocity) { currentOnDismissed() }
        }

        fun settle(velocity: Float) {
            val anchors = listOfNotNull(expandedOffset, partialOffset.takeIf { halfOpen }, hiddenOffset)
            val target = when {
                velocity > flingPx -> anchors.firstOrNull { it > offset + 1f } ?: hiddenOffset
                velocity < -flingPx -> anchors.lastOrNull { it < offset - 1f } ?: expandedOffset
                else -> anchors.minBy { abs(it - offset) }
            }
            if (target == hiddenOffset) dismiss(force = false, velocity = velocity) else animateOffset(target, velocity)
        }

        controller.closeAction = { force -> dismiss(force) }

        LaunchedEffect(sheetPx > 0f) {
            if (sheetPx > 0f && !dismissing) {
                offset = hiddenOffset
                animateOffset(partialOffset)
                if (motion.reduced) fade.animateTo(1f, motion.fade())
            }
        }

        val imeVisible = WindowInsets.isImeVisible
        LaunchedEffect(imeVisible) {
            if (imeVisible && halfOpen && !dismissing && sheetPx > 0f) animateOffset(expandedOffset)
        }

        // Share of the sheet on screen. Read only while drawing: a moving sheet must not recompose.
        fun visibleFraction(): Float = (if (sheetPx > 0f) ((hiddenOffset - offset) / hiddenOffset).coerceIn(0f, 1f) else 0f) * fade.value

        // Whether a half-open sheet is pulled up past halfway; changes only when that flips.
        val expanded by remember(partialOffset) { derivedStateOf { offset < partialOffset / 2 } }

        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = visibleFraction() }
                .background(colors.scrim)
                .pointerInput(Unit) { detectTapGestures { dismiss(force = false) } }
                .semantics {
                    contentDescription = closeLabel
                    onClick { dismiss(force = false); true }
                },
        )

        // An upward pull past fully open stretches the sheet elastically; a downward one first
        // shrinks the stretch. Returns the part of [delta] used.
        fun stretchBy(delta: Float): Float {
            if (motion.reduced || offset > expandedOffset || (delta >= 0f && stretch == 0f)) return 0f
            stretchJob?.cancel()
            val raw = rubberBandInverse(stretch, stretchMaxPx)
            val pulled = raw - delta
            stretch = rubberBand(pulled.coerceAtLeast(0f), stretchMaxPx)
            return if (pulled < 0f) raw else delta
        }

        fun releaseStretch() {
            if (stretch == 0f) return
            stretchJob = scope.launch {
                animate(stretch, 0f, animationSpec = motion.standard()) { value, _ -> stretch = value }
            }
        }

        // While the sheet slides out, drags and scrolls leave it alone instead of stopping the exit.
        val nestedScroll = remember(halfOpen, partialOffset, hiddenOffset) {
            object : NestedScrollConnection {
                override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                    if (dismissing) return Offset.Zero
                    if (source == NestedScrollSource.UserInput && available.y > 0f && stretch > 0f) return Offset(0f, stretchBy(available.y))
                    if (available.y >= 0f || source != NestedScrollSource.UserInput || offset <= expandedOffset) return Offset.Zero
                    settleJob?.cancel()
                    val target = (offset + available.y).coerceAtLeast(expandedOffset)
                    val consumed = target - offset
                    offset = target
                    return Offset(0f, consumed)
                }

                override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                    if (dismissing) return Offset.Zero
                    if (source == NestedScrollSource.UserInput && available.y < 0f) return Offset(0f, stretchBy(available.y))
                    if (available.y <= 0f || source != NestedScrollSource.UserInput) return Offset.Zero
                    settleJob?.cancel()
                    val target = (offset + available.y).coerceAtMost(hiddenOffset)
                    val used = target - offset
                    offset = target
                    return Offset(0f, used)
                }

                override suspend fun onPreFling(available: Velocity): Velocity {
                    if (dismissing) return Velocity.Zero
                    if (stretch > 0f) {
                        releaseStretch()
                        return available
                    }
                    val atAnchor = abs(offset - expandedOffset) < 1f || (halfOpen && abs(offset - partialOffset) < 1f)
                    if (atAnchor) return Velocity.Zero
                    settle(available.y)
                    return available
                }
            }
        }

        val dragState = rememberDraggableState { delta ->
            if (dismissing) return@rememberDraggableState
            settleJob?.cancel()
            val rest = delta - stretchBy(delta)
            if (rest != 0f) offset = (offset + rest).coerceIn(expandedOffset, hiddenOffset)
        }

        val maxSheetDp = with(density) { maxSheetPx.toDp() }
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .then(
                    if (floating) Modifier.windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom)).padding(bottom = 16.dp)
                    else Modifier,
                )
                .widthIn(max = Sizes.maxFormWidth)
                .fillMaxWidth()
                .then(if (expandable) Modifier.height(maxSheetDp) else Modifier.heightIn(max = maxSheetDp))
                .onSizeChanged { sheetPx = it.height.toFloat() }
                .graphicsLayer {
                    translationY = offset + backProgress * 32.dp.toPx()
                    val scale = 1f - backProgress * 0.05f
                    scaleX = scale
                    scaleY = scale * (1f + if (sheetPx > 0f) stretch / sheetPx else 0f)
                    alpha = fade.value
                    transformOrigin = TransformOrigin(0.5f, 1f)
                }
                .nestedScroll(nestedScroll)
                .draggable(
                    dragState,
                    Orientation.Vertical,
                    onDragStopped = { velocity ->
                        releaseStretch()
                        settle(velocity)
                    },
                )
                .semantics { if (paneTitle != null) this.paneTitle = paneTitle },
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .then(if (expandable) Modifier.fillMaxHeight() else Modifier)
                    .glass(
                        if (floating) RoundedCornerShape(28.dp) else RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                        GlassKind.Menu, elevation = 10.dp,
                    ),
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime).only(WindowInsetsSides.Bottom)),
                ) {
                    DragHandle(
                        description = handleLabel,
                        toggleLabel = when {
                            !halfOpen -> null
                            expanded -> collapseLabel
                            else -> expandLabel
                        },
                        onToggle = { animateOffset(if (expanded) partialOffset else expandedOffset) },
                        actions = buildList {
                            add(CustomAccessibilityAction(closeLabel) { dismiss(force = false); true })
                            if (halfOpen && expanded) add(CustomAccessibilityAction(collapseLabel) { animateOffset(partialOffset); true })
                            if (halfOpen && !expanded) add(CustomAccessibilityAction(expandLabel) { animateOffset(expandedOffset); true })
                        },
                    )
                    content()
                }
            }
        }

        // The sheet eases back out of a back gesture's preview instead of jumping, whether the
        // gesture was cancelled, kept open by confirmDismiss or closes the sheet.
        fun releaseBack() {
            backJob?.cancel()
            if (motion.reduced) {
                backProgress = 0f
                return
            }
            backJob = scope.launch {
                animate(backProgress, 0f, animationSpec = motion.standard()) { value, _ -> backProgress = value }
            }
        }

        PredictiveBackHandler(enabled = !dismissing) { progress ->
            backJob?.cancel()
            try {
                progress.collect { event -> backProgress = event.progress }
                releaseBack()
                dismiss(force = false)
            } catch (e: CancellationException) {
                releaseBack()
                throw e
            }
        }
    }
}

/**
 * The grip at the top of a sheet, a full touch target tall. For TalkBack it carries the sheet's
 * actions; on a sheet with two heights a tap ([toggleLabel] says which way) switches between them.
 */
@Composable
private fun DragHandle(
    description: String,
    toggleLabel: String?,
    onToggle: () -> Unit,
    actions: List<CustomAccessibilityAction>,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(Sizes.touchTarget)
            .then(
                if (toggleLabel != null) {
                    Modifier.clickable(interactionSource = null, indication = null, onClickLabel = toggleLabel, role = Role.Button, onClick = onToggle)
                } else {
                    Modifier
                },
            )
            .semantics(mergeDescendants = true) {
                contentDescription = description
                customActions = actions
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(width = 36.dp, height = 4.dp)
                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f), RoundedCornerShape(2.dp)),
        )
    }
}
