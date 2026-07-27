package com.metromusic.data.store

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import java.io.File

/**
 * Genres the user has said are the same genre.
 *
 * Keyed by the *normalised* spelling of what was merged, so merging "Electro." into "Electronic" also
 * catches the "electro ." that turns up two albums later. The value is the target's name exactly as it
 * should be displayed.
 */
@Serializable
data class GenreMerges(val aliases: Map<String, String> = emptyMap()) {
    val isEmpty: Boolean get() = aliases.isEmpty()
}

class GenreStore(context: Context, scope: CoroutineScope) {

    private val store = JsonStore(
        file = File(context.filesDir, "genres.json"),
        serializer = GenreMerges.serializer(),
        defaultValue = GenreMerges(),
        scope = scope
    )

    val merges: StateFlow<GenreMerges> = store.state

    /**
     * Files [from] under [into].
     *
     * Anything already pointing at [from] is repointed, so merging A into B and then B into C leaves
     * A under C rather than under a name that no longer appears anywhere.
     */
    fun merge(from: String, into: String) = store.update { current ->
        val source = normalizeGenre(from)
        val target = into.trim()
        if (source.isEmpty() || target.isEmpty() || normalizeGenre(target) == source) {
            current
        } else {
            val repointed = current.aliases.mapValues { (_, value) ->
                if (normalizeGenre(value) == source) target else value
            }
            GenreMerges(repointed + (source to target))
        }
    }

    fun unmerge(from: String) = store.update {
        GenreMerges(it.aliases - normalizeGenre(from))
    }

    fun clear() = store.update { GenreMerges() }

    suspend fun flush() = store.flush()
}

/**
 * The spelling two genre tags have in common when they are only typed differently.
 *
 * Case, surrounding space, and the punctuation people leave on the end of a tag ("Electro.", "Rock!",
 * "Hip-Hop;") — those are the differences that produce three entries for one genre in a library
 * scraped from real files. Inner punctuation is left alone: "hip-hop" and "hip hop" *are* spelled
 * differently and merging them is a judgement, which is what the manual merge is for.
 */
fun normalizeGenre(raw: String): String = raw
    .trim()
    .lowercase()
    .trim { !it.isLetterOrDigit() }
    .replace(Regex("""\s+"""), " ")
