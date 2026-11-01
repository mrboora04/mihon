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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.domain.suggestions.Suggestion
import eu.kanade.domain.suggestions.SuggestionTag
import eu.kanade.presentation.manga.components.MangaCover
import eu.kanade.tachiyomi.ui.browse.suggestions.SuggestionsViewModel
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Refresh
import mihon.icons.materialsymbols.rounded.Settings
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.asMangaCover
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.Pill
import tachiyomi.presentation.core.components.ScrollbarLazyColumn
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.EmptyScreenAction
import tachiyomi.presentation.core.screens.LoadingScreen

@Composable
fun SuggestionsScreen(
    state: SuggestionsViewModel.State,
    contentPadding: PaddingValues,
    onClickItem: (Manga) -> Unit,
    onClickTag: (SuggestionTag) -> Unit,
    onRefresh: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    when {
        state.isLoading -> LoadingProgress(state = state, contentPadding = contentPadding)
        state.items.isEmpty() -> EmptyScreen(
            stringRes = when {
                state.failed -> MR.strings.suggestions_failed
                state.sourceCount == 0 -> MR.strings.suggestions_no_sources
                !state.hasSeeds -> MR.strings.suggestions_no_history
                else -> MR.strings.suggestions_empty
            },
            modifier = Modifier.padding(contentPadding),
            actions = listOf(
                EmptyScreenAction(MR.strings.action_retry, MaterialSymbols.Rounded.Refresh, onRefresh),
                EmptyScreenAction(MR.strings.pref_category_personalised, MaterialSymbols.Rounded.Settings, onOpenSettings),
            ),
        )
        else -> ScrollbarLazyColumn(contentPadding = contentPadding) {
            item(key = "tags") {
                TagFilterRow(state = state, onClickTag = onClickTag)
            }
            items(items = state.visibleItems, key = { "${it.manga.source}-${it.manga.url}" }) { suggestion ->
                SuggestionItem(
                    suggestion = suggestion,
                    selectedTag = state.selectedTag,
                    onClick = { onClickItem(suggestion.manga) },
                    onClickTag = onClickTag,
                )
            }
        }
    }
}

@Composable
private fun LoadingProgress(
    state: SuggestionsViewModel.State,
    contentPadding: PaddingValues,
) {
    if (state.progressTotal == 0) {
        LoadingScreen(Modifier.padding(contentPadding))
        return
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .padding(horizontal = MaterialTheme.padding.large),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.medium, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        LinearProgressIndicator(
            progress = { state.progress / state.progressTotal.toFloat() },
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = stringResource(MR.strings.suggestions_progress, state.progress, state.progressTotal),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TagFilterRow(
    state: SuggestionsViewModel.State,
    onClickTag: (SuggestionTag) -> Unit,
) {
    Row(
        modifier = Modifier
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = MaterialTheme.padding.medium),
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
    ) {
        state.tagCounts.take(MAX_FILTER_TAGS).forEach { (tag, count) ->
            FilterChip(
                selected = state.selectedTag == tag,
                onClick = { onClickTag(tag) },
                label = { Text(text = "${tag.label} $count") },
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
    onClickTag: (SuggestionTag) -> Unit,
) {
    Row(
        modifier = Modifier
            .combinedClickable(onClick = onClick)
            .padding(horizontal = MaterialTheme.padding.medium, vertical = MaterialTheme.padding.small),
        verticalAlignment = Alignment.Top,
    ) {
        MangaCover.Book(
            data = suggestion.manga.asMangaCover(),
            modifier = Modifier.height(96.dp),
        )
        Column(
            modifier = Modifier
                .padding(start = MaterialTheme.padding.medium)
                .weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = suggestion.manga.title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = suggestion.sourceName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            FlowRow(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                suggestion.tags.forEach { tag ->
                    val selected = tag == selectedTag
                    Pill(
                        text = tag.label,
                        modifier = Modifier.combinedClickable(onClick = { onClickTag(tag) }),
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

private const val MAX_FILTER_TAGS = 12
