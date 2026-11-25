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

@file:OptIn(InternalCoSecApi::class)

package me.ahoo.cosec.jwt

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.login.token.TokenSubject
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.runtime.HeapBudget
import me.ahoo.cosec.runtime.authn.TokenAuthenticator
import me.ahoo.cosec.spi.authn.AuthnResult
import me.ahoo.cosec.spi.authn.Credentials
import me.ahoo.cosec.spi.id.RandomIdGenerator
import me.ahoo.cosec.spi.token.RevocationReader
import me.ahoo.cosec.spi.token.VerifiedToken
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import me.ahoo.cosec.spi.identity.Identity

/**
 * What it costs to accept a CoSec access token on a request: [verify] checks the signature and `exp` and reads the
 * claims into a `VerifiedToken` (nested attribute claims are copied read-only, #1343); [authenticate] is what the
 * pipeline's `TokenAuthenticator` does with it, the verification plus both revocation reads (the token's id and its
 * principal's) and the principal it makes. The revocations are a map read here, as a local copy answers them: Redis is
 * not measured.
 *
 * [algorithm] is each one `cosec.jwt.algorithm` can name: 8.0 signs with HMAC only (an asymmetric algorithm comes with
 * the 8.1 external identity provider work). [claims] is a token of a bare principal, or one with roles in two spaces,
 * attached policies and nested attributes, as a downstream framework issues, or `large`: a member of the target
 * deployment's largest tenant, a dozen roles in each of four spaces, eight policies and the same attributes.
 *
 * [digest] is SHA-256 over the token as a request carries it: the least a cache of verified tokens keyed by a digest of
 * the whole token pays on a hit, beside a map read (round 2, R2B-22). [verifyCached] and [authenticateCached] are what
 * the starter's authenticator does by default since R2B-22: the same token again, answered from [VerifiedTokenCache]
 * after its first verification; [verify] and [authenticate] stay the verification without one, which refresh, logout
 * and a disabled cache pay.
 *
 * Run: `./gradlew :jwt:jmh -PjmhIncludes=JwtVerificationBenchmark` (a smoke run); for numbers, the options in
 * `wiki/architecture/overview.md#measuring`.
 */
@State(Scope.Benchmark)
open class JwtVerificationBenchmark {
    @Param("HS256", "HS512")
    var algorithm: String = ""

    @Param("bare", "rich", "large")
    var claims: String = ""

    private lateinit var verifier: JwtTokenVerifier
    private lateinit var authenticator: TokenAuthenticator
    private lateinit var cachedVerifier: JwtTokenVerifier
    private lateinit var cachedAuthenticator: TokenAuthenticator
    private lateinit var accessToken: String
    private lateinit var credentials: Credentials
    private val sha256: MessageDigest = MessageDigest.getInstance("SHA-256")

    /** Revocations as a local copy holds them: none of these tokens is revoked. */
    private object LocalRevocations : RevocationReader {
        private val tokens = ConcurrentHashMap<String, Boolean>()
        private val subjects = ConcurrentHashMap<String, Instant>()

        override suspend fun isRevoked(tokenId: String): Boolean = tokens[tokenId] == true

        override suspend fun subjectRevokedBefore(tenant: TenantId, principal: PrincipalId): Instant? =
            subjects["${tenant.value}:${principal.value}"]
    }

    @Setup
    fun setup() {
        val signing = when (algorithm) {
            "HS256" -> JwtSigning.hmac256(SECRET.take(HS256_SECRET_BYTES))
            "HS512" -> JwtSigning.hmac512(SECRET)
            else -> error("Unknown algorithm $algorithm")
        }
        val subject = when (claims) {
            "bare" -> TokenSubject("alice", Identity.Credential.Kind.PERSON, app = "order")
            "rich" -> TokenSubject(
                id = "alice",
                kind = Identity.Credential.Kind.PERSON,
                app = "order",
                tenant = "acme",
                roles = setOf("reader", "writer", "picker@north", "picker@south"),
                policies = setOf("orders-read", "orders-write"),
                attributes = mapOf(
                    "department" to "finance",
                    "profile" to mapOf("level" to 3, "regions" to listOf("north", "south"), "flags" to mapOf("beta" to true)),
                ),
            )
            "large" -> TokenSubject(
                id = "alice",
                kind = Identity.Credential.Kind.PERSON,
                app = "order",
                tenant = "acme",
                roles = (1..LARGE_SPACES).flatMapTo(LinkedHashSet()) { space -> (1..LARGE_ROLES).map { "role-$it@space-$space" } },
                policies = (1..LARGE_POLICIES).mapTo(LinkedHashSet()) { "policy-$it" },
                attributes = mapOf(
                    "department" to "finance",
                    "profile" to mapOf("level" to 3, "regions" to listOf("north", "south"), "flags" to mapOf("beta" to true)),
                ),
            )
            else -> error("Unknown claims $claims")
        }
        accessToken = JwtTokenConverter(RandomIdGenerator, signing).toToken(subject).accessToken
        verifier = JwtTokenVerifier(signing)
        authenticator = TokenAuthenticator(verifier, LocalRevocations)
        cachedVerifier = verifier.cached(VerifiedTokenCache(HeapBudget.of().tokenCache))
        cachedAuthenticator = TokenAuthenticator(cachedVerifier, LocalRevocations)
        credentials = Credentials("Bearer $accessToken", null)
        check(authenticate() is AuthnResult.Authenticated) { "The benchmark's token does not authenticate." }
        check(authenticateCached() is AuthnResult.Authenticated) { "The benchmark's token does not authenticate." }
        System.err.println("JwtVerificationBenchmark: $claims token of ${accessToken.length} characters")
    }

    @Benchmark
    fun verify(): VerifiedToken = verifier.verify(accessToken)

    @Benchmark
    fun verifyCached(): VerifiedToken = cachedVerifier.verify(accessToken)

    @Benchmark
    fun digest(): ByteArray = sha256.digest(accessToken.toByteArray(Charsets.US_ASCII))

    @Benchmark
    fun authenticate(): AuthnResult = completed { authenticator.authenticate(credentials) }

    @Benchmark
    fun authenticateCached(): AuthnResult = completed { cachedAuthenticator.authenticate(credentials) }

    private companion object {
        /** 64 bytes: what `hmac512` needs at least (RFC 7518 §3.2); `hmac256` takes the first 32. */
        const val SECRET = "jwt-verification-benchmark-secret-0123456789abcdef0123456789abcd"
        const val HS256_SECRET_BYTES = 32
        const val LARGE_SPACES = 4
        const val LARGE_ROLES = 12
        const val LARGE_POLICIES = 8

        /**
         * Runs [block], which never suspends here (the revocations are a map), on the calling thread, without the
         * event loop `runBlocking` would add to every call.
         */
        fun <T> completed(block: suspend () -> T): T {
            var result: Result<T>? = null
            block.startCoroutine(Continuation(EmptyCoroutineContext) { result = it })
            return checkNotNull(result) { "The call suspended." }.getOrThrow()
        }
    }
}
