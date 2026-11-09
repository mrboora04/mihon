package eu.kanade.domain.suggestions

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.io.File
import kotlin.time.Duration.Companion.days

/**
 * What MangaUpdates has told us, kept on the device so the For you tab can be opened again
 * without asking twice. MangaUpdates' API policy asks clients to cache.
 */
@Inject
@SingleIn(AppScope::class)
class SuggestionCache(
    private val context: Context,
    private val json: Json,
) {

    private val file get() = File(context.filesDir, "for_you_mangaupdates.json")
    private val mutex = Mutex()
    private var data: Data? = null

    suspend fun <T> read(block: (Data) -> T): T = mutex.withLock { block(load()) }

    suspend fun edit(block: (Data) -> Unit) = mutex.withLock {
        val current = load()
        block(current)
        save(current)
    }

    suspend fun clear() = mutex.withLock {
        data = Data()
        withContext(Dispatchers.IO) { file.delete() }
    }

    private suspend fun load(): Data {
        data?.let { return it }
        val loaded = withContext(Dispatchers.IO) {
            try {
                file.takeIf { it.exists() }?.readText()?.let { json.decodeFromString<Data>(it) }
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e) { "For you: dropping unreadable cache" }
                null
            }
        } ?: Data()
        loaded.pruneOlderThan(System.currentTimeMillis() - KEEP_FOR.inWholeMilliseconds)
        data = loaded
        return loaded
    }

    private suspend fun save(current: Data) = withContext(Dispatchers.IO) {
        try {
            file.writeText(json.encodeToString(Data.serializer(), current))
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "For you: couldn't save cache" }
        }
    }

    @Serializable
    data class Data(
        /** Library manga id -> MangaUpdates series id, or [NO_MATCH]. Kept until the cache is cleared. */
        val matches: MutableMap<Long, Long> = mutableMapOf(),
        /** MangaUpdates series id -> its recommendations. */
        val series: MutableMap<Long, Series> = mutableMapOf(),
        /** Lowercased search text -> MangaUpdates search results. */
        val searches: MutableMap<String, Search> = mutableMapOf(),
        /** MangaUpdates author id -> their series. */
        val authors: MutableMap<Long, Search> = mutableMapOf(),
        /** A list key (trending period, top-rated query) -> its results, in MangaUpdates' order. */
        val lists: MutableMap<String, Search> = mutableMapOf(),
    ) {
        /**
         * Drops answers nothing has needed for a long time, so the file stays small. Anything still
         * in use is refreshed long before this. Title matches are tiny and kept.
         */
        fun pruneOlderThan(cutoff: Long) {
            series.values.removeAll { it.fetchedAt < cutoff }
            searches.values.removeAll { it.fetchedAt < cutoff }
            authors.values.removeAll { it.fetchedAt < cutoff }
            lists.values.removeAll { it.fetchedAt < cutoff }
        }
    }

    @Serializable
    data class Series(
        val fetchedAt: Long,
        val readersPicks: List<Entry>,
        val similar: List<Entry>,
        val genres: List<String> = emptyList(),
        /** Most-voted reader categories first. */
        val categories: List<String> = emptyList(),
        val authorIds: List<Long> = emptyList(),
    )

    @Serializable
    data class Search(
        val fetchedAt: Long,
        val results: List<Entry>,
    )

    @Serializable
    data class Entry(
        val id: Long,
        val title: String,
        val url: String? = null,
        val cover: String? = null,
        val weight: Int = 1,
        val rating: Double? = null,
        val type: String? = null,
        val year: String? = null,
        val genres: List<String> = emptyList(),
    )

    companion object {
        const val NO_MATCH = -1L
        private val KEEP_FOR = 30.days
    }
}
