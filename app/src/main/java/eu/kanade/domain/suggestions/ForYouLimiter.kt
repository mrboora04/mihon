package eu.kanade.domain.suggestions

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Every MangaUpdates request the Discover tab makes goes through here, so the app can't overuse
 * their API however often the tab is opened or refreshed. Requests run one at a time, at least
 * [SPACING] apart, and stop at [PER_HOUR] per hour and [PER_DAY] per day (rolling, kept across
 * restarts).
 *
 * After a failure that may pass (429, 5xx, timeout, no connection) nothing is sent for a while:
 * [backoff] doubles from [BASE_PAUSE] with each failure in a row, up to [MAX_PAUSE], and a longer
 * `Retry-After` from the server wins. A successful request resets the count.
 */
@Inject
@SingleIn(AppScope::class)
class ForYouLimiter(
    private val preferences: SuggestionPreferences,
) {

    // Swappable so tests can control time and the backoff's randomness
    internal var clock: () -> Long = System::currentTimeMillis
    internal var random: Random = Random.Default

    private val mutex = Mutex()
    private var lastRequestAt = 0L

    /** @property afterFailure true while paused after a failed request, false for the hourly/daily caps */
    class LimitReachedException(
        val retryIn: Duration,
        val afterFailure: Boolean = false,
        val reason: String? = null,
    ) : Exception("MangaUpdates request limit reached")

    /** Runs [block] as one MangaUpdates request, or throws [LimitReachedException] without running it. */
    suspend fun <T> request(block: suspend () -> T): T = mutex.withLock {
        val now = clock()
        val pausedFor = preferences.pausedUntil.get() - now
        if (pausedFor > 0) {
            throw LimitReachedException(
                pausedFor.milliseconds,
                afterFailure = true,
                reason = preferences.pauseReason.get().ifBlank { null },
            )
        }

        val log = preferences.getRequestLog().filter { now - it < DAY_MS }
        val lastHour = log.filter { now - it < HOUR_MS }
        when {
            log.size >= PER_DAY -> throw LimitReachedException((log.min() + DAY_MS - now).milliseconds)
            lastHour.size >= PER_HOUR -> throw LimitReachedException((lastHour.min() + HOUR_MS - now).milliseconds)
        }

        val wait = lastRequestAt + SPACING.inWholeMilliseconds - now
        if (wait > 0) delay(wait)
        lastRequestAt = clock()
        preferences.setRequestLog(log + lastRequestAt)

        block().also {
            if (preferences.failuresInARow.get() != 0) preferences.failuresInARow.set(0)
        }
    }

    /**
     * Stops all requests after a failure that may pass, so a retry can't hammer the API.
     *
     * @return how long requests are paused for
     */
    fun pauseAfterFailure(reason: String, retryAfter: Duration? = null): Duration {
        val failures = preferences.failuresInARow.get() + 1
        preferences.failuresInARow.set(failures)
        val pause = maxOf(backoff(failures, random), retryAfter ?: Duration.ZERO).coerceAtMost(MAX_PAUSE)
        preferences.pauseReason.set(reason)
        preferences.pausedUntil.set(clock() + pause.inWholeMilliseconds)
        return pause
    }

    /** How long until a manual refresh is allowed again, or null if it is allowed now. */
    fun refreshCooldown(): Duration? {
        val left = preferences.lastManualRefresh.get() + REFRESH_COOLDOWN.inWholeMilliseconds - clock()
        return left.takeIf { it > 0 }?.milliseconds
    }

    fun markManualRefresh() {
        preferences.lastManualRefresh.set(clock())
    }

    companion object {
        const val PER_HOUR = 30
        const val PER_DAY = 150
        val SPACING = 2.seconds
        val REFRESH_COOLDOWN = 10.minutes
        val BASE_PAUSE = 5.minutes
        val MAX_PAUSE = 2.hours
        private val HOUR_MS = 1.hours.inWholeMilliseconds
        private val DAY_MS = 24.hours.inWholeMilliseconds

        /**
         * Exponential backoff with "equal jitter": half of the doubled pause is fixed and half is
         * random, so the pause never drops below half while repeated failures don't line up.
         */
        fun backoff(failuresInARow: Int, random: Random): Duration {
            val doubled = BASE_PAUSE * (1 shl (failuresInARow - 1).coerceIn(0, 10))
            val capped = doubled.coerceAtMost(MAX_PAUSE)
            return capped / 2 + (capped / 2) * random.nextDouble()
        }
    }
}
