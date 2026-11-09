package eu.kanade.domain.suggestions

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import kotlin.random.Random
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

class ForYouLimiterTest {

    private var now = 1_000_000_000L
    private val preferences = SuggestionPreferences(InMemoryPreferenceStore())
    private val limiter = ForYouLimiter(preferences).apply {
        clock = { now }
        random = Random(42)
    }

    @Test
    fun `backoff doubles with each failure and stays between half and the full pause`() {
        val random = Random(7)
        (1..6).forEach { failures ->
            val full = (ForYouLimiter.BASE_PAUSE * (1 shl (failures - 1))).coerceAtMost(ForYouLimiter.MAX_PAUSE)
            val pause = ForYouLimiter.backoff(failures, random)
            pause shouldBeGreaterThanOrEqualTo full / 2
            pause shouldBeLessThanOrEqualTo full
        }
    }

    @Test
    fun `backoff never goes past the maximum`() {
        ForYouLimiter.backoff(50, Random(1)) shouldBeLessThanOrEqualTo ForYouLimiter.MAX_PAUSE
    }

    @Test
    fun `a longer Retry-After wins over the backoff`() {
        limiter.pauseAfterFailure("HTTP 429", retryAfter = 1.hours) shouldBe 1.hours
    }

    @Test
    fun `Retry-After is capped at the maximum pause`() {
        limiter.pauseAfterFailure("HTTP 429", retryAfter = 10.hours) shouldBe ForYouLimiter.MAX_PAUSE
    }

    @Test
    fun `nothing is sent while paused, and a success afterwards resets the backoff`() = runTest {
        val pause = limiter.pauseAfterFailure("HTTP 503")
        var sent = 0

        val blocked = shouldThrow<ForYouLimiter.LimitReachedException> { limiter.request { sent++ } }
        blocked.afterFailure shouldBe true
        blocked.reason shouldBe "HTTP 503"
        sent shouldBe 0

        now += pause.inWholeMilliseconds + 1
        limiter.request { sent++ }
        sent shouldBe 1
        preferences.failuresInARow.get() shouldBe 0
    }

    @Test
    fun `failures in a row make each pause longer`() {
        val first = limiter.pauseAfterFailure("HTTP 503")
        val third = run {
            limiter.pauseAfterFailure("HTTP 503")
            limiter.pauseAfterFailure("HTTP 503")
        }
        // The third pause is at least half of 4x the base, which is more than the first can be
        third shouldBeGreaterThanOrEqualTo ForYouLimiter.BASE_PAUSE * 2
        first shouldBeLessThanOrEqualTo ForYouLimiter.BASE_PAUSE
    }

    @Test
    fun `the hourly cap stops requests without sending them`() = runTest {
        var sent = 0
        repeat(ForYouLimiter.PER_HOUR) { limiter.request { sent++ } }

        val capped = shouldThrow<ForYouLimiter.LimitReachedException> { limiter.request { sent++ } }
        capped.afterFailure shouldBe false
        sent shouldBe ForYouLimiter.PER_HOUR

        now += 61.minutes.inWholeMilliseconds
        limiter.request { sent++ }
        sent shouldBe ForYouLimiter.PER_HOUR + 1
    }
}
