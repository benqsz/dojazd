package app.dojazd.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.core.content.edit
import androidx.core.net.toUri
import app.dojazd.R
import app.dojazd.storage.Place
import app.dojazd.storage.PlacesCodec
import app.dojazd.storage.PlacesDocument
import app.dojazd.storage.merge
import app.dojazd.storage.with
import app.dojazd.storage.without
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import java.io.File
import java.io.IOException
import java.util.UUID

/** A file outside the app that a backup of the favourite places is kept in. */
data class PlacesBackupFile(
    val uri: Uri,
    /** Where the file is, as the user would find it: a folder path, or the app or service that holds it. */
    val path: String,
    /** False when the file could not be written the last time it was tried. */
    val reachable: Boolean = true,
)

/**
 * The places saved by the user (home, work and others), kept in `places.json`
 * in the app's private storage. They leave the device only when the user
 * exports them, or chooses a backup file (see [setBackup]): that file is then
 * written on every change. It is never read back on its own; restoring from it
 * is an import.
 */
class PlacesRepository(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    private val file = File(context.filesDir, "places.json")

    /** Where a `places.json` the app could not read is kept, rather than written over with an empty list. */
    private val unreadable = File(file.path + ".unreadable")
    private val mutex = Mutex()
    private var document = PlacesDocument()

    private val prefs = context.getSharedPreferences("places", Context.MODE_PRIVATE)

    private val _places = MutableStateFlow<List<Place>>(emptyList())
    val places: StateFlow<List<Place>> = _places.asStateFlow()

    private val _backup = MutableStateFlow<PlacesBackupFile?>(null)

    /** The file a backup of the places is kept in, or null when there is none. */
    val backup: StateFlow<PlacesBackupFile?> = _backup.asStateFlow()

    init {
        scope.launch(Dispatchers.IO) {
            mutex.withLock {
                document = load()
                _places.value = document.places
                _backup.value =
                    prefs.getString(KEY_BACKUP, null)?.let { saved ->
                        val uri = saved.toUri()
                        PlacesBackupFile(uri, describe(uri))
                    }
            }
        }
    }

    /** Keeps a backup of the places in the new file at [uri] from now on. False when the file cannot be written. */
    suspend fun setBackup(uri: Uri): Boolean =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                try {
                    context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                    writeTo(uri, document)
                    prefs.edit { putString(KEY_BACKUP, uri.toString()) }
                    _backup.value = PlacesBackupFile(uri, describe(uri))
                    true
                } catch (e: IOException) {
                    false
                } catch (e: SecurityException) {
                    false
                }
            }
        }

    /** Stops writing the backup; the file is left as it is. */
    fun removeBackup() {
        scope.launch(Dispatchers.IO) {
            mutex.withLock {
                val current = _backup.value ?: return@withLock
                try {
                    context.contentResolver.releasePersistableUriPermission(current.uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                } catch (e: SecurityException) {
                    // Already gone, for example because the file was deleted.
                }
                prefs.edit { remove(KEY_BACKUP) }
                _backup.value = null
            }
        }
    }

    private fun writeTo(
        uri: Uri,
        changed: PlacesDocument,
    ) {
        val output = context.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("Cannot open $uri")
        output.bufferedWriter().use { it.write(PlacesCodec.encode(changed)) }
    }

    /**
     * The file's place for showing to the user. Android hands out only content
     * addresses, so the folder path is worked out where the provider gives it
     * away (the device's own storage and Downloads); anything else is named by
     * the app that holds it, such as a cloud drive.
     */
    private fun describe(uri: Uri): String {
        val name = displayName(uri)
        val id =
            try {
                DocumentsContract.getDocumentId(uri)
            } catch (e: IllegalArgumentException) {
                null
            }
        return when {
            id == null -> {
                name
            }

            uri.authority == EXTERNAL_STORAGE && id.contains(':') -> {
                val (volume, path) = id.split(':', limit = 2)
                val root = if (volume == "primary") context.getString(R.string.storage_internal) else volume
                "$root/$path"
            }

            uri.authority == DOWNLOADS && id.startsWith("raw:") -> {
                id.removePrefix("raw:").removePrefix("/storage/emulated/0/")
            }

            else -> {
                val holder =
                    uri.authority
                        ?.let { context.packageManager.resolveContentProvider(it, 0) }
                        ?.loadLabel(context.packageManager)
                        ?.toString()
                if (holder != null) "$holder/$name" else name
            }
        }
    }

    private fun displayName(uri: Uri): String =
        try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        } catch (e: SecurityException) {
            null
        } ?: uri.lastPathSegment.orEmpty()

    fun newId(): String = UUID.randomUUID().toString()

    /** Adds [place], or updates the saved place with the same id. */
    fun save(place: Place) = change { it.with(place) }

    fun delete(id: String) = change { it.without(id) }

    /** Deletes every favourite place; the backup file, when there is one, is emptied too. */
    fun deleteAll() =
        change {
            // A file set aside as unreadable holds places too, and the user asked for all of them to go.
            unreadable.delete()
            it.copy(places = emptyList())
        }

    /** Writes all saved places to the document the user picked. False when it could not be written. */
    suspend fun exportTo(uri: Uri): Boolean =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                try {
                    val output = context.contentResolver.openOutputStream(uri, "wt") ?: return@withLock false
                    output.bufferedWriter().use { it.write(PlacesCodec.encode(document)) }
                    true
                } catch (e: IOException) {
                    false
                } catch (e: SecurityException) {
                    false
                }
            }
        }

    /** Adds the places from the document the user picked. Returns how many it held, or null when it is not a places file. */
    suspend fun importFrom(uri: Uri): Int? =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                try {
                    val text =
                        context.contentResolver
                            .openInputStream(uri)
                            ?.bufferedReader()
                            ?.use { reader ->
                                // A places file is a few kilobytes; anything far larger was picked by mistake.
                                val buffer = CharArray(MAX_IMPORT_CHARS + 1)
                                var length = 0
                                while (length < buffer.size) {
                                    val count = reader.read(buffer, length, buffer.size - length)
                                    if (count < 0) break
                                    length += count
                                }
                                if (length > MAX_IMPORT_CHARS) return@withLock null
                                String(buffer, 0, length)
                            }
                            ?: return@withLock null
                    val imported = PlacesCodec.decode(text)
                    store(document.merge(imported))
                    imported.places.size
                } catch (e: IOException) {
                    null
                } catch (e: SecurityException) {
                    null
                } catch (e: SerializationException) {
                    null
                } catch (e: IllegalArgumentException) {
                    null
                }
            }
        }

    private fun change(transform: (PlacesDocument) -> PlacesDocument) {
        scope.launch(Dispatchers.IO) {
            mutex.withLock {
                try {
                    store(transform(document))
                } catch (e: IOException) {
                    // The list on screen keeps showing what is really saved.
                }
            }
        }
    }

    private fun store(changed: PlacesDocument) {
        storeLocally(changed)
        val current = _backup.value ?: return
        val written =
            try {
                writeTo(current.uri, changed)
                true
            } catch (e: IOException) {
                false
            } catch (e: SecurityException) {
                false
            }
        _backup.value = current.copy(reachable = written)
    }

    private fun storeLocally(changed: PlacesDocument) {
        // Written next to the file and renamed, so a crash mid-write cannot leave half a file.
        val incoming = File(file.path + ".new")
        incoming.writeText(PlacesCodec.encode(changed))
        if (!incoming.renameTo(file)) throw IOException("Cannot store places")
        document = changed
        _places.value = changed.places
    }

    private fun load(): PlacesDocument {
        if (!file.exists()) return PlacesDocument()
        return try {
            PlacesCodec.decode(file.readText())
        } catch (e: IOException) {
            PlacesDocument()
        } catch (e: SerializationException) {
            setAsideUnreadable()
        } catch (e: IllegalArgumentException) {
            setAsideUnreadable()
        }
    }

    /** Keeps a file the app cannot read under another name instead of overwriting it with an empty list. */
    private fun setAsideUnreadable(): PlacesDocument {
        // When it cannot even be moved it stays where it is, and the next change writes over it.
        if (!file.renameTo(unreadable)) unreadable.delete()
        return PlacesDocument()
    }

    private companion object {
        const val KEY_BACKUP = "backup_file"
        const val MAX_IMPORT_CHARS = 1_000_000
        const val EXTERNAL_STORAGE = "com.android.externalstorage.documents"
        const val DOWNLOADS = "com.android.providers.downloads.documents"
    }
}
