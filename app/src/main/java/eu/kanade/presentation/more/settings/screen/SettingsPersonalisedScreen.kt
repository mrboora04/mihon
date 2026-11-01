package eu.kanade.presentation.more.settings.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import eu.kanade.domain.suggestions.SuggestionSignal
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.tachiyomi.util.system.toast
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
        val preferences = remember { context.appGraph.suggestionPreferences }

        val enabled by preferences.enabled.collectAsState()
        val checkLength by preferences.checkLength.collectAsState()
        val recentSearches by preferences.recentSearches.collectAsState()
        val searchCount = remember(recentSearches) { preferences.getRecentSearches().size }

        return listOf(
            Preference.PreferenceGroup(
                title = stringResource(MR.strings.label_suggestions),
                preferenceItems = listOf(
                    Preference.PreferenceItem.SwitchPreference(
                        preference = preferences.enabled,
                        title = stringResource(MR.strings.pref_suggestions_enabled),
                        subtitle = stringResource(MR.strings.pref_suggestions_enabled_summary),
                    ),
                    Preference.PreferenceItem.MultiSelectListPreference(
                        preference = preferences.signals,
                        entries = mapOf(
                            SuggestionSignal.SEARCHED to stringResource(MR.strings.suggestion_signal_searched),
                            SuggestionSignal.FINISHED to stringResource(MR.strings.suggestion_signal_finished),
                            SuggestionSignal.GENRES to stringResource(MR.strings.suggestion_signal_genres),
                            SuggestionSignal.AUTHORS to stringResource(MR.strings.suggestion_signal_authors),
                            SuggestionSignal.POPULAR to stringResource(MR.strings.suggestion_signal_popular),
                            SuggestionSignal.LATEST to stringResource(MR.strings.suggestion_signal_latest),
                        ),
                        title = stringResource(MR.strings.pref_suggestions_signals),
                        visible = enabled,
                    ),
                    Preference.PreferenceItem.InfoPreference(
                        title = stringResource(MR.strings.pref_suggestions_info),
                    ),
                ),
            ),
            Preference.PreferenceGroup(
                title = stringResource(MR.strings.label_sources),
                visible = enabled,
                preferenceItems = listOf(
                    Preference.PreferenceItem.SliderPreference(
                        preference = preferences.maxSources,
                        title = stringResource(MR.strings.pref_suggestions_max_sources),
                        subtitle = stringResource(MR.strings.pref_suggestions_max_sources_summary),
                        valueRange = 1..15,
                    ),
                    Preference.PreferenceItem.SwitchPreference(
                        preference = preferences.pinnedSourcesOnly,
                        title = stringResource(MR.strings.pref_suggestions_pinned_only),
                    ),
                    Preference.PreferenceItem.SwitchPreference(
                        preference = preferences.hideInLibrary,
                        title = stringResource(MR.strings.pref_hide_in_library_items),
                    ),
                ),
            ),
            Preference.PreferenceGroup(
                title = stringResource(MR.strings.pref_suggestions_length),
                visible = enabled,
                preferenceItems = listOf(
                    Preference.PreferenceItem.SwitchPreference(
                        preference = preferences.checkLength,
                        title = stringResource(MR.strings.pref_suggestions_check_length),
                        subtitle = stringResource(MR.strings.pref_suggestions_check_length_summary),
                    ),
                    Preference.PreferenceItem.SliderPreference(
                        preference = preferences.shortThreshold,
                        title = stringResource(MR.strings.pref_suggestions_short_threshold),
                        valueText = { pluralStringResource(MR.plurals.manga_num_chapters, it, it) },
                        valueRange = 10..100 step 10,
                        visible = checkLength,
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
                        subtitle = pluralStringResource(MR.plurals.suggestions_saved_searches, searchCount, searchCount),
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
