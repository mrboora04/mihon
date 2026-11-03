package eu.kanade.domain.source.model

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList

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
