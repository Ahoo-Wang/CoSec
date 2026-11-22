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
import me.ahoo.cosec.runtime.HeapBudget
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.openjdk.jol.info.GraphLayout

/**
 * What an entry of the cache retains for each character of its token (`IDN-32`), measured with JOL over [MEASURED]
 * entries of each of two shapes: the target deployment's largest token (a dozen roles in each of four spaces, eight
 * policies, nested attributes) and a small one (one role). The cache weighs an entry by its token's length times
 * [VerifiedTokenCache.BYTES_PER_TOKEN_CHAR] when it puts it, never when it reads it: this test holds the factor to the
 * larger of the two measurements, never below it and at most twice it, and checks that the token cache's share of the
 * production overlay's 2 GiB heap holds the 16,384 large tokens its old default did.
 */
class VerifiedTokenCacheSizeTest {
    private fun large(principal: Int) = TokenSubject(
        id = "user-$principal",
        app = "order",
        tenant = "acme",
        roles = (1..SPACES).flatMapTo(LinkedHashSet()) { space -> (1..ROLES).map { "role-$it@space-$space" } },
        policies = (1..POLICIES).mapTo(LinkedHashSet()) { "policy-$it" },
        attributes = mapOf(
            "department" to "finance",
            "profile" to mapOf("level" to 3, "regions" to listOf("north", "south"), "flags" to mapOf("beta" to true)),
        ),
    )

    private fun small(principal: Int) = TokenSubject(
        id = "user-$principal",
        app = "order",
        tenant = "acme",
        roles = setOf("viewer")
    )

    /** Retained bytes per token character, and the token's length, over [MEASURED] tokens of [subject]. */
    private fun measure(subject: (Int) -> TokenSubject): Pair<Long, Int> {
        val converter = JwtTokenConverter(JwtFixture.IDS, JwtFixture.SIGNING)
        val cache = VerifiedTokenCache(Long.MAX_VALUE)
        val verifier = JwtTokenVerifier(JwtFixture.SIGNING).cached(cache)
        val empty = GraphLayout.parseInstance(cache).totalSize()
        var chars = 0L
        var length = 0
        repeat(MEASURED) {
            val token = converter.toToken(subject(it)).accessToken
            chars += token.length
            length = token.length
            verifier.verify(token)
        }
        cache.size.assert().isEqualTo(MEASURED)
        val retained = GraphLayout.parseInstance(cache).totalSize() - empty
        return Pair((retained + chars - 1) / chars, length)
    }

    @Test
    fun `an entry weighs what it retains, and the share of a 2 GiB heap holds the large tokens the old bound did`() {
        val (largePerChar, largeLength) = measure(::large)
        val (smallPerChar, smallLength) = measure(::small)
        println(
            "VerifiedTokenCache: a large token of $largeLength chars retains $largePerChar B a char, a small one of " +
                "$smallLength chars $smallPerChar B a char; the factor is ${VerifiedTokenCache.BYTES_PER_TOKEN_CHAR}"
        )
        val measured = maxOf(largePerChar, smallPerChar)
        VerifiedTokenCache.BYTES_PER_TOKEN_CHAR.assert().isGreaterThanOrEqualTo(
            measured
        ).isLessThanOrEqualTo(measured * 2)
        val share = HeapBudget.of(HEAP).tokenCache
        (share / (largeLength * VerifiedTokenCache.BYTES_PER_TOKEN_CHAR)).assert().isGreaterThanOrEqualTo(OLD_BOUND)
    }

    private companion object {
        const val SPACES = 4
        const val ROLES = 12
        const val POLICIES = 8
        const val MEASURED = 1_000
        const val OLD_BOUND = 16_384L
        const val HEAP = 2L shl 30
    }
}
