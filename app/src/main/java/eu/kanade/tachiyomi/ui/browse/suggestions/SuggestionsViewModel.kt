package eu.kanade.tachiyomi.ui.browse.suggestions

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import eu.kanade.domain.suggestions.Suggestion
import eu.kanade.domain.suggestions.SuggestionEngine
import eu.kanade.domain.suggestions.SuggestionEngine.TrendingPeriod
import eu.kanade.domain.suggestions.SuggestionTag
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import kotlin.time.Duration

@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class SuggestionsViewModel(
    private val engine: SuggestionEngine,
) : ViewModel() {

    val state: StateFlow<State>
        field = MutableStateFlow(State())

    private val _events = Channel<Event>(Channel.UNLIMITED)
    val events = _events.receiveAsFlow()

    private var job: Job? = null

    /**
     * Called when the tab's page is actually on screen. Browse builds every tab up front, so
     * loading in init would ask MangaUpdates whenever Browse opens.
     */
    fun onShown() {
        if (state.value.current == null && job?.isActive != true) load()
    }

    fun selectSection(section: Section) {
        if (state.value.section == section) return
        state.update { it.copy(section = section, selectedTag = null) }
        if (state.value.current == null) load()
    }

    fun selectPeriod(period: TrendingPeriod) {
        state.update { it.copy(trendingPeriod = period, selectedTag = null) }
        if (state.value.current == null) load()
    }

    /** Null means "your genres". */
    fun selectCategory(category: String?) {
        state.update { it.copy(topRatedCategory = category, selectedTag = null) }
        if (state.value.current == null) load()
    }

    /** Asks MangaUpdates about the next few library titles that have nothing cached yet. */
    fun loadMore() {
        val current = state.value
        if (current.section != Section.FOR_YOU || job?.isActive == true) return
        if (current.forYou?.stop != null || (current.forYou?.pendingTitles ?: 0) == 0) return
        load()
    }

    /** Tries again after a failure or after the limit has passed. */
    fun retry() = load()

    /** Manual refresh, allowed once every few minutes so the API isn't asked over and over. */
    fun refresh() {
        engine.refreshCooldown()?.let { left ->
            _events.trySend(Event.RefreshCooldown(left))
            return
        }
        engine.markManualRefresh()
        load()
    }

    private fun load() {
        val snapshot = state.value
        job?.cancel()
        state.update { it.copy(isLoading = true, failed = false) }
        job = viewModelScope.launch(Dispatchers.IO) {
            try {
                when (snapshot.section) {
                    Section.FOR_YOU -> {
                        val result = engine.loadForYou(budget = REQUESTS_PER_BATCH) { partial ->
                            state.update { it.copy(forYou = partial) }
                        }
                        state.update { it.copy(forYou = result) }
                    }
                    Section.TRENDING -> {
                        val result = engine.loadTrending(snapshot.trendingPeriod)
                        state.update { it.copy(trending = it.trending + (snapshot.trendingPeriod to result)) }
                    }
                    Section.TOP_RATED -> {
                        val result = engine.loadTopRated(snapshot.topRatedCategory)
                        state.update { it.copy(topRated = it.topRated + (snapshot.topRatedCategory to result)) }
                    }
                }
                state.update { it.copy(isLoading = false) }
            } catch (e: CancellationException) {
                // A newer load replaced this one; it isn't a failure
                throw e
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e)
                state.update { it.copy(isLoading = false, failed = true) }
            }
        }
    }

    fun toggleTag(tag: SuggestionTag) {
        state.update {
            it.copy(selectedTag = if (it.selectedTag == tag) null else tag)
        }
    }

    enum class Section { FOR_YOU, TRENDING, TOP_RATED }

    sealed interface Event {
        data class RefreshCooldown(val left: Duration) : Event
    }

    @Immutable
    data class State(
        val section: Section = Section.FOR_YOU,
        val isLoading: Boolean = true,
        val failed: Boolean = false,
        val forYou: SuggestionEngine.ForYouResult? = null,
        val trendingPeriod: TrendingPeriod = TrendingPeriod.WEEK,
        val trending: Map<TrendingPeriod, SuggestionEngine.ListResult> = emptyMap(),
        val topRatedCategory: String? = null,
        val topRated: Map<String?, SuggestionEngine.ListResult> = emptyMap(),
        val selectedTag: SuggestionTag? = null,
    ) {
        /** The loaded result for whatever is on screen, or null if it hasn't been loaded yet. */
        val current: Any? = when (section) {
            Section.FOR_YOU -> forYou
            Section.TRENDING -> trending[trendingPeriod]
            Section.TOP_RATED -> topRated[topRatedCategory]
        }

        val items: List<Suggestion> = when (section) {
            Section.FOR_YOU -> forYou?.suggestions
            Section.TRENDING -> trending[trendingPeriod]?.suggestions
            Section.TOP_RATED -> topRated[topRatedCategory]?.suggestions
        }.orEmpty()

        val stop: SuggestionEngine.Stop? = when (section) {
            Section.FOR_YOU -> forYou?.stop
            Section.TRENDING -> trending[trendingPeriod]?.stop
            Section.TOP_RATED -> topRated[topRatedCategory]?.stop
        }

        /** What the shown Top rated list is based on: the user's genres, or the chosen category. */
        val topRatedBasis: List<String> = topRated[topRatedCategory]?.basis.orEmpty()

        val needsLibrary: Boolean = section == Section.TOP_RATED &&
            topRated[topRatedCategory]?.needsLibrary == true

        /** Reader categories from the user's own titles; offered as Top rated hashtags. */
        val yourCategories: List<String> = forYou?.yourCategories.orEmpty()

        /** The most common reasons, offered as quick filters above the list. */
        val tagCounts: List<Pair<SuggestionTag, Int>> = items
            .flatMap { it.tags }
            .groupingBy { it }
            .eachCount()
            .entries
            .sortedWith(compareBy<Map.Entry<SuggestionTag, Int>> { it.key.order }.thenByDescending { it.value })
            .map { it.key to it.value }

        val visibleItems: List<Suggestion> = selectedTag
            ?.let { tag -> items.filter { tag in it.tags } }
            ?: items
    }

    private companion object {
        // Each For you batch costs at most this many MangaUpdates requests, spaced by the limiter
        const val REQUESTS_PER_BATCH = 6
    }
}
