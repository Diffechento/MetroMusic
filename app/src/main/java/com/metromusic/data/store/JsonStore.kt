package com.metromusic.data.store

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * A small piece of app state kept in one JSON file.
 *
 * The library itself comes from MediaStore and is never persisted, so everything that lands
 * here — playlists, favorites, settings — is kilobytes. That makes a plain file the right
 * tool: no schema, no migrations, no code generation, and the whole thing lives in one
 * [StateFlow] the UI can collect.
 *
 * Writes are debounced and atomic (write a temp file, then rename), so reordering a playlist
 * by dragging doesn't hammer the disk and an interrupted write can't truncate the real file.
 */
class JsonStore<T : Any>(
    private val file: File,
    private val serializer: KSerializer<T>,
    defaultValue: T,
    private val scope: CoroutineScope,
    private val debounceMs: Long = 400
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val _state = MutableStateFlow(defaultValue)
    val state: StateFlow<T> = _state.asStateFlow()

    private val writeMutex = Mutex()
    private var saveJob: Job? = null

    /** Set as soon as anything is written, so a slow initial read can't clobber a fresh edit. */
    @Volatile
    private var touched = false

    init {
        scope.launch {
            val restored = read()
            if (restored != null && !touched) _state.value = restored
        }
    }

    fun update(transform: (T) -> T) {
        touched = true
        _state.update(transform)
        scheduleSave()
    }

    /** Writes immediately, waiting for it to land. For teardown paths that can't be debounced. */
    suspend fun flush() {
        saveJob?.cancelAndJoin()
        write(_state.value)
    }

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(debounceMs)
            write(_state.value)
        }
    }

    private suspend fun read(): T? = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext null
        try {
            json.decodeFromString(serializer, file.readText())
        } catch (e: Exception) {
            // A corrupt file must not brick the app; fall back to defaults and move on.
            Log.w(Tag, "Could not read ${file.name}, starting from defaults", e)
            null
        }
    }

    private suspend fun write(value: T) = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            try {
                file.parentFile?.mkdirs()
                val temp = File(file.parentFile, "${file.name}.tmp")
                temp.writeText(json.encodeToString(serializer, value))
                if (!temp.renameTo(file)) {
                    // Some filesystems refuse to rename onto an existing file.
                    file.delete()
                    temp.renameTo(file)
                }
            } catch (e: Exception) {
                Log.w(Tag, "Could not write ${file.name}", e)
            }
        }
    }

    private companion object {
        const val Tag = "JsonStore"
    }
}
