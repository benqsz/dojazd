package app.dojazd.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Transient
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URISyntaxException
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLException

/** What a request to the network was for. */
@Serializable
enum class FetchKind { TIMETABLE, TIMETABLE_SIZE, TRIP_UPDATES, VEHICLES, ALERTS }

/** One request the app sent to the network. */
@Serializable
data class FetchLogEntry(
    /** When it started, in epoch milliseconds. */
    val at: Long,
    val kind: FetchKind,
    val url: String,
    val outcome: Outcome,
    /** The HTTP status, when the server answered. */
    val status: Int? = null,
    /** How much was received, when anything was. */
    val bytes: Long? = null,
    /** How long it took. */
    val millis: Long? = null,
    val error: String? = null,
    /** Tells entries apart while the app runs; not kept in the file. */
    @Transient val id: Long = 0,
) {
    @Serializable
    enum class Outcome {
        /** Data came. */
        OK,

        /** The copy the app has is still current. */
        NOT_MODIFIED,
        FAILED,
    }
}

/**
 * Thrown instead of going online in offline mode. Callers check the mode before
 * asking for anything, so this is only a last guard, and nothing is logged for it.
 */
class OfflineException : IOException("Offline mode is on")

/** The server answered, but not with the file: [status] is its HTTP status. */
class HttpStatusException(
    val status: Int,
) : IOException("HTTP $status")

/** The server sent more than the app is willing to take in. */
class TooLargeException : IOException("The response is too large")

/** True when [this] says the server could not be reached at all, as when the phone has no connection. */
fun IOException.isConnectionFailure(): Boolean =
    this is UnknownHostException ||
        this is ConnectException ||
        this is NoRouteToHostException ||
        this is SocketTimeoutException ||
        this is SSLException

sealed interface DownloadResult {
    /** The server confirmed that the copy identified by the given ETag is still current. */
    data object NotModified : DownloadResult

    /** [etag] is what to pass to the next download, so that an unchanged file is not downloaded again. */
    data class Downloaded(
        val etag: String?,
    ) : DownloadResult
}

/** [bytes] is null when the server said the copy with the ETag that was sent is still current. */
class FeedResponse(
    val bytes: ByteArray?,
    val etag: String?,
)

/**
 * The one way the app goes online. Every request passes through here: none is
 * sent in offline mode, and each one sent is written to a log the user can read
 * in the settings. The last [MAX_ENTRIES] are kept on the device.
 */
class Network(
    /** Where the log is kept between runs of the app. */
    private val file: File,
    scope: CoroutineScope,
    private val userAgent: String,
    /** True while the user wants nothing fetched at all. */
    private val isOffline: () -> Boolean,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(FetchLogEntry.serializer())
    private val _log = MutableStateFlow(emptyList<FetchLogEntry>())
    private val nextId = AtomicLong()

    /** The requests made lately, newest first. */
    val log: StateFlow<List<FetchLogEntry>> = _log.asStateFlow()

    // Several requests in a row are written to the file once.
    private val saves = Channel<Unit>(Channel.CONFLATED)

    init {
        scope.launch(Dispatchers.IO) {
            // Read here rather than when the app starts; what was logged meanwhile is newer and stays on top.
            val stored = load().map { it.copy(id = nextId.incrementAndGet()) }
            _log.update { (it + stored).take(MAX_ENTRIES) }
            while (true) {
                saves.receive()
                save(_log.value)
                delay(SAVE_DELAY_MILLIS)
            }
        }
    }

    private val offline: Boolean get() = isOffline()

    fun clearLog() {
        _log.value = emptyList()
        saves.trySend(Unit)
    }

    /**
     * Size of the file at [url] in bytes, without downloading it; null when the server does not say.
     *
     * @throws IOException on network failure, or [OfflineException] in offline mode
     */
    fun contentLength(
        kind: FetchKind,
        url: String,
    ): Long? =
        request(kind, url, method = "HEAD", etag = null, readTimeout = 15_000) { connection, status ->
            Received(if (status == HttpURLConnection.HTTP_OK) connection.contentLengthLong.takeIf { it > 0 } else null, bytes = null)
        }

    /**
     * Downloads [url] to [target]. When [etag] is given the request is conditional,
     * so an unchanged file costs a single small round trip. Stops as soon as
     * offline mode is switched on.
     *
     * @throws IOException on network failure or an unexpected HTTP status, or [OfflineException] in offline mode
     */
    fun download(
        kind: FetchKind,
        url: String,
        target: File,
        etag: String?,
        onProgress: (bytesRead: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): DownloadResult =
        request(kind, url, method = "GET", etag = etag, readTimeout = 30_000) { connection, status ->
            when (status) {
                HttpURLConnection.HTTP_NOT_MODIFIED -> {
                    Received(DownloadResult.NotModified, bytes = null)
                }

                HttpURLConnection.HTTP_OK -> {
                    val total = connection.contentLengthLong
                    // Written next to the target and renamed, so a broken download never looks complete.
                    val partial = File(target.path + ".part")
                    var read = 0L
                    try {
                        connection.inputStream.use { input ->
                            partial.outputStream().use { output ->
                                val buffer = ByteArray(BUFFER_BYTES)
                                while (true) {
                                    if (offline) throw OfflineException()
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    output.write(buffer, 0, count)
                                    read += count
                                    if (read > MAX_DOWNLOAD_BYTES) throw TooLargeException()
                                    onProgress(read, total)
                                }
                            }
                        }
                        target.delete()
                        if (!partial.renameTo(target)) throw IOException("Cannot move download to ${target.path}")
                    } catch (e: IOException) {
                        partial.delete()
                        throw PartialException(e, read)
                    }
                    Received(DownloadResult.Downloaded(connection.validator()), bytes = read)
                }

                else -> {
                    throw HttpStatusException(status)
                }
            }
        }

    /**
     * Fetches the small file at [url] into memory. When [etag] is given the
     * request is conditional, so a file that has not changed costs no download.
     *
     * @throws IOException on network failure or an unexpected HTTP status, or [OfflineException] in offline mode
     */
    fun fetch(
        kind: FetchKind,
        url: String,
        etag: String?,
    ): FeedResponse =
        request(kind, url, method = "GET", etag = etag, readTimeout = 15_000) { connection, status ->
            when (status) {
                HttpURLConnection.HTTP_NOT_MODIFIED -> {
                    Received(FeedResponse(null, etag), bytes = null)
                }

                HttpURLConnection.HTTP_OK -> {
                    val bytes = connection.inputStream.use { it.readAtMost(MAX_FETCH_BYTES) }
                    Received(FeedResponse(bytes, connection.validator()), bytes = bytes.size.toLong())
                }

                else -> {
                    throw HttpStatusException(status)
                }
            }
        }

    /** What came back from a request, and how much of it. */
    private class Received<T>(
        val value: T,
        val bytes: Long?,
    )

    /** A download that broke off after [bytes] had come. */
    private class PartialException(
        val error: IOException,
        val bytes: Long,
    ) : IOException(error.message, error)

    /** A request that failed after the server had answered with [status]. */
    private class AnsweredException(
        val error: IOException,
        val status: Int,
    ) : IOException(error.message, error)

    private fun <T> request(
        kind: FetchKind,
        url: String,
        method: String,
        etag: String?,
        readTimeout: Int,
        redirects: Int = 0,
        read: (HttpURLConnection, status: Int) -> Received<T>,
    ): T {
        val started = System.currentTimeMillis()
        // Not attempted at all, so not a request to log.
        if (offline) throw OfflineException()
        val redirectedTo: String
        try {
            val connection = URI(url).toURL().openConnection() as HttpURLConnection
            try {
                connection.requestMethod = method
                // Followed below instead, one logged request at a time, so that none is made unseen.
                connection.instanceFollowRedirects = false
                connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
                connection.readTimeout = readTimeout
                connection.setRequestProperty("User-Agent", userAgent)
                when {
                    etag == null -> Unit

                    etag.startsWith(
                        MODIFIED_PREFIX,
                    ) -> connection.setRequestProperty("If-Modified-Since", etag.removePrefix(MODIFIED_PREFIX))

                    else -> connection.setRequestProperty("If-None-Match", etag)
                }
                val code = connection.responseCode
                if (code !in REDIRECTS) {
                    val received =
                        try {
                            read(connection, code)
                        } catch (e: IOException) {
                            throw AnsweredException(e, code)
                        }
                    val outcome =
                        if (code == HttpURLConnection.HTTP_NOT_MODIFIED) FetchLogEntry.Outcome.NOT_MODIFIED else FetchLogEntry.Outcome.OK
                    record(FetchLogEntry(started, kind, url, outcome, code, received.bytes, System.currentTimeMillis() - started))
                    return received.value
                }
                redirectedTo =
                    try {
                        redirectTarget(url, connection.getHeaderField("Location"), redirects)
                    } catch (e: IOException) {
                        throw AnsweredException(e, code)
                    }
                record(FetchLogEntry(started, kind, url, FetchLogEntry.Outcome.OK, code, millis = System.currentTimeMillis() - started))
            } finally {
                connection.disconnect()
            }
        } catch (e: IOException) {
            val answered = e as? AnsweredException
            val partial = (answered?.error ?: e) as? PartialException
            val cause = partial?.error ?: answered?.error ?: e
            // A download cut short by going offline was sent all the same, so it is logged as failed.
            val outcome = FetchLogEntry.Outcome.FAILED
            val bytes = partial?.bytes?.takeIf { it > 0 }
            val message = cause.message ?: cause.javaClass.simpleName
            record(FetchLogEntry(started, kind, url, outcome, answered?.status, bytes, System.currentTimeMillis() - started, message))
            throw cause
        } catch (e: URISyntaxException) {
            record(FetchLogEntry(started, kind, url, FetchLogEntry.Outcome.FAILED, error = e.message ?: e.javaClass.simpleName))
            throw IOException(e.message, e)
        } catch (e: IllegalArgumentException) {
            // A malformed address in the city's configuration.
            record(FetchLogEntry(started, kind, url, FetchLogEntry.Outcome.FAILED, error = e.message ?: e.javaClass.simpleName))
            throw IOException(e.message, e)
        }
        // The next request is logged on its own, as the one it is.
        return request(kind, redirectedTo, method, etag, readTimeout, redirects + 1, read)
    }

    /**
     * Where a redirect from [url] leads, when the app will follow it: only to
     * the same server and only over HTTPS, so that the operator's server cannot
     * send the app anywhere else.
     */
    private fun redirectTarget(
        url: String,
        location: String?,
        redirects: Int,
    ): String {
        if (location == null || redirects >= MAX_REDIRECTS) throw IOException("Too many redirects")
        val from = URI(url)
        val target =
            try {
                from.resolve(location)
            } catch (e: IllegalArgumentException) {
                throw IOException("Malformed redirect", e)
            }
        if (target.scheme != "https" || !target.host.equals(from.host, ignoreCase = true)) {
            throw IOException("Redirect to another server refused")
        }
        return target.toString()
    }

    /**
     * What to ask with next time, to learn whether the file has changed: its
     * ETag, or from a server that gives none the date it was last modified,
     * marked so that it is sent back as what it is.
     */
    private fun HttpURLConnection.validator(): String? =
        getHeaderField("ETag") ?: getHeaderField("Last-Modified")?.let { MODIFIED_PREFIX + it }

    /** Reads everything, unless it is more than [limit] bytes: a feed is small, and what is not is no feed. */
    private fun InputStream.readAtMost(limit: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER_BYTES)
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            if (output.size() + count > limit) throw TooLargeException()
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun record(entry: FetchLogEntry) {
        val numbered = entry.copy(id = nextId.incrementAndGet())
        _log.update { (listOf(numbered) + it).take(MAX_ENTRIES) }
        saves.trySend(Unit)
    }

    private fun load(): List<FetchLogEntry> =
        try {
            if (file.exists()) json.decodeFromString(serializer, file.readText()) else emptyList()
        } catch (e: IOException) {
            emptyList()
        } catch (e: SerializationException) {
            emptyList()
        } catch (e: IllegalArgumentException) {
            emptyList()
        }

    private fun save(entries: List<FetchLogEntry>) {
        try {
            val incoming = File(file.path + ".new")
            incoming.writeText(json.encodeToString(serializer, entries))
            // Left for the next save when it cannot be moved; the log stays in memory meanwhile.
            if (!incoming.renameTo(file)) incoming.delete()
        } catch (e: IOException) {
            // The log stays in memory; the next request tries again.
        }
    }

    companion object {
        const val MAX_ENTRIES = 200
        private const val SAVE_DELAY_MILLIS = 2_000L
        private const val CONNECT_TIMEOUT_MILLIS = 15_000
        private const val BUFFER_BYTES = 64 * 1024
        private const val MAX_REDIRECTS = 5
        private const val MODIFIED_PREFIX = "modified:"
        private val REDIRECTS = setOf(301, 302, 303, 307, 308)

        /** Live feeds are tens or hundreds of kilobytes; nothing near this is one. */
        private const val MAX_FETCH_BYTES = 16 * 1024 * 1024

        /** A timetable archive is a few megabytes, tens for the largest cities. */
        private const val MAX_DOWNLOAD_BYTES = 512L * 1024 * 1024
    }
}
