package eu.kanade.domain.suggestions

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.preference.getEnumSet

/**
 * Settings for the Browse > Suggestions tab. Everything here stays on the device.
 */
@Inject
@SingleIn(AppScope::class)
class SuggestionPreferences(
    preferenceStore: PreferenceStore,
) {

    val enabled: Preference<Boolean> = preferenceStore.getBoolean("suggestions_enabled", true)

    val signals: Preference<Set<SuggestionSignal>> = preferenceStore.getEnumSet(
        "suggestions_signals",
        SuggestionSignal.entries.toSet(),
    )

    /** When false, every enabled source is checked (pinned first). */
    val pinnedSourcesOnly: Preference<Boolean> = preferenceStore.getBoolean("suggestions_pinned_only", false)

    val hideInLibrary: Preference<Boolean> = preferenceStore.getBoolean("suggestions_hide_in_library", true)

    /** Chapters at or below this count are tagged #short. 0 turns length tags off. */
    val shortThreshold: Preference<Int> = preferenceStore.getInt("suggestions_short_chapters", 0)

    val recordSearches: Preference<Boolean> = preferenceStore.getBoolean("suggestions_record_searches", true)

    /** Newest first, newline separated. Kept as a string because string sets lose order. */
    val recentSearches: Preference<String> = preferenceStore.getString("suggestions_recent_searches", "")

    fun getRecentSearches(): List<String> {
        return recentSearches.get().lines().filter { it.isNotBlank() }
    }

    fun recordSearch(query: String?) {
        val trimmed = query?.trim().orEmpty()
        if (trimmed.length < 2 || !recordSearches.get()) return
        val updated = (listOf(trimmed) + getRecentSearches().filterNot { it.equals(trimmed, ignoreCase = true) })
            .take(MAX_RECENT_SEARCHES)
        recentSearches.set(updated.joinToString("\n"))
    }

    fun clearRecentSearches() {
        recentSearches.delete()
    }

    companion object {
        const val MAX_RECENT_SEARCHES = 20
    }
}

enum class SuggestionSignal {
    SEARCHED,
    GENRES,
    FINISHED,
    AUTHORS,
    POPULAR,
    LATEST,
}
