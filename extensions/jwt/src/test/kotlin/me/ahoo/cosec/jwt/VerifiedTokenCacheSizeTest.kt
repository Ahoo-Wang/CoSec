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
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.openjdk.jol.info.GraphLayout

/**
 * What the cache's default bound costs (R2B-22), measured with JOL: the retained size of an entry of the target
 * deployment's largest token (a dozen roles in each of four spaces, eight policies, nested attributes), its key and
 * the map's node. Every entry of that size, the default bound holds at most a tenth of the production overlay's 2 GB
 * heap. Printed, for `configuration.md`.
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

    @Test
    fun `the default bound of large tokens stays under a tenth of a 2 GB heap`() {
        val converter = JwtTokenConverter(JwtFixture.IDS, JwtFixture.SIGNING)
        val cache = VerifiedTokenCache()
        val verifier = JwtTokenVerifier(JwtFixture.SIGNING).cached(cache)
        val empty = GraphLayout.parseInstance(cache).totalSize()
        repeat(MEASURED) { verifier.verify(converter.toToken(large(it)).accessToken) }
        cache.size.assert().isEqualTo(MEASURED)
        val perEntry = (GraphLayout.parseInstance(cache).totalSize() - empty) / MEASURED
        val worstCase = perEntry * VerifiedTokenCache.DEFAULT_MAX_ENTRIES
        println(
            "VerifiedTokenCache: a large token's entry retains $perEntry B; " +
                "${VerifiedTokenCache.DEFAULT_MAX_ENTRIES} of them ${worstCase / MIB} MiB"
        )
        worstCase.assert().isLessThan(HEAP / 10)
    }

    private companion object {
        const val SPACES = 4
        const val ROLES = 12
        const val POLICIES = 8
        const val MEASURED = 1_000
        const val MIB = 1L shl 20
        const val HEAP = 2L shl 30
    }
}
