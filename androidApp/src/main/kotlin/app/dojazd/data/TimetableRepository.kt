package app.dojazd.data

import android.content.Context
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteFullException
import android.net.Uri
import androidx.core.content.edit
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import app.dojazd.gtfs.GtfsFormatException
import app.dojazd.gtfs.GtfsImporter
import app.dojazd.gtfs.Timetable
import app.dojazd.gtfs.ZipGtfsFeed
import app.dojazd.gtfs.db.GtfsDatabase
import app.dojazd.provider.CityConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.locks.ReentrantReadWriteLock
import java.util.zip.ZipException
import kotlin.concurrent.read
import kotlin.concurrent.write

/** Why a timetable could not be fetched or read, for a screen to say in the user's language. */
data class TimetableFailure(
    val kind: Kind,
    /** The HTTP status of a [Kind.SERVER] failure. */
    val status: Int? = null,
) {
    enum class Kind {
        /** The server could not be reached: no connection, or none that works. */
        NO_CONNECTION,

        /** The server answered, but not with the file. */
        SERVER,

        /** The phone has no room left for the timetable. */
        NO_SPACE,

        /** What came is not a timetable the app can read. */
        BAD_FILE,
        OTHER,
    }

    companion object {
        /** What [error], thrown while fetching or importing a timetable, means for the user. */
        fun of(error: Exception): TimetableFailure =
            when {
                error is HttpStatusException -> TimetableFailure(Kind.SERVER, error.status)

                error is SQLiteFullException || error.message.orEmpty().contains("ENOSPC") -> TimetableFailure(Kind.NO_SPACE)

                error is IOException && error.isConnectionFailure() -> TimetableFailure(Kind.NO_CONNECTION)

                error is GtfsFormatException ||
                    error is ZipException ||
                    error is TooLargeException ||
                    error is SQLiteException ||
                    error is IllegalArgumentException -> TimetableFailure(Kind.BAD_FILE)

                else -> TimetableFailure(Kind.OTHER)
            }
    }
}

/**
 * Owns the offline timetable of the active city: downloads the GTFS feed,
 * imports it into a local database and answers queries against it.
 *
 * A new feed is imported into a separate file and swapped in only once it is
 * complete, so the previous timetable stays usable during an update and a
 * failed update changes nothing.
 */
class TimetableRepository(
    private val context: Context,
    private val scope: CoroutineScope,
    private val network: Network,
    private val settings: AppSettings,
) {
    sealed interface Work {
        data object Checking : Work

        data class Downloading(
            val bytes: Long,
            val totalBytes: Long,
        ) : Work

        data class Importing(
            val stopTimes: Int,
        ) : Work
    }

    /** When the repository may contact the network on its own. A refresh asked for by the user always may. */
    data class UpdatePolicy(
        /** False when automatic refresh is off: nothing is downloaded unless the user asks. */
        val automatic: Boolean,
        /** Minimum time between automatic checks for a newer timetable. */
        val intervalSeconds: Long,
    )

    data class Status(
        val cityId: String? = null,
        /**
         * True until the stored timetable of [cityId] has been looked for. Whether there
         * is one is not known before, so a screen should not yet say that there is none.
         */
        val opening: Boolean = false,
        /** True when a timetable for [cityId] is open and can be queried. */
        val ready: Boolean = false,
        /** Increases whenever the open timetable is replaced; row ids are only valid within one version. */
        val version: Int = 0,
        val work: Work? = null,
        /** Why the last download failed; never set in offline mode. */
        val downloadError: TimetableFailure? = null,
        /** Why the last file the user picked could not be imported. */
        val importError: TimetableFailure? = null,
        /** Epoch seconds of the import of the open timetable. */
        val importedAt: Long? = null,
        /** The last day the open timetable has any service on, as a GTFS `YYYYMMDD` number. */
        val validUntil: Int? = null,
        /** Size of the open timetable on the device, in bytes. */
        val storageBytes: Long? = null,
    ) {
        /**
         * Names the open timetable across restarts of the app, unlike [version]:
         * row ids kept from an earlier run are good only while this is the same.
         */
        val stamp: String? get() = importedAt?.let { "$cityId/$it" }
    }

    private class OpenTimetable(
        val driver: AndroidSqliteDriver,
        val timetable: Timetable,
    ) {
        /** Results of [remembered], kept for as long as this timetable is open. */
        val memo = HashMap<String, Any?>()
    }

    private val prefs = context.getSharedPreferences("timetables", Context.MODE_PRIVATE)
    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    /** Serialises activation and updates; they run one at a time, in request order. */
    private val updates = Mutex()

    /** Queries hold the read side; swapping the database takes the write side. */
    private val lock = ReentrantReadWriteLock()
    private var open: OpenTimetable? = null
    private var city: CityConfig? = null
    private var policy = UpdatePolicy(automatic = false, intervalSeconds = Long.MAX_VALUE)

    init {
        // Going offline stops a running download (see Network.download), and what went wrong online no longer matters.
        scope.launch {
            settings.data
                .map { it.offline }
                .distinctUntilChanged()
                .collect { offline -> if (offline) _status.update { it.copy(downloadError = null) } }
        }
    }

    /**
     * Makes [city] the active city: opens its stored timetable and, if [policy]
     * allows, downloads or updates it. Call again to apply a changed policy.
     */
    fun activate(
        city: CityConfig,
        policy: UpdatePolicy,
    ) {
        scope.launch(Dispatchers.IO) {
            updates.withLock {
                this@TimetableRepository.policy = policy
                if (this@TimetableRepository.city?.id != city.id) {
                    lock.write {
                        open?.driver?.close()
                        open = null
                        this@TimetableRepository.city = city
                    }
                    deleteObsoleteDatabases()
                    _status.update { Status(cityId = city.id, opening = true, version = it.version + 1) }
                    val stored = context.getDatabasePath(databaseName(city)).exists() && openStored(city)
                    _status.update { it.copy(opening = false) }
                    if (!stored) restoreFromArchive(city)
                }
                update(city, force = false)
            }
        }
    }

    /** Size in bytes of the active city's feed download, or null when it cannot be determined. */
    suspend fun downloadSize(): Long? =
        withContext(Dispatchers.IO) {
            val city = city ?: return@withContext null
            if (settings.data.value.offline) return@withContext null
            try {
                network.contentLength(FetchKind.TIMETABLE_SIZE, city.gtfs.url)
            } catch (e: IOException) {
                null
            }
        }

    /** Downloads or updates the timetable now, at the user's request, whatever the policy says; never in offline mode. */
    fun refresh() {
        val city = city ?: return
        if (settings.data.value.offline) return
        scope.launch(Dispatchers.IO) { updates.withLock { update(city, force = true) } }
    }

    /**
     * Imports the timetable from a GTFS archive the user picked, for use without
     * any network. It replaces the open timetable the same way a download does.
     */
    fun importFile(uri: Uri) {
        val city = city ?: return
        scope.launch(Dispatchers.IO) {
            updates.withLock {
                _status.update { it.copy(work = Work.Checking, importError = null) }
                val archive = File(context.cacheDir, "gtfs-${city.id}.zip")
                val failure =
                    attempt {
                        val input = context.contentResolver.openInputStream(uri) ?: throw IOException("Cannot open the file")
                        input.use { source -> archive.outputStream().use { source.copyTo(it) } }
                        importAndSwap(city, archive, etag = null)
                    }
                archive.delete()
                // A timetable from a file makes any failed download beside the point.
                _status.update {
                    if (failure ==
                        null
                    ) {
                        it.copy(work = null, downloadError = null)
                    } else {
                        it.copy(work = null, importError = failure)
                    }
                }
            }
        }
    }

    /** Runs [block] against the open timetable on a background thread; null when none is open. */
    suspend fun <T> query(block: (Timetable) -> T): T? =
        withContext(Dispatchers.IO) {
            lock.read { open?.timetable?.let(block) }
        }

    /**
     * Like [query], but the result is kept under [key] until the timetable is
     * replaced, so that what several screens need is worked out once.
     */
    suspend fun <T> remembered(
        key: String,
        block: (Timetable) -> T,
    ): T? =
        withContext(Dispatchers.IO) {
            lock.read {
                val current = open ?: return@read null
                synchronized(current.memo) {
                    if (key in current.memo) {
                        @Suppress("UNCHECKED_CAST")
                        return@read current.memo[key] as T
                    }
                }
                block(current.timetable).also { result -> synchronized(current.memo) { current.memo[key] = result } }
            }
        }

    private fun update(
        city: CityConfig,
        force: Boolean,
    ) {
        // Offline, nothing is downloaded, not even at the user's request.
        if (settings.data.value.offline) return
        val hasTimetable = lock.read { open != null }
        if (!force) {
            if (!policy.automatic) return
            val lastCheck = prefs.getLong(lastCheckKey(city), 0)
            if (hasTimetable && now() - lastCheck < policy.intervalSeconds) return
            // After a failure the next try waits, longer each time: a server in trouble is not asked every minute.
            val failures = prefs.getInt(failuresKey(city), 0)
            if (failures > 0 && now() - prefs.getLong(lastFailureKey(city), 0) < retryDelaySeconds(failures)) return
        }

        _status.update { it.copy(work = Work.Checking, downloadError = null) }
        val archive = File(context.cacheDir, "gtfs-${city.id}.zip")
        val failure =
            attempt {
                val etag = if (hasTimetable) lock.read { open?.timetable?.meta(Timetable.META_ETAG) } else null
                val result =
                    network.download(FetchKind.TIMETABLE, city.gtfs.url, archive, etag) { bytes, total ->
                        _status.update { it.copy(work = Work.Downloading(bytes, total)) }
                    }
                if (result is DownloadResult.Downloaded) {
                    importAndSwap(city, archive, result.etag)
                }
            }
        archive.delete()
        when {
            failure == null -> {
                prefs.edit {
                    putLong(lastCheckKey(city), now())
                    remove(failuresKey(city))
                    remove(lastFailureKey(city))
                }
                _status.update { it.copy(work = null) }
            }

            // Offline mode was switched on meanwhile: stopping is what was asked for, not a failure.
            settings.data.value.offline -> {
                _status.update { it.copy(work = null) }
            }

            else -> {
                prefs.edit {
                    putInt(failuresKey(city), prefs.getInt(failuresKey(city), 0) + 1)
                    putLong(lastFailureKey(city), now())
                }
                _status.update { it.copy(work = null, downloadError = failure) }
            }
        }
    }

    /**
     * Runs [block], which fetches or imports a timetable, and tells what went
     * wrong when something did. Everything such work can fail with ends here,
     * whichever way the timetable came, so that none of it can stop the app.
     */
    private inline fun attempt(block: () -> Unit): TimetableFailure? =
        try {
            block()
            null
        } catch (e: IOException) {
            TimetableFailure.of(e)
        } catch (e: SecurityException) {
            TimetableFailure.of(e)
        } catch (e: GtfsFormatException) {
            TimetableFailure.of(e)
        } catch (e: SQLiteException) {
            TimetableFailure.of(e)
        } catch (e: IllegalArgumentException) {
            // A file that is not a ZIP archive at all.
            TimetableFailure.of(e)
        }

    /**
     * Imports the archive kept from the last import again, when the database made
     * from it is gone: after an update of the app that stores timetables another
     * way, this brings the timetable back without the network, offline mode included.
     */
    private fun restoreFromArchive(city: CityConfig) {
        val kept = keptArchive(city)
        if (!kept.exists()) return
        val failure = attempt { importAndSwap(city, kept, prefs.getString(archiveEtagKey(city), null)) }
        _status.update { it.copy(work = null) }
        // An archive that cannot be imported any more is of no further use.
        if (failure != null) kept.delete()
    }

    private fun importAndSwap(
        city: CityConfig,
        archive: File,
        etag: String?,
    ) {
        val finalName = databaseName(city)
        val importName = finalName + IMPORT_SUFFIX
        context.deleteDatabase(importName)

        _status.update { it.copy(work = Work.Importing(0)) }
        val driver = AndroidSqliteDriver(GtfsDatabase.Schema, context, importName)
        try {
            val meta =
                buildMap {
                    put(Timetable.META_IMPORTED_AT, now().toString())
                    if (etag != null) put(Timetable.META_ETAG, etag)
                }
            // With the driver, the importer writes in batches and builds the indexes once, at the end.
            GtfsImporter(GtfsDatabase(driver), driver).import(ZipGtfsFeed(archive), meta) { stopTimes ->
                _status.update { it.copy(work = Work.Importing(stopTimes)) }
            }
            // Packs the file tight, now that nothing in it will change again.
            driver.execute(null, "VACUUM", 0)
        } finally {
            driver.close()
        }
        // The import wrote without waiting for the disk; make sure it is all there before it replaces anything.
        RandomAccessFile(context.getDatabasePath(importName), "rw").use { it.fd.sync() }

        lock.write {
            open?.driver?.close()
            open = null
            context.deleteDatabase(finalName)
            val imported = context.getDatabasePath(importName)
            if (!imported.renameTo(context.getDatabasePath(finalName))) {
                throw IOException("Cannot replace timetable database")
            }
        }
        // Journal files of the import database are meaningless after the rename.
        context.deleteDatabase(importName)
        if (!openStored(city)) throw GtfsFormatException("The imported timetable cannot be read")
        keep(city, archive, etag)
    }

    /** Keeps [archive] for [restoreFromArchive]. Without it only the way back after an app update is lost. */
    private fun keep(
        city: CityConfig,
        archive: File,
        etag: String?,
    ) {
        val kept = keptArchive(city)
        if (archive != kept) {
            try {
                kept.parentFile?.mkdirs()
                archive.copyTo(kept, overwrite = true)
            } catch (e: IOException) {
                kept.delete()
            }
        }
        prefs.edit { if (etag == null) remove(archiveEtagKey(city)) else putString(archiveEtagKey(city), etag) }
    }

    /**
     * Opens the stored timetable of [city]. False, with the file removed, when it
     * is not a whole timetable: damaged, or left empty by a write that never reached the disk.
     */
    private fun openStored(city: CityConfig): Boolean {
        val name = databaseName(city)
        val driver = AndroidSqliteDriver(GtfsDatabase.Schema, context, name, windowSizeBytes = CURSOR_WINDOW_BYTES)
        val timetable = Timetable(GtfsDatabase(driver))
        val importedAt: Long?
        val validUntil: Int?
        try {
            importedAt = timetable.meta(Timetable.META_IMPORTED_AT)?.toLongOrNull()
            validUntil = timetable.lastServiceDate()
        } catch (e: SQLiteException) {
            driver.close()
            context.deleteDatabase(name)
            return false
        }
        if (importedAt == null) {
            driver.close()
            context.deleteDatabase(name)
            return false
        }
        lock.write { open = OpenTimetable(driver, timetable) }
        val storageBytes = context.getDatabasePath(name).length()
        _status.update {
            it.copy(
                ready = true,
                version = it.version + 1,
                importedAt = importedAt,
                validUntil = validUntil,
                storageBytes = storageBytes,
            )
        }
        return true
    }

    /** Removes interrupted imports and databases written by an older schema. */
    private fun deleteObsoleteDatabases() {
        val currentSuffix = "-f${Timetable.DB_FORMAT}.db"
        context
            .databaseList()
            .filter { it.startsWith(DATABASE_PREFIX) }
            // databaseList() also returns SQLite's side files; reduce them to the database they belong to.
            .map { it.removeSuffix("-journal").removeSuffix("-wal").removeSuffix("-shm") }
            .distinct()
            .filter { !it.endsWith(currentSuffix) }
            .forEach { context.deleteDatabase(it) }
    }

    private fun databaseName(city: CityConfig) = "$DATABASE_PREFIX${city.id}-f${Timetable.DB_FORMAT}.db"

    private fun keptArchive(city: CityConfig) = File(context.filesDir, "timetables/${city.id}.zip")

    private fun lastCheckKey(city: CityConfig) = "last_check_${city.id}"

    private fun failuresKey(city: CityConfig) = "failures_${city.id}"

    private fun lastFailureKey(city: CityConfig) = "last_failure_${city.id}"

    private fun archiveEtagKey(city: CityConfig) = "archive_etag_${city.id}"

    /** A minute after the first failure in a row, then twice as long each time, up to an hour. */
    private fun retryDelaySeconds(failures: Int): Long = minOf(60L shl minOf(failures - 1, 6), MAX_RETRY_DELAY_SECONDS)

    private fun now() = System.currentTimeMillis() / 1000

    private companion object {
        const val DATABASE_PREFIX = "gtfs-"
        const val IMPORT_SUFFIX = ".import"
        const val MAX_RETRY_DELAY_SECONDS = 3600L

        /**
         * Room for a whole service day of stop times in one go. Android reads a
         * result in windows of 2 MB by default and runs the query again for each,
         * which makes the planner's largest read several times slower.
         */
        const val CURSOR_WINDOW_BYTES = 16L * 1024 * 1024
    }
}
