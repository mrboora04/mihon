package eu.kanade.domain.suggestions

import eu.kanade.tachiyomi.data.track.mangaupdates.MangaUpdatesHttpException
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.SerializationException
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.SocketTimeoutException
import kotlin.time.Duration.Companion.seconds

class SuggestionEngineTest {

    @Test
    fun `rate limits, timeouts and server errors pause, keeping Retry-After`() {
        classifyFailure(MangaUpdatesHttpException(429, 120.seconds)) shouldBe
            Failure.Transient("HTTP 429", 120.seconds)
        classifyFailure(MangaUpdatesHttpException(503, null)).shouldBeInstanceOf<Failure.Transient>()
        classifyFailure(MangaUpdatesHttpException(408, null)).shouldBeInstanceOf<Failure.Transient>()
        classifyFailure(SocketTimeoutException()) shouldBe Failure.Transient("timed out")
        classifyFailure(IOException()) shouldBe Failure.Transient("no connection")
    }

    @Test
    fun `client errors and unreadable replies only skip that request`() {
        classifyFailure(MangaUpdatesHttpException(404, null)) shouldBe Failure.Permanent("HTTP 404")
        classifyFailure(MangaUpdatesHttpException(400, null)).shouldBeInstanceOf<Failure.Permanent>()
        classifyFailure(SerializationException("bad")) shouldBe Failure.Permanent("unexpected reply")
        classifyFailure(IllegalStateException()).shouldBeInstanceOf<Failure.Permanent>()
    }

    @Test
    fun `novels and doujinshi are not suggested`() {
        fun entry(title: String, type: String? = null) = SuggestionCache.Entry(id = 1, title = title, type = type)

        isComic(entry("Sousou no Frieren", "Manga")) shouldBe true
        isComic(entry("Solo Leveling", "Manhwa")) shouldBe true
        isComic(entry("Some Title", "Novel")) shouldBe false
        isComic(entry("Some Title", "Doujinshi")) shouldBe false
        // Recommendation lists send no type, only the name
        isComic(entry("Incubus Banishment (Novel)")) shouldBe false
        isComic(entry("Sousou no Frieren dj - Fern")) shouldBe false
    }

    @Test
    fun `category names drop MangaUpdates' plural endings`() {
        SuggestionTag.categoryName("War/s") shouldBe "War"
        SuggestionTag.categoryName("Elf/ves") shouldBe "Elf"
        SuggestionTag.categoryName("Ambitious Goal/s") shouldBe "Ambitious Goal"
        SuggestionTag.categoryName("Travel/Journey") shouldBe "Travel/Journey"
        SuggestionTag.category("Calm Protagonist").label shouldBe "#calm-protagonist"
    }
}
