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
import eu.kanade.domain.suggestions.SuggestionTag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class SuggestionsViewModel(
    private val engine: SuggestionEngine,
) : ViewModel() {

    val state: StateFlow<State>
        field = MutableStateFlow(State())

    private var job: Job? = null

    init {
        refresh()
    }

    fun refresh() {
        job?.cancel()
        state.update { it.copy(isLoading = true, failed = false, progress = 0, progressTotal = 0) }
        job = viewModelScope.launch(Dispatchers.IO) {
            try {
                val result = engine.generate { done, total ->
                    state.update { it.copy(progress = done, progressTotal = total) }
                }
                state.update {
                    it.copy(
                        isLoading = false,
                        items = result.suggestions,
                        hasSeeds = result.seeds.isNotEmpty(),
                        sourceCount = result.sourceCount,
                    )
                }
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

    @Immutable
    data class State(
        val isLoading: Boolean = true,
        val progress: Int = 0,
        val progressTotal: Int = 0,
        val failed: Boolean = false,
        val items: List<Suggestion> = emptyList(),
        val hasSeeds: Boolean = false,
        val sourceCount: Int = 0,
        val selectedTag: SuggestionTag? = null,
    ) {
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
}
