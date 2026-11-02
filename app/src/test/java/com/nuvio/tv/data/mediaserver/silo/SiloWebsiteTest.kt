package com.nuvio.tv.data.mediaserver.silo

import com.nuvio.tv.data.mediaserver.ServerException
import com.nuvio.tv.data.mediaserver.ServerFailure
import com.nuvio.tv.data.mediaserver.ServerSignInIssue
import com.nuvio.tv.data.mediaserver.jellyfin.JellyfinProvider
import com.nuvio.tv.data.mediaserver.mediabrowser.TestHttp
import com.nuvio.tv.data.mediaserver.mediabrowser.testIdentity
import com.nuvio.tv.data.mediaserver.mediabrowser.text
import kotlinx.coroutines.test.runTest
import javax.net.ssl.SSLPeerUnverifiedException
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SiloWebsiteTest {
    private val webPage = "<!doctype html><html><head><title>Silo</title></head><body></body></html>"
    private val publicInfo = """{"ServerName": "Popcorn", "Version": "12.1.0", "ProductName": "Jellyfin Server", "Id": "srv"}"""
    private val authenticated = """{"User": {"Id": "u1", "Name": "harry#Kids"}, "AccessToken": "jf-token", "ServerId": "srv"}"""

    private fun connectInfo(publicUrl: String, enabled: Boolean = true) =
        """{"jellyfin": {"enabled": $enabled, "pending_restart": false, "public_url": "$publicUrl", "server_name": "Popcorn"},
           "account": {"password_login_available": true}}"""

    private class FakeSilo(
        val websiteHost: String,
        val jellyfinHost: String,
        val connectInfo: String,
        val acceptedPassword: String = "pw",
        val jellyfinStatus: (Request) -> Int = { 200 },
        val account: String = "harry",
        val profiles: String = """{"items": [{"name": "Harry", "is_primary": true, "has_pin": false}]}"""
    ) {
        fun accepts(request: Request): Boolean =
            request.text.contains("\"username\":\"$account\",\"password\":\"$acceptedPassword\"")
    }

    private fun http(silo: FakeSilo, webPage: String = this.webPage, publicInfo: String = this.publicInfo) = TestHttp(
        status = { request ->
            when {
                request.url.host == silo.jellyfinHost -> silo.jellyfinStatus(request)
                request.url.encodedPath == SiloWebsite.LOGIN_PATH && !silo.accepts(request) -> 401
                else -> 200
            }
        },
        responder = { request ->
            when {
                request.url.host == silo.jellyfinHost -> when (request.url.encodedPath) {
                    "/System/Info/Public" -> publicInfo
                    "/Users/AuthenticateByName" -> if (silo.jellyfinStatus(request) == 200) authenticated else
                        """{"Error": "InvalidUsernameOrPassword", "Message": "Invalid username or password"}"""
                    else -> "[]"
                }
                request.url.encodedPath == SiloWebsite.SYSTEM_INFO_PATH ->
                    """{"server_version": "build-812", "api_major": 2, "contract_digest": "x", "links": {}}"""
                request.url.encodedPath == SiloWebsite.LOGIN_PATH ->
                    if (silo.accepts(request)) {
                        """{"access_token": "site-token", "refresh_token": "r", "expires_in": 900}"""
                    } else {
                        """{"type": "https://siloserver.org/docs/api/v2/problems/invalid_credentials", "title": "Unauthorized",
                           "status": 401, "detail": "invalid credentials", "instance": "urn:silo:request:1"}"""
                    }
                request.url.encodedPath == SiloWebsite.CONNECT_INFO_PATH -> silo.connectInfo
                request.url.encodedPath == SiloWebsite.PROFILES_PATH -> silo.profiles
                request.url.encodedPath == SiloWebsite.LOGOUT_PATH -> ""
                else -> webPage
            }
        }
    )

    @Test
    fun theWebsiteAddressFindsTheJellyfinAddressTheOwnerSetUp() = runTest {
        val silo = FakeSilo("silo.popcorn.test", "silojf.popcorn.test", connectInfo("https://silojf.popcorn.test"))
        val http = http(silo)

        val signIn = SiloProvider(http.client, testIdentity).signIn("https://silo.popcorn.test/", "harry#Kids", "pw")

        assertEquals("https://silojf.popcorn.test", signIn.address)
        assertEquals("jf-token", signIn.token)
        val logins = http.requests.filter { it.url.encodedPath == SiloWebsite.LOGIN_PATH }
        assertEquals(listOf("""{"username":"harry","password":"pw"}"""), logins.map { it.text })
        assertNull(logins.last().header("Authorization"))
        assertTrue(http.requests.none { it.url.encodedPath == SiloWebsite.PROFILES_PATH })
        val info = http.requests.single { it.url.encodedPath == SiloWebsite.CONNECT_INFO_PATH }
        assertEquals("Bearer site-token", info.header("Authorization"))
        val logout = http.requests.single { it.url.encodedPath == SiloWebsite.LOGOUT_PATH }
        assertEquals("Bearer site-token", logout.header("Authorization"))
        val jellyfinSignIn = http.requests.last()
        assertEquals("https://silojf.popcorn.test/Users/AuthenticateByName", jellyfinSignIn.url.toString())
        assertTrue(jellyfinSignIn.text.contains("\"Username\":\"harry#Kids\""))
    }

    @Test
    fun anUnsetAddressFallsBackToTheDefaultPortOnTheWebsitesHost() = runTest {
        val http = fallbackHttp(branding = """{"LoginDisclaimer": "Silo provides Jellyfin-compatible app support.", "SplashscreenEnabled": false}""")

        val signIn = SiloProvider(http.client, testIdentity).signIn("192.168.1.20:8090", "harry", "pw")

        assertEquals("http://192.168.1.20:8096", signIn.address)
    }

    @Test
    fun aGuessedAddressThatIsAnotherJellyfinServerNeverGetsThePassword() = runTest {
        val http = fallbackHttp(branding = """{"SplashscreenEnabled": false}""")

        val issue = signInIssue { SiloProvider(http.client, testIdentity).signIn("192.168.1.20:8090", "harry", "pw") }

        assertEquals(ServerSignInIssue.SILO_WEBSITE, issue)
        assertTrue(http.requests.none { it.url.port == 8096 && it.url.encodedPath == "/Users/AuthenticateByName" })
    }

    private fun fallbackHttp(branding: String): TestHttp {
        val silo = FakeSilo("192.168.1.20", "192.168.1.20", connectInfo("http://127.0.0.1:8096"))
        return TestHttp(
            status = { 200 },
            responder = { request ->
                when {
                    request.url.port == 8096 && request.url.encodedPath == "/System/Info/Public" -> publicInfo
                    request.url.port == 8096 && request.url.encodedPath == SiloWebsite.BRANDING_PATH -> branding
                    request.url.port == 8096 -> authenticated
                    request.url.encodedPath == SiloWebsite.SYSTEM_INFO_PATH -> """{"server_version": "build-812"}"""
                    request.url.encodedPath == SiloWebsite.LOGIN_PATH -> """{"access_token": "site-token"}"""
                    request.url.encodedPath == SiloWebsite.CONNECT_INFO_PATH -> silo.connectInfo
                    request.url.encodedPath == SiloWebsite.LOGOUT_PATH -> ""
                    else -> webPage
                }
            }
        )
    }

    @Test
    fun anHttpsWebsiteNeverFallsBackToPlainHttp() = runTest {
        val silo = FakeSilo("silo.popcorn.test", "silojf.popcorn.test", connectInfo("http://127.0.0.1:8096"))
        val http = http(silo)

        val issue = signInIssue { SiloProvider(http.client, testIdentity).signIn("https://silo.popcorn.test", "harry", "pw") }

        assertEquals(ServerSignInIssue.SILO_WEBSITE, issue)
        assertTrue(http.requests.none { it.url.port == 8096 })
        assertTrue(http.requests.any { it.url.encodedPath == SiloWebsite.LOGOUT_PATH })
    }

    @Test
    fun jellyfinAppsTurnedOffIsSaidPlainly() = runTest {
        val silo = FakeSilo("silo.popcorn.test", "silojf.popcorn.test", connectInfo("https://silojf.popcorn.test", enabled = false))

        val issue = signInIssue { SiloProvider(http(silo).client, testIdentity).signIn("https://silo.popcorn.test", "harry", "pw") }

        assertEquals(ServerSignInIssue.SILO_JELLYFIN_OFF, issue)
    }

    @Test
    fun aJellyfinAddressThatCannotBeReachedPointsToSilosConnectAppsPage() = runTest {
        val silo = FakeSilo("silo.popcorn.test", "silojf.popcorn.test", connectInfo("https://silojf.popcorn.test"), jellyfinStatus = { 404 })

        val issue = signInIssue { SiloProvider(http(silo).client, testIdentity).signIn("https://silo.popcorn.test", "harry", "pw") }

        assertEquals(ServerSignInIssue.SILO_WEBSITE, issue)
    }

    @Test
    fun aProfilePinAfterTheLastHashIsLeftOffForTheWebsite() = runTest {
        val silo = FakeSilo("silo.popcorn.test", "silojf.popcorn.test", connectInfo("https://silojf.popcorn.test"), acceptedPassword = "pa#ss")
        val http = http(silo)

        SiloProvider(http.client, testIdentity).signIn("https://silo.popcorn.test", "harry#Kids", "pa#ss#1234")

        val logins = http.requests.filter { it.url.encodedPath == SiloWebsite.LOGIN_PATH }.map { it.text }
        assertEquals(listOf("""{"username":"harry","password":"pa#ss"}"""), logins)
        assertTrue(http.requests.last().text.contains("\"Pw\":\"pa#ss#1234\""))
    }

    @Test
    fun anAccountWhoseNameHasAHashGetsItsProfileAddedForJellyfin() = runTest {
        val silo = FakeSilo(
            "silo.popcorn.test", "silojf.popcorn.test", connectInfo("https://silojf.popcorn.test"),
            account = "harryposter#1234",
            profiles = """{"items": [{"name": "Kids", "is_primary": false, "has_pin": true},
                                     {"name": "Harry", "is_primary": true, "has_pin": false}]}"""
        )
        val http = http(silo)

        SiloProvider(http.client, testIdentity).signIn("https://silo.popcorn.test", "harryposter#1234", "pw")

        assertEquals(
            listOf("""{"username":"harryposter","password":"pw"}""", """{"username":"harryposter#1234","password":"pw"}"""),
            http.requests.filter { it.url.encodedPath == SiloWebsite.LOGIN_PATH }.map { it.text }
        )
        val profiles = http.requests.single { it.url.encodedPath == SiloWebsite.PROFILES_PATH }
        assertEquals("Bearer site-token", profiles.header("Authorization"))
        assertTrue(http.requests.last().text.contains("\"Username\":\"harryposter#1234#Harry\""))
    }

    @Test
    fun aHashAccountWithoutUsableProfilesKeepsTheNameAsTyped() = runTest {
        val silo = FakeSilo(
            "silo.popcorn.test", "silojf.popcorn.test", connectInfo("https://silojf.popcorn.test"),
            account = "harryposter#1234",
            profiles = """{"items": [{"name": "Movie #2", "is_primary": true, "has_pin": false}]}"""
        )
        val http = http(silo)

        SiloProvider(http.client, testIdentity).signIn("https://silo.popcorn.test", "harryposter#1234", "pw")

        assertTrue(http.requests.last().text.contains("\"Username\":\"harryposter#1234\""))
    }

    @Test
    fun aHashAccountWithSeveralProfilesIsLeftForThePersonToChoose() = runTest {
        val silo = FakeSilo(
            "silo.popcorn.test", "silojf.popcorn.test", connectInfo("https://silojf.popcorn.test"),
            account = "harryposter#1234",
            profiles = """{"items": [{"name": "Harry", "is_primary": true, "has_pin": false},
                                     {"name": "Kids", "is_primary": false, "has_pin": false}]}"""
        )
        val http = http(silo)

        SiloProvider(http.client, testIdentity).signIn("https://silo.popcorn.test", "harryposter#1234", "pw")

        assertTrue(http.requests.last().text.contains("\"Username\":\"harryposter#1234\""))
    }

    @Test
    fun aWrongPasswordForAHashNameIsTriedTwiceAtMost() = runTest {
        val silo = FakeSilo("silo.popcorn.test", "silojf.popcorn.test", connectInfo("https://silojf.popcorn.test"))
        val http = http(silo)

        val error = failure { SiloProvider(http.client, testIdentity).signIn("https://silo.popcorn.test", "harry#Kids", "nope#12") }

        assertEquals(ServerFailure.AUTH_REQUIRED, error.failure)
        assertEquals(2, http.requests.count { it.url.encodedPath == SiloWebsite.LOGIN_PATH })
    }

    @Test
    fun tooManyTriesIsSaidPlainly() = runTest {
        val http = TestHttp(
            status = { request -> if (request.url.encodedPath == SiloWebsite.LOGIN_PATH) 429 else 200 },
            responder = { request ->
                when (request.url.encodedPath) {
                    SiloWebsite.SYSTEM_INFO_PATH -> """{"server_version": "build-812"}"""
                    SiloWebsite.LOGIN_PATH -> """{"title": "Too Many Requests", "status": 429, "detail": "rate limited"}"""
                    else -> webPage
                }
            }
        )

        val issue = signInIssue { SiloProvider(http.client, testIdentity).signIn("https://silo.popcorn.test", "harry", "pw") }

        assertEquals(ServerSignInIssue.TOO_MANY_TRIES, issue)
        assertEquals(1, http.requests.count { it.url.encodedPath == SiloWebsite.LOGIN_PATH })
    }

    @Test
    fun aPlainHttpJellyfinAddressIsNotUsedFromAnHttpsWebsite() = runTest {
        val silo = FakeSilo("silo.popcorn.test", "silojf.popcorn.test", connectInfo("http://silojf.popcorn.test:8096"))
        val http = http(silo)

        val issue = signInIssue { SiloProvider(http.client, testIdentity).signIn("https://silo.popcorn.test", "harry", "pw") }

        assertEquals(ServerSignInIssue.SILO_WEBSITE, issue)
        assertTrue(http.requests.none { it.url.host == silo.jellyfinHost })
    }

    @Test
    fun aCertificateProblemOnTheJellyfinAddressNamesThatAddress() = runTest {
        val silo = FakeSilo("silo.popcorn.test", "silojf.popcorn.test", connectInfo("https://silojf.popcorn.test"))
        val base = http(silo)
        val http = TestHttp(
            status = { request -> base.statusFor(request) },
            responder = { request ->
                if (request.url.host == silo.jellyfinHost) throw SSLPeerUnverifiedException("self-signed")
                base.replyFor(request)
            }
        )

        val error = failure { SiloProvider(http.client, testIdentity).signIn("https://silo.popcorn.test", "harry", "pw") }

        assertEquals(ServerFailure.CERTIFICATE, error.failure)
        assertEquals("https://silojf.popcorn.test", error.address)
    }

    @Test
    fun aRedirectToAnotherHostIsNotFollowed() = runTest {
        val http = TestHttp(
            status = { request -> if (request.url.host == "silo.popcorn.test") 301 else 200 },
            location = { request -> request.url.newBuilder().host("elsewhere.test").build().toString() },
            responder = { request ->
                if (request.url.encodedPath == SiloWebsite.SYSTEM_INFO_PATH) """{"server_version": "build-812"}""" else webPage
            }
        )

        val issue = signInIssue { SiloProvider(http.client, testIdentity).signIn("https://silo.popcorn.test", "harry", "pw") }

        assertEquals(ServerSignInIssue.WEBSITE, issue)
        assertTrue(http.requests.none { it.url.host == "elsewhere.test" && it.url.encodedPath == SiloWebsite.SYSTEM_INFO_PATH })
        assertTrue(http.requests.none { it.url.encodedPath == SiloWebsite.LOGIN_PATH })
    }

    @Test
    fun loginTriesStayWithinTwo() {
        assertEquals(listOf("harry" to "pw"), SiloWebsite.loginTries("harry", "pw"))
        assertEquals(listOf("harry" to "pw", "harry" to "pw#1234"), SiloWebsite.loginTries("harry", "pw#1234"))
        assertEquals(listOf("harry" to "pw", "harry#Kids" to "pw"), SiloWebsite.loginTries("harry#Kids", "pw#1234"))
        assertEquals(listOf("harry" to "p#w", "harry#1234" to "p#w"), SiloWebsite.loginTries("harry#1234", "p#w"))
    }

    @Test
    fun aWrongPasswordOnTheWebsiteIsAPlainSignInFailure() = runTest {
        val silo = FakeSilo("silo.popcorn.test", "silojf.popcorn.test", connectInfo("https://silojf.popcorn.test"))

        val error = failure { SiloProvider(http(silo).client, testIdentity).signIn("https://silo.popcorn.test", "harry", "nope") }

        assertEquals(ServerFailure.AUTH_REQUIRED, error.failure)
        assertNull(error.issue)
    }

    @Test
    fun aWebsiteThatIsNotSiloIsCalledAWebsite() = runTest {
        val http = TestHttp { webPage }

        val issue = signInIssue { SiloProvider(http.client, testIdentity).signIn("https://example.test", "harry", "pw") }

        assertEquals(ServerSignInIssue.WEBSITE, issue)
        assertTrue(http.requests.none { it.url.encodedPath == SiloWebsite.LOGIN_PATH })
    }

    @Test
    fun aJellyfinServerTypedAsAWebsiteAddressIsCalledAWebsiteToo() = runTest {
        val http = TestHttp { webPage }

        val issue = signInIssue { JellyfinProvider(http.client, testIdentity).signIn("https://example.test", "harry", "pw") }

        assertEquals(ServerSignInIssue.WEBSITE, issue)
        assertEquals(1, http.requests.size)
    }

    @Test
    fun theWebsiteMovingToHttpsIsFollowedOnTheSameHostOnly() = runTest {
        val silo = FakeSilo("silo.popcorn.test", "silojf.popcorn.test", connectInfo("https://silojf.popcorn.test"))
        val base = http(silo)
        val http = TestHttp(
            status = { request ->
                if (!request.url.isHttps && request.url.host == silo.websiteHost) 301 else base.statusFor(request)
            },
            responder = { request -> base.replyFor(request) },
            location = { request ->
                if (!request.url.isHttps) request.url.newBuilder().scheme("https").build().toString() else null
            }
        )

        val signIn = SiloProvider(http.client, testIdentity).signIn("silo.popcorn.test", "harry", "pw")

        assertEquals("https://silojf.popcorn.test", signIn.address)
        assertTrue(http.requests.filter { it.url.encodedPath == SiloWebsite.LOGIN_PATH }.all { it.url.isHttps })
    }

    @Test
    fun silosRefusalReasonsBecomeClearMessages() = runTest {
        val reasons = mapOf(
            "username must include a profile suffix like username#profile" to ServerSignInIssue.PROFILE_NEEDED,
            "profile not found: Kidz" to ServerSignInIssue.PROFILE_NOT_FOUND,
            "profile is PIN protected: use password#pin format to access PIN-protected profiles" to ServerSignInIssue.PROFILE_PIN,
            "profile is PIN protected: use username#profile and password#pin format" to ServerSignInIssue.PROFILE_AND_PIN,
            "invalid profile PIN: incorrect PIN for profile Kids" to ServerSignInIssue.PIN_WRONG,
            "password change required: sign in to Silo to replace your temporary password" to ServerSignInIssue.TEMPORARY_PASSWORD,
            "local password sign-in is turned off on this server" to ServerSignInIssue.PASSWORD_SIGN_IN_OFF,
            "Invalid username or password" to null
        )
        reasons.forEach { (message, expected) ->
            val http = TestHttp(
                status = { request -> if (request.url.encodedPath == "/Users/AuthenticateByName") 401 else 200 },
                responder = { request ->
                    if (request.url.encodedPath == "/System/Info/Public") publicInfo
                    else """{"Error": "InvalidUsernameOrPassword", "Message": "$message"}"""
                }
            )
            val error = failure { SiloProvider(http.client, testIdentity).signIn("http://192.168.1.20:8096", "harry", "pw") }
            assertEquals(message, ServerFailure.AUTH_REQUIRED, error.failure)
            assertEquals(message, expected, error.issue)
        }
    }

    @Test
    fun theJellyfinCandidateSkipsAddressesThatOnlyWorkOnTheServerItself() {
        assertEquals("https://jf.example.test", SiloWebsite.jellyfinCandidate("https://silo.example.test", "https://jf.example.test/"))
        assertEquals("http://10.0.0.5:8096", SiloWebsite.jellyfinCandidate("http://10.0.0.5:8090", "http://127.0.0.1:8096"))
        assertEquals("http://nas.local:8096", SiloWebsite.jellyfinCandidate("http://nas.local:8090/silo", "http://localhost:8096"))
        assertEquals("http://10.0.0.5:8096", SiloWebsite.jellyfinCandidate("http://10.0.0.5:8090", ""))
        assertNull(SiloWebsite.jellyfinCandidate("https://silo.example.test", "http://[::1]:8096"))
        assertNull(SiloWebsite.jellyfinCandidate("https://silo.example.test", null))
    }

    @Test
    fun theWebsiteSignInUsesTheAccountNameWithoutTheProfile() {
        assertEquals("harry", SiloWebsite.accountName("harry#Kids"))
        assertEquals("har#ry", SiloWebsite.accountName("har#ry#Kids"))
        assertEquals("harry", SiloWebsite.accountName(" harry "))
        assertEquals("#Kids", SiloWebsite.accountName("#Kids"))
    }

    private suspend fun failure(block: suspend () -> Unit): ServerException {
        try {
            block()
        } catch (error: ServerException) {
            return error
        }
        fail("Expected a sign-in failure")
        throw AssertionError()
    }

    private suspend fun signInIssue(block: suspend () -> Unit): ServerSignInIssue? = failure(block).issue
}
