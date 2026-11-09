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
        "for_you_signals",
        SuggestionSignal.entries.toSet(),
    )

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

    /** Times (epoch ms) of recent MangaUpdates requests, for [ForYouLimiter]'s hourly and daily caps. */
    private val requestLog: Preference<String> = preferenceStore.getString(
        Preference.appStateKey("for_you_request_log"),
        "",
    )

    fun getRequestLog(): List<Long> = requestLog.get().split(',').mapNotNull { it.toLongOrNull() }

    fun setRequestLog(times: List<Long>) {
        requestLog.set(times.joinToString(","))
    }

    /** No MangaUpdates requests until this time (epoch ms), set after a failed request. */
    val pausedUntil: Preference<Long> = preferenceStore.getLong(
        Preference.appStateKey("for_you_paused_until"),
        0L,
    )

    /** Failed MangaUpdates requests since the last success, for [ForYouLimiter]'s backoff. */
    val failuresInARow: Preference<Int> = preferenceStore.getInt(
        Preference.appStateKey("for_you_failures_in_a_row"),
        0,
    )

    /** Why requests were paused, shown with the pause message. */
    val pauseReason: Preference<String> = preferenceStore.getString(
        Preference.appStateKey("for_you_pause_reason"),
        "",
    )

    val lastManualRefresh: Preference<Long> = preferenceStore.getLong(
        Preference.appStateKey("for_you_last_refresh"),
        0L,
    )

    companion object {
        const val MAX_RECENT_SEARCHES = 20
    }
}

enum class SuggestionSignal {
    SEARCHED,
    FINISHED,
    READING,

    /** In the library but not started yet. */
    SAVED,
}
