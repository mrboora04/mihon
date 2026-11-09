package eu.kanade.tachiyomi.ui.browse.suggestions

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.presentation.browse.SuggestionsScreen
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.TabContent
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.GlobalSearchScreen
import eu.kanade.tachiyomi.ui.setting.SettingsScreen
import eu.kanade.tachiyomi.ui.webview.WebViewScreen
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Refresh
import mihon.icons.materialsymbols.rounded.Settings
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import kotlin.math.ceil

@Composable
fun Screen.suggestionsTab(): TabContent {
    val navigator = LocalNavigator.currentOrThrow
    val context = LocalContext.current
    val viewModel = metroViewModel<SuggestionsViewModel>()
    val state by viewModel.state.collectAsStateWithLifecycle()

    return TabContent(
        titleRes = MR.strings.label_discover,
        actions = listOf(
            AppBar.Action(
                title = stringResource(MR.strings.action_webview_refresh),
                icon = MaterialSymbols.Rounded.Refresh,
                onClick = viewModel::refresh,
            ),
            AppBar.Action(
                title = stringResource(MR.strings.pref_category_personalised),
                icon = MaterialSymbols.Rounded.Settings,
                onClick = { navigator.push(SettingsScreen(SettingsScreen.Destination.Personalised)) },
            ),
        ),
        content = { contentPadding, snackbarHostState ->
            SuggestionsScreen(
                state = state,
                contentPadding = contentPadding,
                onSelectSection = viewModel::selectSection,
                onSelectPeriod = viewModel::selectPeriod,
                onSelectCategory = viewModel::selectCategory,
                // Sources are only searched for a title when the user picks it, same as a manual global search
                onClickItem = { navigator.push(GlobalSearchScreen(it.title)) },
                onLongClickItem = { suggestion ->
                    suggestion.url?.let { navigator.push(WebViewScreen(it, suggestion.title)) }
                },
                onClickTag = viewModel::toggleTag,
                onLoadMore = viewModel::loadMore,
                onRetry = viewModel::retry,
                onOpenSettings = { navigator.push(SettingsScreen(SettingsScreen.Destination.Personalised)) },
            )

            // The Browse pager only composes a page while it's on screen (or being swiped to)
            LaunchedEffect(Unit) { viewModel.onShown() }

            LaunchedEffect(Unit) {
                viewModel.events.collectLatest { event ->
                    when (event) {
                        is SuggestionsViewModel.Event.RefreshCooldown -> launch {
                            val minutes = ceil(event.left.inWholeSeconds / 60.0).toInt().coerceAtLeast(1)
                            snackbarHostState.showSnackbar(
                                context.stringResource(MR.strings.suggestions_refresh_cooldown, minutes),
                            )
                        }
                    }
                }
            }
        },
    )
}
