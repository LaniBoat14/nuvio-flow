package com.nuvio.tv.data.mediaserver.silo

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SiloArtworkTest {
    private val base = "http://silo.local:8090/api/v2/artwork/tmdb/movies/603"
    private val nowSeconds = 1_000_000L

    private fun link(kind: String, exp: Long, sig: String) = "$base/$kind/w500.r1.webp?exp=$exp&sig=$sig"

    private class Fake(private val status: (Request) -> Int) {
        val sent = mutableListOf<Request>()
        fun client(interceptor: SiloArtworkInterceptor): OkHttpClient = OkHttpClient.Builder()
            .addInterceptor(interceptor)
            .addInterceptor { chain ->
                val request = chain.request()
                synchronized(sent) { sent += request }
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status(request)).message("x")
                    .body("img".toResponseBody(null)).build()
            }
            .build()
    }

    private fun setup(status: (Request) -> Int = { 200 }, links: Map<SiloArtworkKind, String>? = null): Triple<Fake, OkHttpClient, () -> Int> {
        var fetches = 0
        val refresher = SiloArtworkRefresher(
            fetch = { fetches++; links },
            now = { nowSeconds * 1_000L }
        )
        val fake = Fake(status)
        return Triple(fake, fake.client(SiloArtworkInterceptor({ refresher }, { nowSeconds })), { fetches })
    }

    private fun OkHttpClient.get(url: String) = newCall(Request.Builder().url(url).build()).execute().use { it.code }

    @Test
    fun aMarkNamesItsTitleAndSurvivesOddIds() {
        val marked = SiloArtworkLink.mark(link("poster", 1, "a"), "c1", "local.12#a b", SiloArtworkKind.POSTER)

        assertEquals(SiloArtworkMark("c1", "local.12#a b", SiloArtworkKind.POSTER), SiloArtworkLink.parse(marked.toHttpUrl()))
        assertEquals("$base/poster/w500.r1.webp", marked.toHttpUrl().newBuilder().query(null).fragment(null).build().toString())
        assertNull(SiloArtworkLink.parse(link("poster", 1, "a").toHttpUrl()))
    }

    @Test
    fun anExpiredLinkIsSwappedForAFreshOneOncePerTitle() {
        val fresh = mapOf(
            SiloArtworkKind.POSTER to link("poster", nowSeconds + 14_400, "new1"),
            SiloArtworkKind.BACKGROUND to link("backdrop", nowSeconds + 14_400, "new2")
        )
        val (fake, client, fetches) = setup(links = fresh)

        assertEquals(200, client.get(SiloArtworkLink.mark(link("poster", nowSeconds - 10, "old1"), "c1", "movie-tmdb-603", SiloArtworkKind.POSTER)))
        assertEquals(200, client.get(SiloArtworkLink.mark(link("backdrop", nowSeconds - 10, "old2"), "c1", "movie-tmdb-603", SiloArtworkKind.BACKGROUND)))

        assertEquals(listOf("new1", "new2"), fake.sent.map { it.url.queryParameter("sig") })
        assertEquals(listOf<String?>(null, null), fake.sent.map { it.url.fragment })
        assertEquals(1, fetches())
    }

    @Test
    fun aLinkStillValidGoesAsItIsAndARefusalIsRetriedWithAFreshOne() {
        val fresh = mapOf(SiloArtworkKind.POSTER to link("poster", nowSeconds + 14_400, "new"))
        val (fake, client, fetches) = setup(status = { if (it.url.queryParameter("sig") == "gone") 404 else 200 }, links = fresh)

        assertEquals(200, client.get(SiloArtworkLink.mark(link("poster", nowSeconds + 3_600, "ok"), "c1", "movie-tmdb-603", SiloArtworkKind.POSTER)))
        assertEquals(0, fetches())
        assertEquals(200, client.get(SiloArtworkLink.mark(link("poster", nowSeconds + 3_600, "gone"), "c1", "movie-tmdb-603", SiloArtworkKind.POSTER)))

        assertEquals(listOf("ok", "gone", "new"), fake.sent.map { it.url.queryParameter("sig") })
        assertEquals(1, fetches())
    }

    @Test
    fun otherLinksAndFailedLookupsAreLeftAlone() {
        val (fake, client, fetches) = setup(links = null)

        assertEquals(200, client.get("https://image.tmdb.org/t/p/w500/x.jpg"))
        assertEquals(0, fetches())
        repeat(2) {
            client.get(SiloArtworkLink.mark(link("poster", nowSeconds - 10, "old"), "c1", "movie-tmdb-603", SiloArtworkKind.POSTER))
        }

        assertEquals(listOf(null, "old", "old"), fake.sent.map { it.url.queryParameter("sig") })
        assertEquals(1, fetches())
    }
}
