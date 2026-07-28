package com.metromusic.data.lastfm

import android.util.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest

/**
 * The slice of the Last.fm 2.0 API this app needs: get a request token, turn an authorised token
 * into a session, announce what is playing, and submit plays.
 *
 * **Credentials.** Every call is signed with an application key and secret. Those identify
 * MetroMusic, not the person using it, and cannot live in a public repository, so they come from
 * settings — filled in at build time from `local.properties` if it has them, or pasted into the
 * Last.fm settings page. Without them [configured] is false and the whole feature is inert rather
 * than half-working.
 *
 * **Signing.** Last.fm wants the parameters sorted by name, concatenated as name-then-value with no
 * separators, the shared secret appended, and the MD5 of that sent as `api_sig`. `format` and
 * `callback` are excluded from the signature — including them is the classic reason a request comes
 * back "invalid method signature".
 *
 * Everything here blocks; call it from IO.
 */
class LastFmClient(private val apiKey: String?, private val apiSecret: String?) {

    val configured: Boolean get() = !apiKey.isNullOrBlank() && !apiSecret.isNullOrBlank()

    /** Step one of the browser flow: a token the user then authorises on last.fm. */
    fun requestToken(): String? {
        val body = call(mapOf("method" to "auth.getToken"), signed = true, post = false).bodyOrNull
            ?: return null
        return body.string("token")
    }

    /** The page the user has to visit to authorise [token], logging in with their own credentials. */
    fun authorizeUrl(token: String): String =
        "https://www.last.fm/api/auth/?api_key=$apiKey&token=$token"

    /**
     * Step two: exchange an authorised token for a session key that does not expire.
     *
     * Returns null while the user has not authorised the token yet, which is what makes it safe to
     * call this on a timer with the sign-in page open.
     */
    fun session(token: String): Session? {
        val body = call(
            mapOf("method" to "auth.getSession", "token" to token),
            signed = true,
            post = false
        ).bodyOrNull ?: return null
        val session = runCatching {
            Json.parseToJsonElement(body).jsonObject["session"]?.jsonObject
        }.getOrNull() ?: return null
        val name = session["name"]?.jsonPrimitive?.content ?: return null
        val key = session["key"]?.jsonPrimitive?.content ?: return null
        return Session(name, key)
    }

    fun updateNowPlaying(sessionKey: String, scrobble: PendingScrobble): Boolean =
        call(
            parameters = mapOf(
                "method" to "track.updateNowPlaying",
                "artist" to scrobble.artist,
                "track" to scrobble.title,
                "album" to scrobble.album,
                "duration" to (scrobble.durationMs / 1000).toString(),
                "sk" to sessionKey
            ).filterValues { it.isNotBlank() },
            signed = true,
            post = true
        ).bodyOrNull != null

    /**
     * Submits up to fifty plays in one request — the API's own batch limit, and the reason the
     * offline queue is worth having: a day's listening goes up in a handful of calls.
     *
     * The three answers are the three things a queue can do about them, which is why this does not
     * return a boolean: [Submission.Sent] drops the batch, [Submission.Retry] keeps it and comes back
     * later, and [Submission.Refused] means coming back later will not help. A queue that treats
     * every failure as "try again" spins forever against a suspended key; one that treats every
     * failure as final throws away a tunnel's worth of listening.
     */
    fun scrobble(sessionKey: String, plays: List<PendingScrobble>): Submission {
        if (plays.isEmpty()) return Submission.Sent
        val parameters = mutableMapOf<String, String>(
            "method" to "track.scrobble",
            "sk" to sessionKey
        )
        plays.take(BatchLimit).forEachIndexed { i, play ->
            parameters["artist[$i]"] = play.artist
            parameters["track[$i]"] = play.title
            parameters["timestamp[$i]"] = play.timestampSeconds.toString()
            if (play.album.isNotBlank()) parameters["album[$i]"] = play.album
            if (play.durationMs > 0) {
                parameters["duration[$i]"] = (play.durationMs / 1000).toString()
            }
        }
        return when (val answer = call(parameters, signed = true, post = true)) {
            is Answer.Body -> Submission.Sent
            Answer.Absent -> Submission.Refused
            is Answer.Failed -> when {
                answer.transient -> Submission.Retry
                answer.unauthorised -> Submission.Unauthorised
                else -> Submission.Refused
            }
        }
    }

    /**
     * Loves or un-loves one track, and answers in the same four shapes a scrobble does — for the same
     * reason: the queue behind this has to know whether to keep the item, drop it, or stop and ask the
     * user to sign in again. [Submission.Refused] covers a track Last.fm does not have, which is a
     * permanent no and is why "no such track" is not retried forever.
     */
    fun love(sessionKey: String, artist: String, title: String, loved: Boolean): Submission {
        val answer = call(
            parameters = mapOf(
                "method" to if (loved) "track.love" else "track.unlove",
                "artist" to artist,
                "track" to title,
                "sk" to sessionKey
            ),
            signed = true,
            post = true
        )
        return when (answer) {
            is Answer.Body -> Submission.Sent
            Answer.Absent -> Submission.Refused
            is Answer.Failed -> when {
                answer.transient -> Submission.Retry
                answer.unauthorised -> Submission.Unauthorised
                else -> Submission.Refused
            }
        }
    }

    /**
     * Every track [user] has loved, as artist-and-title pairs.
     *
     * An unsigned read like the cover lookup — a profile's loved tracks are public — so this works
     * with the api key alone and does not need the session. Null means the question could not be
     * asked, and the caller must treat that as "no information" rather than "nothing is loved":
     * reading an empty list out of a failed request would un-favourite the user's whole library.
     *
     * Paged, because a long-standing account has thousands. [LovePageLimit] pages is the ceiling —
     * 200 at a time, so 10 000 loves — and hitting it means the tail is not seen this round rather
     * than the sync failing.
     */
    fun lovedTracks(user: String): List<Loved>? {
        val all = ArrayList<Loved>()
        var page = 1
        while (page <= LovePageLimit) {
            val body = when (
                val answer = call(
                    parameters = mapOf(
                        "method" to "user.getLovedTracks",
                        "user" to user,
                        "limit" to LovePageSize.toString(),
                        "page" to page.toString()
                    ),
                    signed = false,
                    post = false
                )
            ) {
                is Answer.Body -> answer.text
                // No such user, or the account has nothing loved: an answer, and an empty one.
                Answer.Absent -> return all
                is Answer.Failed -> return null
            }
            val root = runCatching {
                Json.parseToJsonElement(body).jsonObject["lovedtracks"]?.jsonObject
            }.getOrNull() ?: return all
            val tracks = runCatching { root["track"]?.jsonArray }.getOrNull().orEmpty()
            tracks.forEach { element ->
                val track = element.jsonObject
                val title = track["name"]?.jsonPrimitive?.content
                val artist = track["artist"]?.jsonObject?.get("name")?.jsonPrimitive?.content
                if (!title.isNullOrBlank() && !artist.isNullOrBlank()) all += Loved(artist, title)
            }
            val total = runCatching {
                root["@attr"]?.jsonObject?.get("totalPages")?.jsonPrimitive?.content?.toInt()
            }.getOrNull() ?: 1
            if (page >= total || tracks.isEmpty()) return all
            page++
        }
        Log.d(Tag, "stopped reading loved tracks at page $LovePageLimit")
        return all
    }

    /** One loved track as Last.fm spells it. */
    data class Loved(val artist: String, val title: String)

    /** What became of a batch of plays; see [scrobble]. */
    enum class Submission {
        /** Last.fm has them. */
        Sent,

        /** Nobody was listening — no network, a 500, "operation failed". The batch stands. */
        Retry,

        /** The session key is no longer good, so the account has to be signed in to again. */
        Unauthorised,

        /** Refused for a reason that repeating the request will not change. */
        Refused
    }

    /**
     * The cover art Last.fm has for an album, in the sizes it offers, smallest first.
     *
     * An unsigned read, so it works with a key alone — there is no user and nothing to sign for. The
     * two answers it can give are kept apart on purpose: an empty list means Last.fm was asked and has
     * no cover for this album, while **null means the question could not be asked** — no network, a
     * captive portal, a 500 from the other end. Writing "no cover" against an album because a hotel
     * wifi intercepted the request is the lyrics probe's mistake, and it is permanent.
     */
    fun albumArt(artist: String, album: String): List<String>? = artOf(
        mapOf(
            "method" to "album.getinfo",
            "artist" to artist,
            "album" to album,
            "autocorrect" to "1"
        ),
        path = listOf("album", "image")
    )

    /**
     * The same, asked through one of the album's tracks.
     *
     * Worth a second question because the album tag is what is most often wrong or missing in a file,
     * while the artist and title are usually right: `track.getinfo` answers with the album it thinks
     * the track is on, cover included.
     */
    fun trackAlbumArt(artist: String, title: String): List<String>? = artOf(
        mapOf(
            "method" to "track.getinfo",
            "artist" to artist,
            "track" to title,
            "autocorrect" to "1"
        ),
        path = listOf("track", "album", "image")
    )

    /** Image urls out of an `image` array somewhere in the answer; see [albumArt] for the contract. */
    private fun artOf(parameters: Map<String, String>, path: List<String>): List<String>? {
        val body = when (val answer = call(parameters, signed = false, post = false)) {
            is Answer.Body -> answer.text
            Answer.Absent -> return emptyList()
            is Answer.Failed -> return null
        }
        return runCatching {
            var element: JsonElement = Json.parseToJsonElement(body)
            for (field in path.dropLast(1)) {
                element = element.jsonObject[field] ?: return emptyList()
            }
            val images = element.jsonObject[path.last()]?.jsonArray ?: return emptyList()
            images.mapNotNull { it.jsonObject["#text"]?.jsonPrimitive?.content }
                .filter { it.isNotBlank() && Placeholder !in it }
        }.getOrDefault(emptyList())
    }

    /**
     * What the API said, in the three shapes a caller can act on.
     *
     * The distinction that matters is between [Absent] and [Failed]: "there is no such album" is an
     * answer worth remembering forever, while "the request did not get through" must never be written
     * down as one. Both used to be a null and both cover art and scrobbling were the poorer for it.
     */
    private sealed interface Answer {
        data class Body(val text: String) : Answer

        /** Asked, and there is nothing: error 6 or 7, or a 404. */
        data object Absent : Answer

        /**
         * Not asked, or refused. [code] is Last.fm's own error number when it gave one and
         * [status] the HTTP status when the transport got that far.
         */
        data class Failed(val code: Int?, val status: Int?) : Answer {
            /** True when the same request stands a chance later: their words, not our guess. */
            val transient: Boolean
                get() = if (code != null) code in Transient else status == null || status >= 500

            /** The session key is dead; nothing improves until the user signs in again. */
            val unauthorised: Boolean get() = code in Unauthorised
        }

        /** The body, or null for anything that is not one — for callers with nothing else to do. */
        val bodyOrNull: String? get() = (this as? Body)?.text
    }

    private fun call(
        parameters: Map<String, String>,
        signed: Boolean,
        post: Boolean
    ): Answer {
        val key = apiKey ?: return Answer.Failed(null, null)
        val secret = if (signed) apiSecret ?: return Answer.Failed(null, null) else null

        val all = parameters + ("api_key" to key)
        val withSignature =
            if (signed && secret != null) all + ("api_sig" to sign(all, secret)) else all
        val form = (withSignature + ("format" to "json")).entries.joinToString("&") { (name, value) ->
            "${encode(name)}=${encode(value)}"
        }

        var connection: HttpURLConnection? = null
        return try {
            val url = if (post) Endpoint else "$Endpoint?$form"
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = TimeoutMs
                readTimeout = TimeoutMs
                setRequestProperty("User-Agent", "MetroMusic/1.0")
                if (post) {
                    requestMethod = "POST"
                    doOutput = true
                    setRequestProperty(
                        "Content-Type",
                        "application/x-www-form-urlencoded; charset=UTF-8"
                    )
                    outputStream.use { it.write(form.toByteArray()) }
                } else {
                    requestMethod = "GET"
                }
            }
            val code = connection.responseCode
            val body = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }
            val error = body?.let(::errorCode)
            when {
                code in 200..299 && error == null -> Answer.Body(body.orEmpty())
                // "No such album", "no such track" — an answer, not a failure. Error 8 ("operation
                // failed") deliberately is not in that set: it is Last.fm asking to be asked again.
                error in NotFound || code == HttpURLConnection.HTTP_NOT_FOUND -> Answer.Absent
                else -> {
                    // Authorisation-pending (14) is expected while the sign-in page is open, so this
                    // is debug rather than a warning; anything else is worth seeing in a log.
                    Log.d(Tag, "HTTP $code (error $error): ${body?.take(200)}")
                    Answer.Failed(error, code)
                }
            }
        } catch (e: IOException) {
            // No network, a captive portal, a timeout: the request never reached Last.fm, which is
            // the case the offline queue exists for.
            Log.d(Tag, "call failed", e)
            Answer.Failed(null, null)
        } finally {
            connection?.disconnect()
        }
    }

    data class Session(val user: String, val key: String)

    private companion object {
        const val Tag = "LastFmClient"
        const val Endpoint = "https://ws.audioscrobbler.com/2.0/"
        const val TimeoutMs = 12_000
        const val BatchLimit = 50

        /** `user.getLovedTracks` paging: 200 at a time, and 10 000 loves is where reading stops. */
        const val LovePageSize = 200
        const val LovePageLimit = 50

        /** Error codes that mean "there is no such thing", as opposed to "not right now". */
        val NotFound = setOf(6, 7)

        /**
         * Last.fm's own "ask again": operation failed, service offline, temporarily unavailable, and
         * rate limit exceeded. Anything else with an error number is the request being wrong, and
         * repeating a wrong request is how a queue jams.
         */
        val Transient = setOf(8, 11, 16, 29)

        /** Authentication failed, invalid session key, token problems — sign in again. */
        val Unauthorised = setOf(4, 9, 14, 15)

        /** Last.fm's own error number, if the body carries one. */
        fun errorCode(body: String): Int? = runCatching {
            Json.parseToJsonElement(body).jsonObject["error"]?.jsonPrimitive?.content?.toInt()
        }.getOrNull()

        /**
         * The star Last.fm serves when it has no image, which is worse than no image: shown as a
         * cover it looks like a cover, and the album stops asking.
         */
        const val Placeholder = "2a96cbd8b46e442fc41c2b86b821562f"

        fun sign(parameters: Map<String, String>, secret: String): String {
            val payload = parameters.entries
                .sortedBy { it.key }
                .joinToString("") { "${it.key}${it.value}" } + secret
            return MessageDigest.getInstance("MD5")
                .digest(payload.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        }

        fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

        fun String.string(field: String): String? = runCatching {
            Json.parseToJsonElement(this).jsonObject[field]?.jsonPrimitive?.content
        }.getOrNull()
    }
}
