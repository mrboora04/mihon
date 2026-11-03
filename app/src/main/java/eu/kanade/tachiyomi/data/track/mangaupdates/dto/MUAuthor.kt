package eu.kanade.tachiyomi.data.track.mangaupdates.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class MUAuthor(
    val name: String,
    val type: String,
    @SerialName("author_id")
    val authorId: Long? = null,
)
