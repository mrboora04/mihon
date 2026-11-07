package eu.kanade.domain.suggestions

import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.data.track.mangaupdates.dto.MURecommendation
import eu.kanade.tachiyomi.data.track.mangaupdates.dto.MURecord
import eu.kanade.tachiyomi.data.track.mangaupdates.dto.MUSeriesSearchRequest
import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.util.lang.htmlDecode
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.contentOrNull
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.manga.interactor.GetLibraryManga
import tachiyomi.domain.track.repository.TrackRepository
import java.io.IOException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days

/**
 * Builds the three For you lists from MangaUpdates. Manga sites are never contacted.
 *
 * Every request goes through [ForYouLimiter] (spaced out, capped per hour and day), answers are
 * cached ([SERIES_TTL], [TRENDING_TTL], [TOP_RATED_TTL]), and a round stops at the first failure.
 */
@Inject
class SuggestionEngine(
    private val preferences: SuggestionPreferences,
    private val cache: SuggestionCache,
    private val limiter: ForYouLimiter,
    private val getLibraryManga: GetLibraryManga,
    private val trackRepository: TrackRepository,
    trackerManager: TrackerManager,
) {

    private val mangaUpdates = trackerManager.mangaUpdates

    enum class TrendingPeriod(val orderBy: String) {
        WEEK("week_pos"),
        MONTH("month1_pos"),
        THREE_MONTHS("month3_pos"),
    }

    /** Why a round stopped early; the screen explains it instead of retrying by itself. */
    sealed interface Stop {
        data class LimitReached(val retryIn: Duration) : Stop

        /**
         * MangaUpdates failed; nothing is sent again until [retryIn] has passed.
         *
         * @property reason short cause such as "HTTP 429", shown so failures can be told apart
         */
        data class Failed(val retryIn: Duration, val reason: String? = null) : Stop
    }

    // region For you

    /**
     * Reports what is cached straight away, then spends up to [budget] requests on titles with
     * nothing (or an old answer) cached, reporting after each one.
     */
    suspend fun loadForYou(budget: Int, onUpdate: (ForYouResult) -> Unit = {}): ForYouResult {
        val context = libraryContext()
        suspend fun snapshot(stop: Stop? = null) = rankForYou(context).copy(stop = stop).also(onUpdate)

        var result = snapshot()
        val runner = Runner(budget)
        for (step in pendingForYouWork(context)) {
            if (runner.remaining <= 0) break
            val ok = when (step) {
                is Work.Search -> runner.run { search(step.query) } && run {
                    val hitId = cache.read { data ->
                        data.searches[step.query.lowercase()]?.results?.firstOrNull(::isComic)?.id
                            ?.takeIf { it !in data.series }
                    }
                    if (hitId == null || runner.remaining <= 0) {
                        true
                    } else {
                        result = snapshot()
                        runner.run { fetchSeries(hitId) }
                    }
                }
                is Work.Fetch -> runner.run { fetchSeries(step.seriesId) }
                is Work.Author -> runner.run { fetchAuthor(step.authorId) }
                is Work.Match -> runner.run { match(step.seed) } && run {
                    // Fetch its recommendations right away if the match was found
                    val seriesId = cache.read { it.matches[step.seed.mangaId] }
                    if (seriesId == null || seriesId == SuggestionCache.NO_MATCH || runner.remaining <= 0) {
                        true
                    } else {
                        result = snapshot()
                        runner.run { fetchSeries(seriesId) }
                    }
                }
            }
            if (!ok) return snapshot(runner.stop)
            result = snapshot()
        }
        return result
    }

    private suspend fun pendingForYouWork(context: LibraryContext): List<Work> = cache.read { data ->
        val now = System.currentTimeMillis()
        buildList {
            context.searches.forEach { query ->
                val cached = data.searches[query.lowercase()]
                if (cached.isStale(now, SERIES_TTL)) {
                    add(Work.Search(query))
                } else {
                    // A search stands for the title the user was looking for: use its recommendations
                    cached?.results?.firstOrNull(::isComic)?.let { hit ->
                        if (data.series[hit.id].isStale(now, SERIES_TTL)) add(Work.Fetch(hit.id))
                    }
                }
            }
            context.seeds.forEach { seed ->
                when (val seriesId = context.seriesIdOf(seed, data)) {
                    null -> add(Work.Match(seed))
                    SuggestionCache.NO_MATCH -> {}
                    else -> if (data.series[seriesId].isStale(now, SERIES_TTL)) add(Work.Fetch(seriesId))
                }
            }
            // Authors come last: they need the series fetched first, and are a smaller signal
            context.seeds
                .mapNotNull { seed -> context.seriesIdOf(seed, data)?.let { data.series[it] } }
                .flatMap { it.authorIds.take(1) }
                .distinct()
                .take(MAX_AUTHORS)
                .filter { data.authors[it].isStale(now, SERIES_TTL) }
                .forEach { add(Work.Author(it)) }
        }
    }

    private suspend fun rankForYou(context: LibraryContext): ForYouResult = cache.read { data ->
        val picks = Picks(context, data)

        // The best match for a search, and what readers recommend for it; not the other hits, which
        // are mostly spin-offs of the same title
        context.searches.forEach { query ->
            val hit = data.searches[query.lowercase()]?.results?.firstOrNull(::isComic) ?: return@forEach
            picks.add(hit, SuggestionTag.Searched, score = 3, searched = query)
            data.series[hit.id]?.let { series ->
                series.readersPicks.forEach { entry ->
                    picks.add(
                        entry,
                        SuggestionTag.Searched,
                        SuggestionTag.ReadersPick,
                        score = entry.weight * 2,
                        searched = query,
                    )
                }
                series.similar.forEach { entry ->
                    picks.add(entry, SuggestionTag.Searched, SuggestionTag.SimilarTags, score = 1, searched = query)
                }
            }
        }
        var checkedTitles = 0
        context.seeds.forEach { seed ->
            val series = context.seriesIdOf(seed, data)?.let { data.series[it] } ?: return@forEach
            checkedTitles++
            val seedWeight = when (seed.kind) {
                SeedKind.FINISHED -> 3
                SeedKind.READING -> 2
                SeedKind.SAVED -> 1
            }
            val reason = when (seed.kind) {
                SeedKind.FINISHED -> SuggestionTag.Finished
                SeedKind.READING -> SuggestionTag.Reading
                SeedKind.SAVED -> SuggestionTag.Saved
            }
            series.readersPicks.forEach { entry ->
                picks.add(
                    entry,
                    reason,
                    SuggestionTag.ReadersPick,
                    score = entry.weight * 2 * seedWeight,
                    because = seed.title,
                )
            }
            series.similar.forEach { entry ->
                picks.add(entry, reason, SuggestionTag.SimilarTags, score = seedWeight, because = seed.title)
            }
            series.authorIds.take(1).forEach { authorId ->
                data.authors[authorId]?.results?.forEach { entry ->
                    picks.add(entry, reason, SuggestionTag.SameAuthor, score = seedWeight, because = seed.title)
                }
            }
        }

        val pendingTitles = context.seeds.count { seed ->
            val seriesId = context.seriesIdOf(seed, data)
            seriesId == null || (seriesId != SuggestionCache.NO_MATCH && seriesId !in data.series)
        }
        ForYouResult(
            suggestions = picks.ranked(),
            hasSeeds = context.seeds.isNotEmpty() || context.searches.isNotEmpty(),
            checkedTitles = checkedTitles,
            pendingTitles = pendingTitles,
            yourCategories = context.topCategories(data),
        )
    }

    // endregion

    // region Trending and Top rated

    /** One MangaUpdates request per period per day, at most. */
    suspend fun loadTrending(period: TrendingPeriod): ListResult = loadList(
        key = "trending:${period.name}",
        ttl = TRENDING_TTL,
        tags = listOf(SuggestionTag.Trending),
        ranked = true,
        request = { MUSeriesSearchRequest(orderby = period.orderBy) },
    )

    /**
     * Best-rated series with English chapters, either in the genres the user reads most or in one of
     * their reader categories. One request per choice per week, at most.
     */
    suspend fun loadTopRated(category: String?): ListResult {
        val context = libraryContext()
        val genres = cache.read { context.topGenres(it) }.take(2)
        if (category == null && genres.isEmpty()) return ListResult(emptyList(), needsLibrary = true)
        return loadList(
            key = if (category != null) "top:category:$category" else "top:genres:${genres.joinToString("+")}",
            ttl = TOP_RATED_TTL,
            tags = listOfNotNull(SuggestionTag.TopRated, category?.let(SuggestionTag::category)),
            ranked = false,
            context = context,
            request = {
                MUSeriesSearchRequest(
                    genre = genres.takeIf { category == null },
                    category = category?.let(::listOf),
                    filters = listOf("scanlated"),
                    orderby = "rating",
                )
            },
        )
    }

    private suspend fun loadList(
        key: String,
        ttl: Duration,
        tags: List<SuggestionTag>,
        ranked: Boolean,
        context: LibraryContext? = null,
        request: () -> MUSeriesSearchRequest,
    ): ListResult {
        val libraryContext = context ?: libraryContext()
        var stop: Stop? = null
        val stale = cache.read { it.lists[key] }.isStale(System.currentTimeMillis(), ttl)
        if (stale) {
            val runner = Runner(budget = 1)
            val ok = runner.run {
                val results = mangaUpdates.searchSeries(request()).mapNotNull(::toEntry)
                cache.edit { it.lists[key] = SuggestionCache.Search(System.currentTimeMillis(), results) }
            }
            if (!ok) stop = runner.stop
        }
        return cache.read { data ->
            val picks = Picks(libraryContext, data)
            data.lists[key]?.results?.forEachIndexed { index, entry ->
                picks.add(entry, *tags.toTypedArray(), score = 1000 - index, rank = (index + 1).takeIf { ranked })
            }
            ListResult(picks.ranked(), stop = stop)
        }
    }

    // endregion

    suspend fun clearCache() = cache.clear()

    fun refreshCooldown() = limiter.refreshCooldown()

    fun markManualRefresh() = limiter.markManualRefresh()

    /** Runs requests through the limiter until the budget runs out or one fails. */
    private inner class Runner(budget: Int) {
        var remaining = budget
            private set
        var stop: Stop? = null
            private set

        suspend fun run(block: suspend () -> Unit): Boolean {
            if (remaining <= 0) return false
            remaining--
            return try {
                limiter.request { block() }
                true
            } catch (e: ForYouLimiter.LimitReachedException) {
                stop = if (e.afterFailure) Stop.Failed(e.retryIn, e.reason) else Stop.LimitReached(e.retryIn)
                false
            } catch (e: Exception) {
                // Covers 429/5xx and timeouts alike: back off for a while rather than keep asking
                logcat(LogPriority.WARN, e) { "For you: MangaUpdates request failed, pausing requests" }
                val reason = failureReason(e)
                limiter.pauseAfterFailure(reason)
                stop = Stop.Failed(ForYouLimiter.FAILURE_PAUSE, reason)
                false
            }
        }
    }

    private fun failureReason(e: Exception): String = when (e) {
        is HttpException -> "HTTP ${e.code}"
        is SerializationException -> "unexpected reply"
        is IOException -> "no connection"
        else -> e::class.simpleName ?: "error"
    }

    private suspend fun match(seed: Seed) {
        val normalizedTitle = seed.title.normalized()
        // Sources often use an English title while MangaUpdates' main title is romaji, so an
        // alternative name matching exactly counts too
        val found = mangaUpdates.searchSeriesHits(MUSeriesSearchRequest(search = seed.title))
            .firstOrNull { item ->
                listOfNotNull(item.record.title, item.hitTitle)
                    .any { it.htmlDecode().normalized() == normalizedTitle }
            }
            ?.record
            ?.seriesId
        cache.edit { it.matches[seed.mangaId] = found ?: SuggestionCache.NO_MATCH }
    }

    private suspend fun fetchSeries(seriesId: Long) {
        val record = mangaUpdates.getSeriesDetails(seriesId)
        cache.edit {
            it.series[seriesId] = SuggestionCache.Series(
                fetchedAt = System.currentTimeMillis(),
                readersPicks = record?.recommendations.orEmpty().mapNotNull(::toEntry),
                similar = record?.categoryRecommendations.orEmpty().mapNotNull(::toEntry),
                genres = record?.genres.orEmpty().mapNotNull { genre -> genre.genre },
                categories = record?.categories.orEmpty()
                    .filter { (it.votes ?: 0) >= MIN_CATEGORY_VOTES }
                    .sortedByDescending { it.votes }
                    .mapNotNull { it.category?.htmlDecode() }
                    .take(MAX_CATEGORIES_PER_SERIES),
                authorIds = record?.authors.orEmpty().filter { it.type == "Author" }.mapNotNull { it.authorId },
            )
        }
    }

    private suspend fun fetchAuthor(authorId: Long) {
        val series = mangaUpdates.getAuthorSeries(authorId).mapNotNull { item ->
            SuggestionCache.Entry(
                id = item.seriesId ?: return@mapNotNull null,
                title = item.title?.htmlDecode() ?: return@mapNotNull null,
                url = item.url,
                year = item.year?.contentOrNull,
                genres = item.genres.orEmpty(),
            )
        }
        cache.edit { it.authors[authorId] = SuggestionCache.Search(System.currentTimeMillis(), series) }
    }

    private suspend fun search(query: String) {
        val results = mangaUpdates.searchSeries(MUSeriesSearchRequest(search = query)).mapNotNull(::toEntry)
        cache.edit { it.searches[query.lowercase()] = SuggestionCache.Search(System.currentTimeMillis(), results) }
    }

    private fun toEntry(record: MURecord): SuggestionCache.Entry? = SuggestionCache.Entry(
        id = record.seriesId ?: return null,
        title = record.title?.htmlDecode() ?: return null,
        url = record.url,
        cover = record.image?.url?.original,
        rating = record.bayesianRating?.takeIf { it > 0 },
        type = record.type,
        year = record.year,
        genres = record.genres.orEmpty().mapNotNull { it.genre },
    )

    private fun toEntry(rec: MURecommendation): SuggestionCache.Entry? {
        val title = rec.seriesName?.htmlDecode() ?: return null
        return SuggestionCache.Entry(
            id = rec.seriesId ?: return null,
            title = title,
            url = rec.seriesUrl,
            cover = rec.seriesImage?.url?.original,
            weight = rec.weight?.coerceIn(1, 5) ?: 1,
        )
    }

    private suspend fun libraryContext(): LibraryContext {
        val signals = preferences.signals.get()
        val library = getLibraryManga.await()
        val trackedIds = trackRepository.getTracks()
            .filter { it.trackerId == mangaUpdates.id && it.remoteId > 0 }
            .associate { it.mangaId to it.remoteId }
        val searches = if (SuggestionSignal.SEARCHED in signals) {
            preferences.getRecentSearches().take(MAX_SEARCH_SEEDS)
        } else {
            emptyList()
        }
        return LibraryContext(library, trackedIds, buildSeeds(signals, library), searches)
    }

    private fun buildSeeds(signals: Set<SuggestionSignal>, library: List<LibraryManga>): List<Seed> {
        val finishedIds = library
            .filter { it.totalChapters > 0 && it.readCount >= it.totalChapters }
            .mapTo(HashSet()) { it.id }
        fun kindOf(item: LibraryManga) = when {
            item.id in finishedIds -> SeedKind.FINISHED
            item.hasStarted -> SeedKind.READING
            else -> SeedKind.SAVED
        }
        val wanted = buildSet {
            if (SuggestionSignal.FINISHED in signals) add(SeedKind.FINISHED)
            if (SuggestionSignal.READING in signals) add(SeedKind.READING)
            if (SuggestionSignal.SAVED in signals) add(SeedKind.SAVED)
        }
        return library
            .filter { kindOf(it) in wanted }
            .sortedWith(
                compareBy<LibraryManga> { kindOf(it).ordinal }
                    .thenByDescending { it.lastRead }
                    .thenByDescending { it.manga.favoriteAt },
            )
            .take(MAX_SEEDS)
            .map { Seed(it.manga.id, it.manga.title, kindOf(it)) }
    }

    private class LibraryContext(
        library: List<LibraryManga>,
        val trackedIds: Map<Long, Long>,
        val seeds: List<Seed>,
        val searches: List<String>,
    ) {
        val libraryIds = library.map { it.manga.id }
        val libraryTitles = library.mapTo(HashSet()) { it.manga.title.normalized() }

        fun seriesIdOf(seed: Seed, data: SuggestionCache.Data) = trackedIds[seed.mangaId] ?: data.matches[seed.mangaId]

        fun librarySeriesIds(data: SuggestionCache.Data) =
            libraryIds.mapNotNullTo(HashSet()) { trackedIds[it] ?: data.matches[it] }

        private fun seedSeries(data: SuggestionCache.Data) =
            seeds.mapNotNull { seed -> seriesIdOf(seed, data)?.let { data.series[it] } }

        /** MangaUpdates genres the user reads most, from the titles already looked up. */
        fun topGenres(data: SuggestionCache.Data): List<String> =
            seedSeries(data).flatMap { it.genres }.mostCommon()

        /** Reader categories the user's titles share most, used as hashtags. */
        fun topCategories(data: SuggestionCache.Data): List<String> =
            seedSeries(data).flatMap { it.categories }.mostCommon().take(MAX_YOUR_CATEGORIES)

        private fun List<String>.mostCommon() =
            groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key }
    }

    /** Collects candidates, skipping anything already in the library. */
    private class Picks(private val context: LibraryContext, data: SuggestionCache.Data) {
        private val librarySeriesIds = context.librarySeriesIds(data)
        private val topGenres = context.topGenres(data).take(MAX_GENRE_TAGS).toSet()
        private val candidates = LinkedHashMap<Long, Candidate>()

        fun add(
            entry: SuggestionCache.Entry,
            vararg tags: SuggestionTag,
            score: Int,
            because: String? = null,
            searched: String? = null,
            rank: Int? = null,
        ) {
            if (!isComic(entry)) return
            if (entry.id in librarySeriesIds || entry.title.normalized() in context.libraryTitles) return
            val c = candidates.getOrPut(entry.id) { Candidate(entry) }
            c.tags += tags
            c.score += score
            because?.let { c.because += it }
            searched?.let { c.searched += it }
            if (rank != null) c.rank = rank
        }

        fun ranked(): List<Suggestion> = candidates.values
            .onEach { c ->
                if (c.because.size > 1) {
                    c.tags += SuggestionTag.MultiPick
                    c.score += c.because.size
                }
                c.entry.genres.filter { it in topGenres }.forEach { c.tags += SuggestionTag.genre(it) }
                c.entry.type?.takeIf { it.isNotBlank() }?.let { c.tags += SuggestionTag.type(it) }
                // Every suggestion shows at least two reasons; where it came from is always true
                if (c.tags.size < 2) c.tags += SuggestionTag.MangaUpdates
            }
            .sortedWith(compareByDescending<Candidate> { it.score }.thenBy { it.entry.title })
            .take(MAX_RESULTS)
            .map { c ->
                Suggestion(
                    seriesId = c.entry.id,
                    title = c.entry.title,
                    coverUrl = c.entry.cover,
                    url = c.entry.url,
                    rating = c.entry.rating,
                    year = c.entry.year,
                    rank = c.rank,
                    because = c.because.toList(),
                    becauseSearched = c.searched.toList(),
                    tags = c.tags.sortedBy { it.order },
                )
            }
    }

    data class ForYouResult(
        val suggestions: List<Suggestion>,
        val hasSeeds: Boolean,
        val checkedTitles: Int,
        val pendingTitles: Int,
        val yourCategories: List<String>,
        val stop: Stop? = null,
    )

    data class ListResult(
        val suggestions: List<Suggestion>,
        /** Top rated needs a few library titles looked up first, to know the user's genres. */
        val needsLibrary: Boolean = false,
        val stop: Stop? = null,
    )

    private enum class SeedKind { FINISHED, READING, SAVED }

    private data class Seed(val mangaId: Long, val title: String, val kind: SeedKind)

    private sealed interface Work {
        data class Match(val seed: Seed) : Work
        data class Fetch(val seriesId: Long) : Work
        data class Author(val authorId: Long) : Work
        data class Search(val query: String) : Work
    }

    private class Candidate(val entry: SuggestionCache.Entry) {
        val tags = LinkedHashSet<SuggestionTag>()
        val because = LinkedHashSet<String>()
        val searched = LinkedHashSet<String>()
        var score = 0
        var rank: Int? = null
    }

    companion object {
        private const val MAX_RESULTS = 80
        private const val MAX_SEEDS = 40
        private const val MAX_AUTHORS = 8
        private const val MAX_SEARCH_SEEDS = 3
        private const val MIN_CATEGORY_VOTES = 3
        private const val MAX_CATEGORIES_PER_SERIES = 8
        private const val MAX_YOUR_CATEGORIES = 6
        private const val MAX_GENRE_TAGS = 4
        private val SERIES_TTL = 7.days
        private val TRENDING_TTL = 1.days
        private val TOP_RATED_TTL = 7.days
    }
}

private fun String.normalized(): String = lowercase().filter(Char::isLetterOrDigit)

private val NOVEL_MARKER = Regex("""\((Light )?Novel\)""", RegexOption.IGNORE_CASE)
private val DOUJINSHI_MARKER = Regex("""\bdj - """, RegexOption.IGNORE_CASE)
private val EXCLUDED_TYPES = setOf("Novel", "Doujinshi")

/**
 * For you only suggests comics. MangaUpdates marks novels and doujinshi in the type, or in the
 * name when the type isn't sent (recommendation lists).
 */
private fun isComic(entry: SuggestionCache.Entry) =
    entry.type !in EXCLUDED_TYPES &&
        !NOVEL_MARKER.containsMatchIn(entry.title) &&
        !DOUJINSHI_MARKER.containsMatchIn(entry.title)

private fun SuggestionCache.Series?.isStale(now: Long, ttl: Duration) =
    this == null || now - fetchedAt > ttl.inWholeMilliseconds

private fun SuggestionCache.Search?.isStale(now: Long, ttl: Duration) =
    this == null || now - fetchedAt > ttl.inWholeMilliseconds

data class Suggestion(
    val seriesId: Long,
    val title: String,
    val coverUrl: String?,
    val url: String?,
    val rating: Double?,
    val year: String?,
    /** Position in a trending list, 1-based. */
    val rank: Int?,
    val because: List<String>,
    /** Searches this was found through, for a "Because you searched…" line. */
    val becauseSearched: List<String> = emptyList(),
    val tags: List<SuggestionTag>,
)

/**
 * A reason a title was suggested, shown as a hashtag. Genre and type tags are made on the fly.
 */
data class SuggestionTag(val label: String, val order: Int) {
    companion object {
        val Searched = SuggestionTag("#searched", 0)
        val Finished = SuggestionTag("#finished", 1)
        val Reading = SuggestionTag("#reading", 2)
        val Saved = SuggestionTag("#saved", 2)
        val Trending = SuggestionTag("#trending", 2)
        val TopRated = SuggestionTag("#top-rated", 2)
        val MultiPick = SuggestionTag("#multi-pick", 3)
        val ReadersPick = SuggestionTag("#readers-pick", 4)
        val SimilarTags = SuggestionTag("#similar-tags", 5)
        val SameAuthor = SuggestionTag("#same-author", 5)
        val MangaUpdates = SuggestionTag("#mangaupdates", 9)

        fun genre(name: String) = SuggestionTag(hashtag(name), 6)

        // MangaUpdates writes plurals as "War/s" or "Elf/ves"; the tag drops that ending
        fun category(name: String) = SuggestionTag(hashtag(name.replace(PLURAL_SUFFIX, "")), 7)

        fun type(name: String) = SuggestionTag(hashtag(name), 8)

        private val PLURAL_SUFFIX = Regex("""/\w{1,3}\b""")

        private fun hashtag(name: String) =
            "#" + name.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), "-").trim('-')
    }
}
