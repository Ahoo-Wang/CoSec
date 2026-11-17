/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *      http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package me.ahoo.cosec.clienthost

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import tools.jackson.databind.json.JsonMapper
import java.net.ConnectException
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpRequest.BodyPublishers
import java.net.http.HttpResponse

/**
 * The host answers as the client's tests assume, before a TypeScript test relies on it: the sign-in issues a pair, a
 * permitted route answers, a denied one is a problem, and the refresh route rotates.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ClientHostTest {
    private companion object {
        const val CONNECT_TIMEOUT_MILLIS = 2_000
    }

    private lateinit var host: ClientHost
    private val http = HttpClient.newHttpClient()
    private val json = JsonMapper()

    @BeforeAll
    fun start() {
        host = ClientHost.start()
    }

    @AfterAll
    fun stop() = host.close()

    private fun send(method: String, path: String, body: String? = null, bearer: String? = null): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI.create(host.url + path))
            .method(method, body?.let(BodyPublishers::ofString) ?: BodyPublishers.noBody())
        if (body != null) request.header("Content-Type", "application/json")
        if (bearer != null) request.header("Authorization", "Bearer $bearer")
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun signIn(user: String): Map<String, String> {
        val response = send("POST", "/auth/login", """{"username":"$user"}""")
        response.statusCode().assert().isEqualTo(200)
        @Suppress("UNCHECKED_CAST")
        return json.readValue(response.body(), Map::class.java) as Map<String, String>
    }

    @Test
    fun anyoneReachesAPublicRoute() {
        send("GET", "/hello").statusCode().assert().isEqualTo(200)
    }

    @Test
    fun aSignedInCallerReachesTheirRoutesAndNobodyDeletes() {
        val pair = signIn("alice")
        send("GET", "/orders/1", bearer = pair.getValue("accessToken")).statusCode().assert().isEqualTo(200)
        send("GET", "/orders/1").statusCode().assert().isEqualTo(401)
        val denied = send("DELETE", "/orders/1", bearer = pair.getValue("accessToken"))
        denied.statusCode().assert().isEqualTo(403)
        denied.headers().firstValue("Content-Type").get().assert().startsWith("application/problem+json")
    }

    @Test
    fun theRefreshRouteRotatesAPair() {
        val pair = signIn("bob")
        val refreshed = send("POST", "/auth/refresh", json.writeValueAsString(pair))
        refreshed.statusCode().assert().isEqualTo(200)
        refreshed.body().assert().isNotEqualTo(json.writeValueAsString(pair))
    }

    @Test
    fun aPairPresentedAgainAfterItsRotationIsRefused() {
        // Rotated twice, so the first pair is two generations behind: past the rotation leeway's one generation (IDN-16).
        val first = signIn("erin")
        val second = refresh(first)
        refresh(second)
        send("POST", "/auth/refresh", json.writeValueAsString(first)).statusCode().assert().isEqualTo(401)
    }

    @Test
    fun aSessionEndedThroughTheHostRefusesAPairThatWasNeverRotated() {
        val pair = signIn("frank")
        val body = json.writeValueAsString(pair)
        send("POST", "/test/end-session", body).statusCode().assert().isEqualTo(200)
        send("POST", "/auth/refresh", body).statusCode().assert().isEqualTo(401)
    }

    @Test
    fun aRefreshThatCannotReadItsStoreIsUnavailableOnceAndKeepsThePair() {
        val pair = signIn("gina")
        val body = json.writeValueAsString(pair)
        send("POST", "/test/fail-next-refresh").statusCode().assert().isEqualTo(200)
        send("POST", "/auth/refresh", body).statusCode().assert().isEqualTo(503)
        // Nothing was issued and nothing was spent: the same pair refreshes.
        send("POST", "/auth/refresh", body).statusCode().assert().isEqualTo(200)
    }

    @Test
    fun theHostListensOnTheLoopbackInterfaceOnly() {
        Socket().use { it.connect(InetSocketAddress(ADDRESS, host.port), CONNECT_TIMEOUT_MILLIS) }
        val other = NetworkInterface.networkInterfaces().toList().flatMap { it.inetAddresses.toList() }
            .firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress && it is Inet4Address }
        Assumptions.assumeTrue(other != null, "this machine has no address besides the loopback")
        assertThrows<ConnectException> {
            Socket().use { it.connect(InetSocketAddress(other, host.port), CONNECT_TIMEOUT_MILLIS) }
        }
    }

    private fun refresh(pair: Map<String, String>): Map<String, String> {
        val response = send("POST", "/auth/refresh", json.writeValueAsString(pair))
        response.statusCode().assert().isEqualTo(200)
        @Suppress("UNCHECKED_CAST")
        return json.readValue(response.body(), Map::class.java) as Map<String, String>
    }

    @Test
    fun anExpiredSignInIsRefusedAsExpiredAndItsPairStillRefreshes() {
        val pair = json.readValue(
            send("POST", "/test/login-expired", """{"username":"dave"}""").body(),
            Map::class.java,
        ).mapKeys { it.key.toString() }.mapValues { it.value.toString() }
        val refused = send("GET", "/orders/1", bearer = pair.getValue("accessToken"))
        refused.statusCode().assert().isEqualTo(401)
        refused.body().assert().contains("token-expired")
        send("POST", "/auth/refresh", json.writeValueAsString(pair)).statusCode().assert().isEqualTo(200)
    }

    @Test
    fun theHostCountsWhatReachedIt() {
        send("POST", "/test/reset")
        signIn("carol")
        val stats = json.readTree(send("GET", "/test/stats").body())
        stats.get("logins").asInt().assert().isEqualTo(1)
        stats.get("refreshes").asInt().assert().isEqualTo(0)
    }
}
