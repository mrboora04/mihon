package eu.kanade.domain.suggestions

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Every MangaUpdates request the For you tab makes goes through here, so the app can't overuse
 * their API however often the tab is opened or refreshed. Requests run one at a time, at least
 * [SPACING] apart, and stop at [PER_HOUR] per hour and [PER_DAY] per day (rolling, kept across
 * restarts). After a failed request (429, 5xx, timeout) nothing is sent for [FAILURE_PAUSE].
 */
@Inject
@SingleIn(AppScope::class)
class ForYouLimiter(
    private val preferences: SuggestionPreferences,
) {

    private val mutex = Mutex()
    private var lastRequestAt = 0L

    /** @property afterFailure true while paused after a failed request, false for the hourly/daily caps */
    class LimitReachedException(
        val retryIn: Duration,
        val afterFailure: Boolean = false,
    ) : Exception("MangaUpdates request limit reached")

    /** Runs [block] as one MangaUpdates request, or throws [LimitReachedException] without running it. */
    suspend fun <T> request(block: suspend () -> T): T = mutex.withLock {
        val now = System.currentTimeMillis()
        val pausedFor = preferences.pausedUntil.get() - now
        if (pausedFor > 0) throw LimitReachedException(pausedFor.milliseconds, afterFailure = true)

        val log = preferences.getRequestLog().filter { now - it < DAY_MS }
        val lastHour = log.filter { now - it < HOUR_MS }
        when {
            log.size >= PER_DAY -> throw LimitReachedException((log.min() + DAY_MS - now).milliseconds)
            lastHour.size >= PER_HOUR -> throw LimitReachedException((lastHour.min() + HOUR_MS - now).milliseconds)
        }

        val wait = lastRequestAt + SPACING.inWholeMilliseconds - now
        if (wait > 0) delay(wait)
        lastRequestAt = System.currentTimeMillis()
        preferences.setRequestLog(log + lastRequestAt)

        block()
    }

    /** Stops all requests for [FAILURE_PAUSE], like honouring a Retry-After, so a retry can't hammer the API. */
    fun pauseAfterFailure() {
        preferences.pausedUntil.set(System.currentTimeMillis() + FAILURE_PAUSE.inWholeMilliseconds)
    }

    /** How long until a manual refresh is allowed again, or null if it is allowed now. */
    fun refreshCooldown(): Duration? {
        val left =
            preferences.lastManualRefresh.get() + REFRESH_COOLDOWN.inWholeMilliseconds - System.currentTimeMillis()
        return left.takeIf { it > 0 }?.milliseconds
    }

    fun markManualRefresh() {
        preferences.lastManualRefresh.set(System.currentTimeMillis())
    }

    companion object {
        const val PER_HOUR = 30
        const val PER_DAY = 150
        val SPACING = 2.seconds
        val REFRESH_COOLDOWN = 10.minutes
        val FAILURE_PAUSE = 5.minutes
        private val HOUR_MS = 1.hours.inWholeMilliseconds
        private val DAY_MS = 24.hours.inWholeMilliseconds
    }
}
