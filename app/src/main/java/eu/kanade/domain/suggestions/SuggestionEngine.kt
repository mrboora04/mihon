package eu.kanade.domain.suggestions

import dev.zacsweers.metro.Inject
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import logcat.LogPriority
import mihon.domain.manga.model.toDomainManga
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.manga.interactor.GetLibraryManga
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.source.local.isLocal
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds
import eu.kanade.tachiyomi.source.model.SManga as SourceManga

/**
 * Builds suggestions from the library, recent searches and the installed sources.
 *
 * Nothing is sent anywhere except the ordinary source requests the Browse tab already makes
 * (popular, latest and search with the source's own filters). All scoring happens on the device.
 */
@Inject
class SuggestionEngine(
    private val preferences: SuggestionPreferences,
    private val sourcePreferences: SourcePreferences,
    private val sourceManager: SourceManager,
    private val getLibraryManga: GetLibraryManga,
    private val networkToLocalManga: NetworkToLocalManga,
) {

    private val dispatcher = Dispatchers.IO.limitedParallelism(5)

    /**
     * @param onProgress called with (finished, total) source requests as they complete.
     */
    suspend fun generate(onProgress: (Int, Int) -> Unit = { _, _ -> }): Result = coroutineScope {
        val signals = preferences.signals.get()
        val library = getLibraryManga.await()
        val seeds = buildSeeds(signals, library)
        val sources = getSources()

        if (sources.isEmpty()) return@coroutineScope Result(emptyList(), seeds, sourceCount = 0)

        // Status only comes from the source's own Status filter; list pages rarely carry it.
        val statusSeeds = seeds.filterIsInstance<Seed.Genre>().firstOrNull()?.let { top ->
            listOf(
                top.copy(tags = top.tags + SuggestionTag.Completed, weight = 1, status = STATUS_COMPLETED),
                top.copy(tags = top.tags + SuggestionTag.Ongoing, weight = 1, status = STATUS_ONGOING),
            )
        }.orEmpty()

        val requests = buildList {
            sources.forEachIndexed { index, source ->
                seeds.forEach { seed -> add(Request(source, seed)) }
                statusSeeds.forEach { seed -> add(Request(source, seed)) }
                // Popular and latest aren't personal, so only the sources at the top of the list
                // (pinned, then last used) contribute them; otherwise they drown out the matches.
                if (index < GENERIC_LISTING_SOURCES) {
                    if (SuggestionSignal.POPULAR in signals) add(Request(source, Seed.Popular))
                    if (SuggestionSignal.LATEST in signals && source.supportsLatest) add(Request(source, Seed.Latest))
                }
            }
        }

        val finished = AtomicInteger(0)
        onProgress(0, requests.size)
        val pages = requests.map { request ->
            async {
                val page = fetch(request)
                onProgress(finished.incrementAndGet(), requests.size)
                request to page
            }
        }.awaitAll()

        val libraryKeys = library.mapTo(HashSet()) { it.manga.source to it.manga.url }
        val libraryTitles = library.mapTo(HashSet()) { it.manga.title.normalized() }
        val hideInLibrary = preferences.hideInLibrary.get()
        val topGenres = seeds.filterIsInstance<Seed.Genre>().mapTo(HashSet()) { it.genre.lowercase() }
        val pinned = sourcePreferences.pinnedSources.get()

        val candidates = LinkedHashMap<Pair<Long, String>, Candidate>()
        val sourcesByTitle = HashMap<String, MutableSet<Long>>()

        for ((request, page) in pages) {
            page?.mangas?.forEach { sManga ->
                val key = request.source.id to sManga.url
                if (hideInLibrary && (key in libraryKeys || sManga.title.normalized() in libraryTitles)) {
                    return@forEach
                }
                val candidate = candidates.getOrPut(key) { Candidate(request.source, sManga) }
                candidate.tags += request.seed.tags
                candidate.score += request.seed.weight
                sourcesByTitle.getOrPut(sManga.title.normalized()) { HashSet() } += request.source.id
            }
        }

        val ranked = candidates.values
            .onEach { candidate ->
                val domain = candidate.sManga.toDomainManga(candidate.source.id)
                domain.genre.orEmpty()
                    .filter { it.lowercase() in topGenres }
                    .forEach {
                        candidate.tags += SuggestionTag.Genre(it)
                        candidate.score += 1
                    }
                if ((sourcesByTitle[candidate.sManga.title.normalized()]?.size ?: 0) > 1) {
                    candidate.tags += SuggestionTag.MultiSource
                    candidate.score += 1
                }
                when (domain.status.toInt()) {
                    SourceManga.COMPLETED, SourceManga.PUBLISHING_FINISHED -> candidate.tags += SuggestionTag.Completed
                    SourceManga.ONGOING -> candidate.tags += SuggestionTag.Ongoing
                }
                // Every suggestion carries at least two reasons; the source it came from is always a true one.
                if (candidate.tags.size < 2) {
                    candidate.tags += if ("${candidate.source.id}" in pinned) {
                        SuggestionTag.PinnedSource
                    } else {
                        SuggestionTag.FromSource(candidate.source.name)
                    }
                }
            }
            .sortedWith(compareByDescending<Candidate> { it.score }.thenByDescending { it.tags.size })
            .distinctBy { it.sManga.title.normalized() }
            .take(MAX_RESULTS)

        val threshold = preferences.shortThreshold.get()
        if (threshold > 0) {
            ranked.take(LENGTH_CHECK_LIMIT).map { candidate ->
                async { candidate to chapterCount(candidate) }
            }.awaitAll().forEach { (candidate, count) ->
                when {
                    count == null || count == 0 -> {}
                    count <= threshold -> candidate.tags += SuggestionTag.ShortSeries
                    count >= threshold * 4 -> candidate.tags += SuggestionTag.LongSeries
                }
            }
        }

        val localManga = networkToLocalManga(ranked.map { it.sManga.toDomainManga(it.source.id) })
        val suggestions = ranked.zip(localManga) { candidate, manga ->
            Suggestion(
                manga = manga,
                sourceName = candidate.source.name,
                tags = candidate.tags.sortedBy { it.order },
                score = candidate.score,
            )
        }

        Result(suggestions, seeds, sourceCount = sources.size)
    }

    private fun buildSeeds(signals: Set<SuggestionSignal>, library: List<LibraryManga>): List<Seed> {
        val seeds = mutableListOf<Seed>()

        if (SuggestionSignal.SEARCHED in signals) {
            preferences.getRecentSearches()
                .take(MAX_SEARCH_SEEDS)
                .forEach { seeds += Seed.Query(it, SuggestionTag.Searched, weight = 3) }
        }

        if (SuggestionSignal.FINISHED in signals) {
            library
                .filter { it.totalChapters > 0 && it.readCount >= it.totalChapters }
                .topGenres(MAX_FINISHED_SEEDS) { 1 }
                .forEach { seeds += Seed.Genre(it, listOf(SuggestionTag.Finished, SuggestionTag.Genre(it)), weight = 3) }
        }

        if (SuggestionSignal.GENRES in signals) {
            val already = seeds.filterIsInstance<Seed.Genre>().mapTo(HashSet()) { it.genre.lowercase() }
            library
                .topGenres(MAX_GENRE_SEEDS + already.size) { if (it.hasStarted) 2 else 1 }
                .filterNot { it.lowercase() in already }
                .take(MAX_GENRE_SEEDS)
                .forEach { seeds += Seed.Genre(it, listOf(SuggestionTag.Genre(it)), weight = 2) }
        }

        if (SuggestionSignal.AUTHORS in signals) {
            library
                .filter { it.hasStarted }
                .mapNotNull { it.manga.author?.takeIf(String::isNotBlank) }
                .groupingBy { it.trim() }
                .eachCount()
                .entries
                .sortedByDescending { it.value }
                .take(MAX_AUTHOR_SEEDS)
                .forEach { seeds += Seed.Query(it.key, SuggestionTag.Author, weight = 2) }
        }

        return seeds
    }

    private suspend fun getSources(): List<Source> {
        val enabledLanguages = sourcePreferences.enabledLanguages.get()
        val disabledSources = sourcePreferences.disabledSources.get()
        val pinned = sourcePreferences.pinnedSources.get()
        val lastUsed = sourcePreferences.lastUsedSource.get()
        val pinnedOnly = preferences.pinnedSourcesOnly.get() && pinned.isNotEmpty()

        return sourceManager.getAll()
            .filter { !it.isLocal() && it.lang in enabledLanguages && "${it.id}" !in disabledSources }
            .filter { !pinnedOnly || "${it.id}" in pinned }
            .sortedWith(
                compareBy(
                    { "${it.id}" !in pinned },
                    { it.id != lastUsed },
                    { it.name.lowercase() },
                ),
            )
    }

    private suspend fun fetch(request: Request): MangasPage? {
        val source = request.source
        return try {
            withTimeoutOrNull(REQUEST_TIMEOUT) {
                withContext(dispatcher) {
                    when (val seed = request.seed) {
                        Seed.Popular -> source.getPopularManga(1)
                        Seed.Latest -> source.getLatestUpdates(1)
                        is Seed.Query -> source.getSearchManga(1, seed.query, source.getFilterList())
                        is Seed.Genre -> {
                            // Same genre matching the Browse screen uses when you tap a genre tag
                            val filters = source.getFilterList()
                            // No Status filter on this source: skip rather than guess
                            if (seed.status != null && !filters.applyStatus(seed.status)) return@withContext null
                            if (filters.applyGenre(seed.genre)) {
                                source.getSearchManga(1, "", filters)
                            } else {
                                source.getSearchManga(1, seed.genre, filters)
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "Suggestions: ${source.name} failed for ${request.seed}" }
            null
        }
    }

    private suspend fun chapterCount(candidate: Candidate): Int? {
        return try {
            withTimeoutOrNull(REQUEST_TIMEOUT) {
                withContext(dispatcher) {
                    candidate.source.getMangaUpdate(
                        manga = candidate.sManga,
                        chapters = emptyList(),
                        fetchDetails = false,
                        fetchChapters = true,
                    ).chapters.size
                }
            }
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "Suggestions: chapter count failed for ${candidate.sManga.title}" }
            null
        }
    }

    private fun List<LibraryManga>.topGenres(count: Int, weight: (LibraryManga) -> Int): List<String> {
        val counts = HashMap<String, Int>()
        val display = HashMap<String, String>()
        forEach { item ->
            item.manga.genre.orEmpty().forEach { genre ->
                val key = genre.trim().lowercase()
                if (key.isEmpty()) return@forEach
                display.putIfAbsent(key, genre.trim())
                counts[key] = (counts[key] ?: 0) + weight(item)
            }
        }
        return counts.entries
            .sortedByDescending { it.value }
            .take(count)
            .map { display.getValue(it.key) }
    }

    private fun String.normalized(): String = lowercase().filter(Char::isLetterOrDigit)

    data class Result(
        val suggestions: List<Suggestion>,
        val seeds: List<Seed>,
        val sourceCount: Int,
    )

    sealed interface Seed {
        val tags: List<SuggestionTag>
        val weight: Int

        data class Query(val query: String, val tag: SuggestionTag, override val weight: Int) : Seed {
            override val tags = listOf(tag)
        }

        data class Genre(
            val genre: String,
            override val tags: List<SuggestionTag>,
            override val weight: Int,
            val status: String? = null,
        ) : Seed

        data object Popular : Seed {
            override val tags = listOf(SuggestionTag.Popular)
            override val weight = 1
        }

        data object Latest : Seed {
            override val tags = listOf(SuggestionTag.New)
            override val weight = 1
        }
    }

    private data class Request(val source: Source, val seed: Seed)

    private class Candidate(val source: Source, val sManga: SourceManga) {
        val tags = LinkedHashSet<SuggestionTag>()
        var score = 0
    }

    companion object {
        private const val MAX_RESULTS = 60
        private const val MAX_SEARCH_SEEDS = 3
        private const val MAX_FINISHED_SEEDS = 2
        private const val MAX_GENRE_SEEDS = 3
        private const val MAX_AUTHOR_SEEDS = 2
        private const val LENGTH_CHECK_LIMIT = 20
        private const val GENERIC_LISTING_SOURCES = 3
        private val REQUEST_TIMEOUT = 20.seconds
        private const val STATUS_COMPLETED = "Completed"
        private const val STATUS_ONGOING = "Ongoing"
    }
}

data class Suggestion(
    val manga: Manga,
    val sourceName: String,
    val tags: List<SuggestionTag>,
    val score: Int,
)

/**
 * A reason a title was suggested. [label] is shown as a hashtag on the suggestion.
 */
sealed class SuggestionTag(val label: String, val order: Int) {
    data object Searched : SuggestionTag("#searched", 0)
    data object Finished : SuggestionTag("#finished", 1)
    data object Author : SuggestionTag("#author", 2)
    data class Genre(val name: String) : SuggestionTag("#" + name.lowercase().replace(Regex("\\s+"), "-"), 3)
    data object MultiSource : SuggestionTag("#multi-source", 4)
    data object ShortSeries : SuggestionTag("#short", 5)
    data object LongSeries : SuggestionTag("#long", 5)
    data object Popular : SuggestionTag("#popular", 6)
    data object New : SuggestionTag("#new", 6)
    data object Completed : SuggestionTag("#completed", 7)
    data object Ongoing : SuggestionTag("#ongoing", 7)
    data object PinnedSource : SuggestionTag("#pinned-source", 8)
    data class FromSource(val name: String) : SuggestionTag("#" + name.lowercase().replace(Regex("\\s+"), "-"), 8)
}

/**
 * Turns on the source filter whose name matches [genreName]. Returns false when the source has no such filter.
 */
fun FilterList.applyGenre(genreName: String): Boolean {
    for (sourceFilter in this) {
        if (sourceFilter is Filter.Group<*>) {
            for (filter in sourceFilter.state) {
                if (filter is Filter<*> && filter.name.equals(genreName, true)) {
                    when (filter) {
                        is Filter.TriState -> filter.state = 1
                        is Filter.CheckBox -> filter.state = true
                        else -> {}
                    }
                    return true
                }
            }
        } else if (sourceFilter is Filter.Select<*>) {
            val index = sourceFilter.values.filterIsInstance<String>()
                .indexOfFirst { it.equals(genreName, true) }

            if (index != -1) {
                sourceFilter.state = index
                return true
            }
        }
    }
    return false
}

/**
 * Turns on [status] (e.g. "Completed") in the source's Status filter. Returns false when the source has none.
 */
fun FilterList.applyStatus(status: String): Boolean {
    for (sourceFilter in this) {
        if (!sourceFilter.name.contains("status", ignoreCase = true)) continue
        if (sourceFilter is Filter.Group<*>) {
            for (filter in sourceFilter.state) {
                if (filter is Filter<*> && filter.name.equals(status, true)) {
                    when (filter) {
                        is Filter.TriState -> filter.state = 1
                        is Filter.CheckBox -> filter.state = true
                        else -> continue
                    }
                    return true
                }
            }
        } else if (sourceFilter is Filter.Select<*>) {
            val index = sourceFilter.values.map { it.toString() }
                .indexOfFirst { it.equals(status, true) }
            if (index != -1) {
                sourceFilter.state = index
                return true
            }
        }
    }
    return false
}
