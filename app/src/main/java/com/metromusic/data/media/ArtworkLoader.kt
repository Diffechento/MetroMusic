package com.metromusic.data.media

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.Collections

/**
 * Album art, decoded small and cached with a hard ceiling in bytes.
 *
 * This is the only part of the app that can realistically run away with memory: a single
 * full-size cover is around a megabyte, so a hundred of them would be a gigabyte. Two rules
 * keep it bounded — never decode larger than the view that asked, and cap the cache by
 * allocated bytes rather than by entry count.
 */
class ArtworkLoader(context: Context) {

    private val resolver = context.contentResolver

    /** An eighth of the heap, and never more than 12 MB — roughly 75 covers at 200x200. */
    private val maxBytes: Int =
        (Runtime.getRuntime().maxMemory() / 8).coerceAtMost(MaxCacheBytes).toInt()

    private val cache = object : LruCache<String, Bitmap>(maxBytes) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }

    /**
     * Albums we already know have no art *in their files*. Without this, every scroll past a
     * coverless album pays for a failed decode again. Ids only, so the memory cost is negligible.
     */
    private val misses = Collections.synchronizedSet(mutableSetOf<Long>())

    /**
     * The two halves of the online fallback, filled in by the composition root rather than passed to
     * the constructor: [online] is built alongside this ([com.metromusic.core.Services]) and
     * [albumNames] can only come from the library, which is built on top of *this*. A lookup rather
     * than an argument on [load] so that everything that draws a cover — tiles, the artist page, the
     * strip, the player's backdrop — gets one without every call site learning about it.
     */
    var online: OnlineArtwork? = null

    var albumNames: ((Long) -> CoverQuery?)? = null

    /**
     * MediaStore's own album id for one of ours, for the legacy `albumart` table — the one URI here
     * that has to speak the store's language rather than the library's (album ids are derived from
     * the tags, not taken from the store — see [com.metromusic.data.model.albumIdOf]). Filled in the
     * same way [albumNames] is; a null answer falls back to the id as given, which for an album with
     * no album tag *is* the store's own.
     */
    var mediaAlbumId: ((Long) -> Long?)? = null

    /** Cached synchronously if present — lets a scrolling list draw without a frame of blank. */
    fun peek(albumId: Long, sizePx: Int): Bitmap? = cache.get(key(albumId, bucket(sizePx)))

    suspend fun load(albumId: Long, trackId: Long, sizePx: Int): Bitmap? {
        if (albumId <= 0L) return null
        val bucketed = bucket(sizePx)
        val cacheKey = key(albumId, bucketed)
        cache.get(cacheKey)?.let { return it }

        // A known miss skips the decode but not the fallback: the cover may have arrived since, or the
        // setting may have been turned on since, and neither is a reason to re-read the file.
        val embedded = if (albumId in misses) {
            null
        } else {
            withContext(Dispatchers.IO) { decode(albumId, trackId, bucketed) }
                .also { if (it == null) misses += albumId }
        }

        val bitmap = embedded ?: fromNetwork(albumId, bucketed) ?: return null
        cache.put(cacheKey, bitmap)
        return bitmap
    }

    /**
     * The cover Last.fm had, if the file had none and the setting says to ask.
     *
     * Nothing is remembered here: the answer, and the fact that there is no answer, are
     * [OnlineArtwork]'s to keep — it is the half that survives the process, and it keeps them under
     * the album's *name* so that a rescan handing out new ids does not throw them away.
     */
    private suspend fun fromNetwork(albumId: Long, size: Int): Bitmap? {
        val source = online ?: return null
        val query = albumNames?.invoke(albumId) ?: return null
        val file = source.cover(query) ?: return null
        return withContext(Dispatchers.IO) { decodeFile(file, size) }
    }

    /** Drops everything — used when the library is rescanned and ids may have shifted. */
    fun clear() {
        cache.evictAll()
        misses.clear()
    }

    private fun decode(albumId: Long, trackId: Long, size: Int): Bitmap? {
        val storeId = mediaAlbumId?.invoke(albumId) ?: albumId
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            decodeViaThumbnail(trackId, size) ?: decodeViaAlbumArtUri(storeId, size)
        } else {
            decodeViaAlbumArtUri(storeId, size)
        }
    }

    /**
     * The supported path on API 29+: MediaStore returns an already-downscaled thumbnail, so
     * the full-size image never reaches our heap.
     */
    private fun decodeViaThumbnail(trackId: Long, size: Int): Bitmap? = try {
        val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, trackId)
        resolver.loadThumbnail(uri, Size(size, size), null)
    } catch (e: IOException) {
        null
    } catch (e: SecurityException) {
        null
    }

    /**
     * The legacy album-art table. Still the only option below API 29, and a useful fallback
     * above it for files whose thumbnail generation fails.
     */
    private fun decodeViaAlbumArtUri(albumId: Long, size: Int): Bitmap? {
        val uri = ContentUris.withAppendedId(AlbumArtUri, albumId)
        return try {
            // Pass one: read the dimensions only, so we can pick a sample size.
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            // Pass two: decode at the smallest power-of-two scale that still covers the target.
            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, size)
            }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        } catch (e: IOException) {
            null
        } catch (e: SecurityException) {
            null
        }
    }

    /** A downloaded cover, decoded by the same two passes and to the same ceiling as an embedded one. */
    private fun decodeFile(file: File, size: Int): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            null
        } else {
            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, size)
            }
            BitmapFactory.decodeFile(file.path, options)
        }
    } catch (e: OutOfMemoryError) {
        null
    }

    private fun sampleSize(width: Int, height: Int, target: Int): Int {
        var sample = 1
        var w = width
        var h = height
        while (w / 2 >= target && h / 2 >= target) {
            w /= 2
            h /= 2
            sample *= 2
        }
        return sample
    }

    /**
     * Rounds a requested size up to a small set of buckets, so a 96dp row and a 110dp row
     * share one cached bitmap instead of decoding the same cover twice.
     */
    private fun bucket(sizePx: Int): Int = Buckets.firstOrNull { it >= sizePx } ?: Buckets.last()

    private fun key(albumId: Long, bucket: Int) = "$albumId@$bucket"

    private companion object {
        val AlbumArtUri: Uri = Uri.parse("content://media/external/audio/albumart")
        val Buckets = intArrayOf(128, 256, 512, 1024)
        const val MaxCacheBytes = 12L * 1024 * 1024
    }
}
