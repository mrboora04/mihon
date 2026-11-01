package eu.kanade.tachiyomi.ui.browse.suggestions

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.presentation.browse.SuggestionsScreen
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.TabContent
import eu.kanade.presentation.more.settings.screen.SettingsPersonalisedScreen
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import eu.kanade.tachiyomi.ui.setting.SettingsScreen
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Refresh
import mihon.icons.materialsymbols.rounded.Settings
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun Screen.suggestionsTab(): TabContent {
    val navigator = LocalNavigator.currentOrThrow
    val viewModel = metroViewModel<SuggestionsViewModel>()
    val state by viewModel.state.collectAsStateWithLifecycle()

    val openSettings = { navigator.push(SettingsScreen(SettingsScreen.Destination.Personalised)) }

    return TabContent(
        titleRes = MR.strings.label_suggestions,
        actions = listOf(
            AppBar.Action(
                title = stringResource(MR.strings.action_webview_refresh),
                icon = MaterialSymbols.Rounded.Refresh,
                onClick = viewModel::refresh,
            ),
            AppBar.Action(
                title = stringResource(MR.strings.pref_category_personalised),
                icon = MaterialSymbols.Rounded.Settings,
                onClick = openSettings,
            ),
        ),
        content = { contentPadding, _ ->
            SuggestionsScreen(
                state = state,
                contentPadding = contentPadding,
                onClickItem = { navigator.push(MangaScreen(it.id, true)) },
                onClickTag = viewModel::toggleTag,
                onRefresh = viewModel::refresh,
                onOpenSettings = openSettings,
            )
        },
    )
}
