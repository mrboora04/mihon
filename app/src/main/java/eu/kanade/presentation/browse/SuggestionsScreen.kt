package eu.kanade.presentation.browse

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.domain.suggestions.Suggestion
import eu.kanade.domain.suggestions.SuggestionEngine
import eu.kanade.domain.suggestions.SuggestionEngine.TrendingPeriod
import eu.kanade.domain.suggestions.SuggestionTag
import eu.kanade.presentation.manga.components.MangaCover
import eu.kanade.tachiyomi.ui.browse.suggestions.SuggestionsViewModel
import eu.kanade.tachiyomi.ui.browse.suggestions.SuggestionsViewModel.Section
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.Pill
import tachiyomi.presentation.core.components.ScrollbarLazyColumn
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import kotlin.math.ceil
import kotlin.time.Duration

@Composable
fun SuggestionsScreen(
    state: SuggestionsViewModel.State,
    contentPadding: PaddingValues,
    onSelectSection: (Section) -> Unit,
    onSelectPeriod: (TrendingPeriod) -> Unit,
    onSelectCategory: (String?) -> Unit,
    onClickItem: (Suggestion) -> Unit,
    onLongClickItem: (Suggestion) -> Unit,
    onClickTag: (SuggestionTag) -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    ScrollbarLazyColumn(contentPadding = contentPadding) {
        item(key = "header") {
            Header(
                state = state,
                onSelectSection = onSelectSection,
                onSelectPeriod = onSelectPeriod,
                onSelectCategory = onSelectCategory,
                onClickTag = onClickTag,
            )
        }
        if (state.items.isEmpty()) {
            item(key = "empty") {
                EmptyItem(
                    state = state,
                    onRetry = onRetry,
                    onOpenSettings = onOpenSettings,
                    onOpenForYou = { onSelectSection(Section.FOR_YOU) },
                )
            }
        } else {
            items(items = state.visibleItems, key = { it.seriesId }) { suggestion ->
                SuggestionItem(
                    suggestion = suggestion,
                    selectedTag = state.selectedTag,
                    onClick = { onClickItem(suggestion) },
                    onLongClick = { onLongClickItem(suggestion) },
                )
            }
            item(key = "footer") {
                FooterItem(state = state, onLoadMore = onLoadMore, onRetry = onRetry)
            }
        }
    }
}

@Composable
private fun Header(
    state: SuggestionsViewModel.State,
    onSelectSection: (Section) -> Unit,
    onSelectPeriod: (TrendingPeriod) -> Unit,
    onSelectCategory: (String?) -> Unit,
    onClickTag: (SuggestionTag) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.extraSmall)) {
        ChipRow {
            Section.entries.forEach { section ->
                FilterChip(
                    selected = state.section == section,
                    onClick = { onSelectSection(section) },
                    label = { Text(text = stringResource(section.label)) },
                )
            }
        }
        when (state.section) {
            Section.FOR_YOU -> {}
            Section.TRENDING -> ChipRow {
                TrendingPeriod.entries.forEach { period ->
                    FilterChip(
                        selected = state.trendingPeriod == period,
                        onClick = { onSelectPeriod(period) },
                        label = { Text(text = stringResource(period.label)) },
                    )
                }
            }
            Section.TOP_RATED -> ChipRow {
                FilterChip(
                    selected = state.topRatedCategory == null,
                    onClick = { onSelectCategory(null) },
                    label = { Text(text = stringResource(MR.strings.suggestions_your_genres)) },
                )
                // The user's own reader categories, as hashtags
                state.yourCategories.forEach { category ->
                    FilterChip(
                        selected = state.topRatedCategory == category,
                        onClick = { onSelectCategory(category) },
                        label = { Text(text = SuggestionTag.category(category).label) },
                    )
                }
            }
        }
        HorizontalDivider()
        // MangaUpdates' API policy asks to credit them wherever their data is shown
        Text(
            text = stringResource(MR.strings.suggestions_credit),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = MaterialTheme.padding.medium),
        )
        if (state.tagCounts.size > 1) {
            ChipRow {
                state.tagCounts.forEach { (tag, count) ->
                    FilterChip(
                        selected = state.selectedTag == tag,
                        onClick = { onClickTag(tag) },
                        label = { Text(text = "${tag.label} $count") },
                    )
                }
            }
        }
    }
}

@Composable
private fun ChipRow(content: @Composable () -> Unit) {
    Row(
        modifier = Modifier
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = MaterialTheme.padding.medium),
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
    ) {
        content()
    }
}

@Composable
private fun EmptyItem(
    state: SuggestionsViewModel.State,
    onRetry: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenForYou: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(MaterialTheme.padding.large),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when {
            state.isLoading -> CircularProgressIndicator()
            state.stop != null -> StopMessage(stop = state.stop, onRetry = onRetry)
            else -> {
                val noHistory = state.section == Section.FOR_YOU && state.forYou?.hasSeeds == false
                Text(
                    text = stringResource(
                        when {
                            state.failed -> MR.strings.suggestions_failed
                            state.needsLibrary -> MR.strings.suggestions_needs_library
                            noHistory -> MR.strings.suggestions_no_history
                            else -> MR.strings.suggestions_empty
                        },
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                // Every empty state says what to do next
                when {
                    state.failed -> TextButton(onClick = onRetry) {
                        Text(text = stringResource(MR.strings.action_retry))
                    }
                    state.needsLibrary -> TextButton(onClick = onOpenForYou) {
                        Text(text = stringResource(MR.strings.label_suggestions))
                    }
                    noHistory -> TextButton(onClick = onOpenSettings) {
                        Text(text = stringResource(MR.strings.pref_category_personalised))
                    }
                }
            }
        }
    }
}

/**
 * For you loads the next batch when this scrolls into view, so MangaUpdates is only asked about
 * more titles as the user actually reads down the list.
 */
@Composable
private fun FooterItem(
    state: SuggestionsViewModel.State,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
) {
    val forYou = state.forYou
    val canLoadMore = state.section == Section.FOR_YOU && !state.isLoading && forYou != null &&
        forYou.stop == null && forYou.pendingTitles > 0
    // A short list keeps this footer on screen, so loading on sight would fetch batch after batch;
    // only do that once the user has a real list to scroll through
    val autoLoad = canLoadMore && state.visibleItems.size >= AUTO_LOAD_MIN_ITEMS
    LaunchedEffect(autoLoad) {
        if (autoLoad) onLoadMore()
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(MaterialTheme.padding.medium),
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.medium, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            state.isLoading -> {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Text(
                    text = stringResource(MR.strings.suggestions_loading_more),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            state.stop != null -> StopMessage(stop = state.stop, onRetry = onRetry)
            canLoadMore -> TextButton(onClick = onLoadMore) {
                Text(text = stringResource(MR.strings.suggestions_load_more))
            }
            state.section == Section.FOR_YOU && forYou != null && forYou.checkedTitles > 0 -> Text(
                text = stringResource(MR.strings.suggestions_based_on, forYou.checkedTitles),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun StopMessage(
    stop: SuggestionEngine.Stop,
    onRetry: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = when (stop) {
                is SuggestionEngine.Stop.LimitReached ->
                    stringResource(MR.strings.suggestions_limit_reached, stop.retryIn.minutesUp())
                is SuggestionEngine.Stop.Failed ->
                    stringResource(MR.strings.suggestions_slow_down, stop.retryIn.minutesUp())
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (stop is SuggestionEngine.Stop.Failed) {
            TextButton(onClick = onRetry) {
                Text(text = stringResource(MR.strings.action_retry))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SuggestionItem(
    suggestion: Suggestion,
    selectedTag: SuggestionTag?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = MaterialTheme.padding.medium, vertical = MaterialTheme.padding.small),
        verticalAlignment = Alignment.Top,
    ) {
        MangaCover.Book(
            data = suggestion.coverUrl,
            modifier = Modifier.height(96.dp),
        )
        Column(
            modifier = Modifier
                .padding(start = MaterialTheme.padding.medium)
                .weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = listOfNotNull(suggestion.rank?.let { "#$it" }, suggestion.title).joinToString(" "),
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val details = listOfNotNull(
                suggestion.rating?.let { "★ %.1f".format(it) },
                suggestion.year?.takeIf { it.isNotBlank() },
            )
            if (details.isNotEmpty()) {
                Text(
                    text = details.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (suggestion.because.isNotEmpty()) {
                Text(
                    text = stringResource(MR.strings.suggestions_because, suggestion.because.take(2).joinToString()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // Plain labels: they're too small to be good tap targets, the chips above do the filtering
            FlowRow(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                suggestion.tags.forEach { tag ->
                    val selected = tag == selectedTag
                    Pill(
                        text = tag.label,
                        color = if (selected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.secondaryContainer
                        },
                        contentColor = if (selected) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.onSecondaryContainer
                        },
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }
    }
}

private fun Duration.minutesUp() = ceil(inWholeSeconds / 60.0).toInt().coerceAtLeast(1)

private const val AUTO_LOAD_MIN_ITEMS = 10

private val Section.label
    get() = when (this) {
        Section.FOR_YOU -> MR.strings.label_suggestions
        Section.TRENDING -> MR.strings.suggestions_trending
        Section.TOP_RATED -> MR.strings.suggestions_top_rated
    }

private val TrendingPeriod.label
    get() = when (this) {
        TrendingPeriod.WEEK -> MR.strings.suggestions_this_week
        TrendingPeriod.MONTH -> MR.strings.suggestions_this_month
        TrendingPeriod.THREE_MONTHS -> MR.strings.suggestions_three_months
    }
