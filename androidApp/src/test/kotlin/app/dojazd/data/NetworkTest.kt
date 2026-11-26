package app.dojazd.data

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The app's one way online, against a server on this machine. */
class NetworkTest {
    private val folder = Files.createTempDirectory("dojazd-network").toFile()
    private val scope = CoroutineScope(Dispatchers.Unconfined)
    private var offline = false
    private val network = Network(File(folder, "fetch-log.json"), scope, "Dojazd/test") { offline }
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply { start() }
    private val base = "http://127.0.0.1:${server.address.port}"

    /** Answers requests for [path] with what [respond] sends. */
    private fun serve(
        path: String,
        respond: (HttpExchange) -> Unit,
    ) {
        server.createContext(path) { exchange -> exchange.use(respond) }
    }

    private fun HttpExchange.send(
        status: Int,
        body: ByteArray = ByteArray(0),
    ) {
        sendResponseHeaders(status, if (body.isEmpty()) -1 else body.size.toLong())
        if (body.isNotEmpty()) responseBody.write(body)
    }

    @AfterTest
    fun stop() {
        server.stop(0)
        scope.cancel()
        folder.deleteRecursively()
    }

    @Test
    fun fetchesAFeedAndLogsTheRequest() {
        // given
        serve("/feed") {
            assertEquals("Dojazd/test", it.requestHeaders.getFirst("User-Agent"))
            it.responseHeaders.add("ETag", "\"v1\"")
            it.send(200, byteArrayOf(1, 2, 3))
        }

        // when
        val response = network.fetch(FetchKind.ALERTS, "$base/feed", etag = null)

        // then
        assertContentEquals(byteArrayOf(1, 2, 3), response.bytes)
        assertEquals("\"v1\"", response.etag)
        val entry = network.log.value.single()
        assertEquals(FetchLogEntry.Outcome.OK, entry.outcome)
        assertEquals(200, entry.status)
        assertEquals(3, entry.bytes)
        assertEquals("$base/feed", entry.url)
    }

    @Test
    fun asksWhetherTheFileChangedWithWhatTheServerGaveLastTime() {
        // given a server that knows the file by its ETag, and one that only dates it
        serve("/tagged") { it.send(if (it.requestHeaders.getFirst("If-None-Match") == "\"v1\"") 304 else 200, byteArrayOf(1)) }
        serve("/dated") {
            it.responseHeaders.add("Last-Modified", "Mon, 09 Nov 2026 12:54:12 GMT")
            it.send(if (it.requestHeaders.getFirst("If-Modified-Since") == "Mon, 09 Nov 2026 12:54:12 GMT") 304 else 200, byteArrayOf(1))
        }

        // when
        val tagged = network.fetch(FetchKind.ALERTS, "$base/tagged", etag = "\"v1\"")
        val dated = network.fetch(FetchKind.ALERTS, "$base/dated", etag = null)
        val datedAgain = network.fetch(FetchKind.ALERTS, "$base/dated", etag = dated.etag)

        // then neither is downloaded a second time
        assertNull(tagged.bytes)
        assertNull(datedAgain.bytes)
        assertEquals(
            FetchLogEntry.Outcome.NOT_MODIFIED,
            network.log.value
                .first()
                .outcome,
        )
    }

    @Test
    fun makesNoRequestAndLogsNoneInOfflineMode() {
        // given
        var asked = false
        serve("/feed") {
            asked = true
            it.send(200, byteArrayOf(1))
        }
        offline = true

        // then
        assertFailsWith<OfflineException> { network.fetch(FetchKind.ALERTS, "$base/feed", etag = null) }
        assertTrue(!asked)
        assertTrue(network.log.value.isEmpty())
    }

    @Test
    fun tellsWhatTheServerAnsweredWhenItIsNotTheFile() {
        // given
        serve("/gone") { it.send(503) }

        // when
        val error = assertFailsWith<HttpStatusException> { network.fetch(FetchKind.ALERTS, "$base/gone", etag = null) }

        // then
        assertEquals(503, error.status)
        val entry = network.log.value.single()
        assertEquals(FetchLogEntry.Outcome.FAILED, entry.outcome)
        assertEquals(503, entry.status)
    }

    @Test
    fun refusesAFeedLargerThanAnyFeedIs() {
        // given a body without end
        serve("/endless") {
            it.sendResponseHeaders(200, 0)
            val chunk = ByteArray(1 shl 20)
            try {
                while (true) it.responseBody.write(chunk)
            } catch (e: IOException) {
                // The app hung up, as it should.
            }
        }

        // then
        assertFailsWith<TooLargeException> { network.fetch(FetchKind.ALERTS, "$base/endless", etag = null) }
        assertEquals(
            FetchLogEntry.Outcome.FAILED,
            network.log.value
                .single()
                .outcome,
        )
    }

    @Test
    fun followsNoRedirectToAnotherServerAndLogsTheOneItRefused() {
        // given a server that sends the app elsewhere
        serve("/moved") {
            it.responseHeaders.add("Location", "https://elsewhere.example/feed")
            it.send(302)
        }

        // then
        assertFailsWith<IOException> { network.fetch(FetchKind.ALERTS, "$base/moved", etag = null) }
        val entry = network.log.value.single()
        assertEquals(FetchLogEntry.Outcome.FAILED, entry.outcome)
        assertEquals(302, entry.status)
    }

    @Test
    fun aDownloadCutShortLeavesNoFileBehind() {
        // given a server that promises more than it sends
        serve("/broken") {
            it.sendResponseHeaders(200, 1_000)
            it.responseBody.write(ByteArray(10))
            it.responseBody.flush()
        }
        val target = File(folder, "timetable.zip")

        // then
        assertFailsWith<IOException> { network.download(FetchKind.TIMETABLE, "$base/broken", target, etag = null) }
        assertTrue(!target.exists())
        assertTrue(!File(target.path + ".part").exists())
    }

    @Test
    fun downloadsToTheFileAndReportsProgress() {
        // given
        val body = ByteArray(200_000) { it.toByte() }
        serve("/timetable") { it.send(200, body) }
        val target = File(folder, "timetable.zip")
        var reported = 0L

        // when
        val result = network.download(FetchKind.TIMETABLE, "$base/timetable", target, etag = null) { read, _ -> reported = read }

        // then
        assertTrue(result is DownloadResult.Downloaded)
        assertContentEquals(body, target.readBytes())
        assertEquals(body.size.toLong(), reported)
    }
}
