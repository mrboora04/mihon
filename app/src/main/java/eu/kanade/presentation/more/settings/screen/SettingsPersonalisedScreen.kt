package eu.kanade.presentation.more.settings.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import eu.kanade.domain.suggestions.SuggestionSignal
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.launch
import mihon.app.di.appGraph
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.pluralStringResource
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState

object SettingsPersonalisedScreen : SearchableSettings {

    @ReadOnlyComposable
    @Composable
    override fun getTitleRes() = MR.strings.pref_category_personalised

    @Composable
    override fun getPreferences(): List<Preference> {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val preferences = remember { context.appGraph.suggestionPreferences }
        val cache = remember { context.appGraph.suggestionCache }

        val enabled by preferences.enabled.collectAsState()
        val recentSearches by preferences.recentSearches.collectAsState()
        val searchCount = remember(recentSearches) { preferences.getRecentSearches().size }

        return listOf(
            Preference.PreferenceGroup(
                title = stringResource(MR.strings.label_discover),
                preferenceItems = listOf(
                    Preference.PreferenceItem.SwitchPreference(
                        preference = preferences.enabled,
                        title = stringResource(MR.strings.pref_suggestions_enabled),
                        subtitle = stringResource(MR.strings.pref_suggestions_enabled_summary),
                    ),
                    Preference.PreferenceItem.MultiSelectListPreference(
                        preference = preferences.signals,
                        entries = mapOf(
                            SuggestionSignal.FINISHED to stringResource(MR.strings.suggestion_signal_finished),
                            SuggestionSignal.READING to stringResource(MR.strings.suggestion_signal_reading),
                            SuggestionSignal.SAVED to stringResource(MR.strings.suggestion_signal_saved),
                            SuggestionSignal.SEARCHED to stringResource(MR.strings.suggestion_signal_searched),
                        ),
                        title = stringResource(MR.strings.pref_suggestions_signals),
                        // Keep the summary in the same order as the dialog, not the stored set's order
                        subtitleProvider = { value, entries ->
                            entries.filterKeys { it in value }.values.joinToString()
                                .ifBlank { stringResource(MR.strings.none) }
                        },
                        visible = enabled,
                    ),
                    Preference.PreferenceItem.TextPreference(
                        title = stringResource(MR.strings.pref_suggestions_clear_cache),
                        subtitle = stringResource(MR.strings.pref_suggestions_clear_cache_summary),
                        visible = enabled,
                        onClick = {
                            scope.launch {
                                cache.clear()
                                context.toast(MR.strings.pref_suggestions_cache_cleared)
                            }
                        },
                    ),
                    Preference.PreferenceItem.InfoPreference(
                        title = stringResource(MR.strings.pref_suggestions_info),
                    ),
                ),
            ),
            Preference.PreferenceGroup(
                title = stringResource(MR.strings.pref_suggestions_history),
                visible = enabled,
                preferenceItems = listOf(
                    Preference.PreferenceItem.SwitchPreference(
                        preference = preferences.recordSearches,
                        title = stringResource(MR.strings.pref_suggestions_record_searches),
                        subtitle = stringResource(MR.strings.pref_suggestions_record_searches_summary),
                    ),
                    Preference.PreferenceItem.TextPreference(
                        title = stringResource(MR.strings.pref_suggestions_clear_searches),
                        subtitle = pluralStringResource(
                            MR.plurals.suggestions_saved_searches,
                            searchCount,
                            searchCount,
                        ),
                        onClick = {
                            preferences.clearRecentSearches()
                            context.toast(MR.strings.pref_suggestions_searches_cleared)
                        },
                    ),
                ),
            ),
        )
    }
}
