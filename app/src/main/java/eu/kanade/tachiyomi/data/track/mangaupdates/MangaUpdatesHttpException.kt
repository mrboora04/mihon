package eu.kanade.tachiyomi.data.track.mangaupdates

import okhttp3.Response
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * A non-success reply from MangaUpdates, keeping what a caller needs to back off properly.
 *
 * @property retryAfter the server's `Retry-After`, when it sent one as a number of seconds
 */
class MangaUpdatesHttpException(
    val code: Int,
    val retryAfter: Duration?,
) : IllegalStateException("HTTP error $code")

/** Returns this reply if it succeeded, otherwise closes it and throws [MangaUpdatesHttpException]. */
internal fun Response.requireSuccess(): Response {
    if (isSuccessful) return this
    val retryAfter = header("Retry-After")?.trim()?.toLongOrNull()?.takeIf { it > 0 }?.seconds
    close()
    throw MangaUpdatesHttpException(code, retryAfter)
}
