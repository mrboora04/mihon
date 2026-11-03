package eu.kanade.tachiyomi.data.track.mangaupdates.dto

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive

/**
 * Body for `POST /v1/series/search`. Null fields are left out of the request.
 *
 * @property filters e.g. `completed`, `scanlated`
 * @property orderby e.g. `rating`, `week_pos`, `month1_pos`, `month3_pos`
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class MUSeriesSearchRequest(
    val search: String? = null,
    val genre: List<String>? = null,
    val category: List<String>? = null,
    val filters: List<String>? = null,
    // Always sent: Mihon's Json leaves out fields still at their default value
    @EncodeDefault
    @SerialName("filter_types")
    val filterTypes: List<String> = listOf("novel", "drama cd"),
    val orderby: String? = null,
    val perpage: Int? = null,
)

/** Popularity positions for a series; lower is more popular. */
@Serializable
data class MURank(
    val position: MURankPositions? = null,
    @SerialName("old_position")
    val oldPosition: MURankPositions? = null,
)

@Serializable
data class MURankPositions(
    val week: Int? = null,
    val month: Int? = null,
    @SerialName("three_months")
    val threeMonths: Int? = null,
)

/** A reader-voted category ("Calm Protagonist", "Travel/Journey", …) on a series. */
@Serializable
data class MUCategory(
    val category: String? = null,
    val votes: Int? = null,
)

@Serializable
data class MUAuthorSeriesList(
    @SerialName("series_list")
    val seriesList: List<MUAuthorSeries>? = null,
)

@Serializable
data class MUAuthorSeries(
    @SerialName("series_id")
    val seriesId: Long? = null,
    val title: String? = null,
    val url: String? = null,
    // Sent as a number here, unlike the string in series details
    val year: JsonPrimitive? = null,
    val genres: List<String>? = null,
)
