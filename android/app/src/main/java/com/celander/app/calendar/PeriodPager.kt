package com.celander.app.calendar

import androidx.compose.foundation.pager.HorizontalPager
import com.celander.app.ui.LocalMotion
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import com.celander.app.ui.theme.CelanderTheme
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.isActive
import kotlin.math.abs

private const val PAGE_COUNT = 20_000
private const val START_PAGE = PAGE_COUNT / 2

/** How far from its anchor the pager goes; an index further away gets a pager anchored there. */
private const val MAX_ANCHOR_DISTANCE = START_PAGE / 2

/** The index shown on the pager's middle page. A plain holder: only the pager's key reads it. */
private class PagerAnchor(var index: Long)

private val PagerAnchorSaver = Saver<PagerAnchor, Long>(save = { it.index }, restore = { PagerAnchor(it) })

/**
 * Horizontal pager over periods identified by a running index (year, month index or epoch day).
 * It follows [index] (e.g. after "today") and reports settled swipes through [onIndexChange].
 */
@Composable
fun PeriodPager(
    index: Long,
    onIndexChange: (Long) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (Long) -> Unit,
) {
    // The pager has a fixed number of pages around its anchor; a jump beyond them (decades in the
    // day view) starts a pager around the new index instead of stopping at the last page.
    val anchor = rememberSaveable(saver = PagerAnchorSaver) { PagerAnchor(index) }
    if (abs(index - anchor.index) > MAX_ANCHOR_DISTANCE) anchor.index = index
    val currentIndex by rememberUpdatedState(index)
    val currentOnChange by rememberUpdatedState(onIndexChange)
    val reduced = LocalMotion.current.reduced

    key(anchor.index) {
        val base = anchor.index
        val pagerState = rememberPagerState(initialPage = START_PAGE + (index - base).toInt()) { PAGE_COUNT }
        // The index the pager last moved to by itself. While it still owes the move to a newer one
        // (a second Ctrl+→ during the first), the page it rests on in between is not a swipe.
        var followed by remember { mutableLongStateOf(index) }

        LaunchedEffect(pagerState) {
            snapshotFlow { if (pagerState.isScrollInProgress) null else pagerState.currentPage }
                .filterNotNull()
                .collect { page ->
                    val settled = base + (page - START_PAGE)
                    if (followed == currentIndex && settled != currentIndex) currentOnChange(settled)
                }
        }
        LaunchedEffect(index) {
            val target = START_PAGE + (index - base).toInt()
            try {
                // Also when an earlier move was cut short between two pages.
                if (target != pagerState.currentPage || pagerState.currentPageOffsetFraction != 0f) {
                    if (reduced || abs(target - pagerState.currentPage) > 2) pagerState.scrollToPage(target) else pagerState.animateScrollToPage(target)
                }
            } finally {
                // Not when a newer index took over: the pager is still on its way.
                if (isActive) followed = index
            }
        }

        HorizontalPager(
            state = pagerState,
            modifier = modifier,
            beyondViewportPageCount = 1,
            key = { base + (it - START_PAGE) },
        ) { page ->
            content(base + (page - START_PAGE))
        }
    }
}
