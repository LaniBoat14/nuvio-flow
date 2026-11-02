package com.nuvio.tv.data.trailer

import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class ImdbTrailerApiTest {
    private val server = MockWebServer()
    private var clock = 0L

    private fun api(budgetMs: Long = 2_000L) = ImdbTrailerApi(
        OkHttpClient.Builder().readTimeout(300, TimeUnit.MILLISECONDS).callTimeout(500, TimeUnit.MILLISECONDS).build(),
        server.url("/").toString(), budgetMs
    ) { clock }

    private fun answer(edges: String) =
        """{"data":{"title":{"primaryVideos":{"edges":[$edges]}}},"extensions":{"disclaimer":"x"}}"""
    private fun encoding(def: String, mime: String = "MP4") =
        """{"url":"https://imdb-video.media-imdb.com/vi123456789/$def.${if (mime == "MP4") "mp4" else "m3u8"}","videoMimeType":"$mime","videoDefinition":"$def"}"""
    private val trailer = """{"node":{"id":"vi123456789","playbackURLs":[${encoding("DEF_AUTO", "M3U8")},${encoding("DEF_SD")},${encoding("DEF_1080p")},${encoding("DEF_720p")}]}}"""

    @Before fun setUp() = server.start()
    @After fun tearDown() = server.shutdown()

    @Test fun `request names the title as a variable and the app as the client`() = runBlocking {
        server.enqueue(MockResponse().setBody(answer("")))
        api().primaryVideo("tt0816692")
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals(ImdbTrailerApi.CLIENT_NAME, request.getHeader("x-imdb-client-name"))
        val body = JsonParser.parseString(request.body.readUtf8()).asJsonObject
        assertEquals("tt0816692", body.getAsJsonObject("variables").get("id").asString)
        assertTrue(body.get("query").asString.contains("playbackURLs"))
    }

    @Test fun `an answer with a trailer gives the same video as the title page`() = runBlocking {
        server.enqueue(MockResponse().setBody(answer(trailer)))
        val video = (api().primaryVideo("tt1") as ImdbTrailerData.Result.Found).video
        assertEquals("vi123456789", video.id)
        assertEquals(1080, ImdbTrailerData.bestMp4(video)?.height)
    }

    @Test fun `no trailer is definite and keeps the service in use`() = runBlocking {
        val api = api()
        server.enqueue(MockResponse().setBody(answer("")))
        server.enqueue(MockResponse().setBody(answer(trailer)))
        assertEquals(ImdbTrailerData.Result.Missing, api.primaryVideo("tt1"))
        assertTrue(api.primaryVideo("tt2") is ImdbTrailerData.Result.Found)
    }

    @Test fun `a refusal falls back to the page for half an hour`() = runBlocking {
        val api = api()
        server.enqueue(MockResponse().setResponseCode(403))
        assertEquals(ImdbTrailerData.Result.Pending, api.primaryVideo("tt1"))
        clock += ImdbApiBackoff.REFUSAL_PAUSE_MS - 1
        assertEquals(ImdbTrailerData.Result.Pending, api.primaryVideo("tt2"))
        assertEquals(1, server.requestCount)
        clock += 1
        server.enqueue(MockResponse().setBody(answer(trailer)))
        assertTrue(api.primaryVideo("tt3") is ImdbTrailerData.Result.Found)
    }

    @Test fun `an answer with errors counts as a refusal, an unknown title does not`() = runBlocking {
        val api = api()
        server.enqueue(MockResponse().setBody("""{"data":{"title":null}}"""))
        assertEquals(ImdbTrailerData.Result.Pending, api.primaryVideo("tt1"))
        server.enqueue(MockResponse().setBody("""{"errors":[{"message":"Field 'x' is undefined"}],"data":null}"""))
        assertEquals(ImdbTrailerData.Result.Pending, api.primaryVideo("tt2"))
        assertEquals(ImdbTrailerData.Result.Pending, api.primaryVideo("tt3"))
        assertEquals(2, server.requestCount)
    }

    @Test fun `one slow answer is tried again, two in a row pause the service for five minutes`() = runBlocking {
        val api = api()
        repeat(2) { server.enqueue(MockResponse().setBody(answer(trailer)).setHeadersDelay(2, TimeUnit.SECONDS)) }
        assertEquals(ImdbTrailerData.Result.Pending, api.primaryVideo("tt1"))
        assertEquals(ImdbTrailerData.Result.Pending, api.primaryVideo("tt2"))
        assertEquals(ImdbTrailerData.Result.Pending, api.primaryVideo("tt3"))
        assertEquals(2, server.requestCount)
        clock += ImdbApiBackoff.FAILURE_PAUSE_MS
        server.enqueue(MockResponse().setBody(answer(trailer)))
        assertTrue(api.primaryVideo("tt4") is ImdbTrailerData.Result.Found)
    }

    @Test fun `a failure between answers does not add up to a pause`() {
        val backoff = ImdbApiBackoff { clock }
        backoff.failed()
        backoff.succeeded()
        backoff.failed()
        assertTrue(backoff.allowed())
        backoff.failed()
        assertFalse(backoff.allowed())
    }
}
