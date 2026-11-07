package eu.kanade.presentation.browse

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.domain.suggestions.Suggestion
import eu.kanade.domain.suggestions.SuggestionEngine
import eu.kanade.domain.suggestions.SuggestionEngine.TrendingPeriod
import eu.kanade.domain.suggestions.SuggestionTag
import eu.kanade.presentation.components.DropdownMenu
import eu.kanade.presentation.components.RadioMenuItem
import eu.kanade.presentation.manga.components.MangaCover
import eu.kanade.tachiyomi.ui.browse.suggestions.SuggestionsViewModel
import eu.kanade.tachiyomi.ui.browse.suggestions.SuggestionsViewModel.Section
import kotlinx.coroutines.delay
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.automirroredrounded.OpenInNew
import mihon.icons.materialsymbols.rounded.KeyboardArrowDown
import mihon.icons.materialsymbols.rounded.Person
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.Pill
import tachiyomi.presentation.core.components.ScrollbarLazyColumn
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import java.util.Date
import kotlin.math.ceil
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

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
    // The stop only says how long is left, so pin it to a clock time once, outside the list items,
    // so scrolling the message away and back doesn't push the time later
    val retryAt = remember(state.stop) {
        val retryIn = when (val stop = state.stop) {
            is SuggestionEngine.Stop.LimitReached -> stop.retryIn
            is SuggestionEngine.Stop.Failed -> stop.retryIn
            null -> null
        }
        retryIn?.let { System.currentTimeMillis() + it.inWholeMilliseconds }
    }
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
                    retryAt = retryAt,
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
                FooterItem(state = state, retryAt = retryAt, onLoadMore = onLoadMore, onRetry = onRetry)
            }
        }
        // MangaUpdates' API policy asks to credit them wherever their data is shown
        item(key = "credit") {
            Text(
                text = stringResource(MR.strings.suggestions_credit),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(MaterialTheme.padding.medium),
            )
        }
    }
}

/**
 * Sections are a segmented control; the chips below it narrow the current section. On wide
 * screens (landscape) both share one line so the list starts right away.
 */
@Composable
private fun Header(
    state: SuggestionsViewModel.State,
    onSelectSection: (Section) -> Unit,
    onSelectPeriod: (TrendingPeriod) -> Unit,
    onSelectCategory: (String?) -> Unit,
    onClickTag: (SuggestionTag) -> Unit,
) {
    val hasOptions = state.section != Section.FOR_YOU || state.tagCounts.size > 1
    val options: @Composable () -> Unit = {
        ChipRow {
            when (state.section) {
                Section.FOR_YOU -> {}
                Section.TRENDING -> PeriodPicker(state.trendingPeriod, onSelectPeriod)
                Section.TOP_RATED -> CategoryPicker(state.topRatedCategory, state.yourCategories, onSelectCategory)
            }
            if (state.tagCounts.size > 1) {
                if (state.section != Section.FOR_YOU) {
                    VerticalDivider(
                        modifier = Modifier
                            .height(32.dp)
                            .align(Alignment.CenterVertically),
                    )
                }
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
    BoxWithConstraints {
        if (maxWidth >= WIDE_HEADER_MIN_WIDTH) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionControl(
                    selected = state.section,
                    onSelect = onSelectSection,
                    modifier = Modifier
                        .padding(start = MaterialTheme.padding.medium)
                        .width(SECTION_CONTROL_WIDTH),
                )
                Box(modifier = Modifier.weight(1f)) {
                    if (hasOptions) options()
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.extraSmall)) {
                SectionControl(
                    selected = state.section,
                    onSelect = onSelectSection,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = MaterialTheme.padding.medium),
                )
                if (hasOptions) options()
            }
        }
    }
}

@Composable
private fun SectionControl(
    selected: Section,
    onSelect: (Section) -> Unit,
    modifier: Modifier = Modifier,
) {
    SingleChoiceSegmentedButtonRow(modifier = modifier) {
        Section.entries.forEachIndexed { index, section ->
            SegmentedButton(
                selected = selected == section,
                onClick = { onSelect(section) },
                shape = SegmentedButtonDefaults.itemShape(index, Section.entries.size),
                // The check mark costs width the labels need on small phones
                icon = {},
            ) {
                Text(text = stringResource(section.label), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun PeriodPicker(
    selected: TrendingPeriod,
    onSelect: (TrendingPeriod) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        PickerChip(label = stringResource(selected.label), onClick = { expanded = true })
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            TrendingPeriod.entries.forEach { period ->
                RadioMenuItem(
                    text = { Text(text = stringResource(period.label)) },
                    isChecked = period == selected,
                    onClick = {
                        expanded = false
                        onSelect(period)
                    },
                )
            }
        }
    }
}

@Composable
private fun CategoryPicker(
    selected: String?,
    categories: List<String>,
    onSelect: (String?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val yourGenres = stringResource(MR.strings.suggestions_your_genres)
    Box {
        PickerChip(
            label = selected?.let { SuggestionTag.category(it).label } ?: yourGenres,
            onClick = { expanded = true },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            // The user's own reader categories, as hashtags
            (listOf(null) + categories).forEach { category ->
                RadioMenuItem(
                    text = { Text(text = category?.let { SuggestionTag.category(it).label } ?: yourGenres) },
                    isChecked = category == selected,
                    onClick = {
                        expanded = false
                        onSelect(category)
                    },
                )
            }
        }
    }
}

/** Shows the current choice with a drop-down arrow, so it doesn't read as one more tag filter. */
@Composable
private fun PickerChip(label: String, onClick: () -> Unit) {
    AssistChip(
        onClick = onClick,
        label = { Text(text = label) },
        trailingIcon = {
            Icon(
                imageVector = MaterialSymbols.Rounded.KeyboardArrowDown,
                contentDescription = null,
                modifier = Modifier.size(AssistChipDefaults.IconSize),
            )
        },
    )
}

@Composable
private fun ChipRow(content: @Composable RowScope.() -> Unit) {
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
    retryAt: Long?,
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
            state.stop != null && retryAt != null -> StopMessage(
                stop = state.stop,
                retryAt = retryAt,
                onRetry = onRetry,
            )
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
    retryAt: Long?,
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
            state.stop != null && retryAt != null -> StopMessage(
                stop = state.stop,
                retryAt = retryAt,
                onRetry = onRetry,
            )
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

/**
 * Says when MangaUpdates can be asked again, and keeps Retry off until then: tapping it earlier
 * would only show the same pause.
 */
@Composable
private fun StopMessage(
    stop: SuggestionEngine.Stop,
    retryAt: Long,
    onRetry: () -> Unit,
) {
    val context = LocalContext.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(retryAt) {
        while (now < retryAt) {
            delay((retryAt - now).coerceAtMost(COUNTDOWN_TICK_MS))
            now = System.currentTimeMillis()
        }
    }
    val ready = now >= retryAt
    val time = remember(retryAt) { DateFormat.getTimeFormat(context).format(Date(retryAt)) }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.extraSmall),
    ) {
        Text(
            text = when {
                ready -> stringResource(MR.strings.suggestions_ready_again)
                stop is SuggestionEngine.Stop.LimitReached -> stringResource(MR.strings.suggestions_limit_until, time)
                else -> stringResource(MR.strings.suggestions_paused_until, time)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        TextButton(onClick = onRetry, enabled = ready) {
            Text(
                text = if (ready) {
                    stringResource(MR.strings.action_retry)
                } else {
                    stringResource(MR.strings.suggestions_retry_in, (retryAt - now).milliseconds.minutesUp())
                },
            )
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
            .padding(start = MaterialTheme.padding.medium, top = MaterialTheme.padding.small)
            .padding(bottom = MaterialTheme.padding.small),
        verticalAlignment = Alignment.Top,
    ) {
        if (suggestion.coverUrl != null) {
            MangaCover.Book(
                data = suggestion.coverUrl,
                modifier = Modifier.height(COVER_HEIGHT),
            )
        } else {
            NoCover(suggestion)
        }
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
        // Same as a long-press, but visible, so the MangaUpdates page can be found without guessing
        if (suggestion.url != null) {
            IconButton(onClick = onLongClick) {
                Icon(
                    imageVector = MaterialSymbols.AutoMirroredRounded.OpenInNew,
                    contentDescription = stringResource(MR.strings.suggestions_open_mangaupdates),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            Spacer(modifier = Modifier.width(MaterialTheme.padding.medium))
        }
    }
}

/**
 * MangaUpdates sends no cover for some picks (same-author ones never have one), so show a plain
 * book with the title's initial instead of an empty box.
 */
@Composable
private fun NoCover(suggestion: Suggestion) {
    Box(
        modifier = Modifier
            .height(COVER_HEIGHT)
            .aspectRatio(MangaCover.Book.ratio)
            .clip(MaterialTheme.shapes.extraSmall)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = suggestion.title.trim().take(1).uppercase(),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (SuggestionTag.SameAuthor in suggestion.tags) {
            Icon(
                imageVector = MaterialSymbols.Rounded.Person,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = MaterialTheme.padding.small)
                    .size(16.dp),
            )
        }
    }
}

private fun Duration.minutesUp() = ceil(inWholeSeconds / 60.0).toInt().coerceAtLeast(1)

private const val AUTO_LOAD_MIN_ITEMS = 10
private const val COUNTDOWN_TICK_MS = 15_000L
private val COVER_HEIGHT = 96.dp

// Below this the section control gets its own line
private val WIDE_HEADER_MIN_WIDTH = 600.dp
private val SECTION_CONTROL_WIDTH = 360.dp

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
