package eu.kanade.tachiyomi.data.track.mangaupdates.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * An entry in a series' `recommendations` (picked by readers) or
 * `category_recommendations` (similar categories) list.
 */
@Serializable
data class MURecommendation(
    @SerialName("series_name")
    val seriesName: String? = null,
    @SerialName("series_url")
    val seriesUrl: String? = null,
    @SerialName("series_id")
    val seriesId: Long? = null,
    @SerialName("series_image")
    val seriesImage: MUImage? = null,
    val weight: Int? = null,
)

@Serializable
data class MUGenre(
    val genre: String? = null,
)
