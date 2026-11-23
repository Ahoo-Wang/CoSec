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

package me.ahoo.cosec.spring.boot

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.StandardEnvironment

/** DR1-D-2: a setting of the retired memberships cache fails startup, naming the property that replaced it. */
class RetiredCacheSettingsTest {
    private fun environment(vararg keys: String) = StandardEnvironment().apply {
        propertySources.addFirst(MapPropertySource("test", keys.associateWith { "5m" }))
    }

    @Test
    fun `every spelling of a retired MembershipCache setting fails, naming its replacement`() {
        for (key in listOf(
            "cocache.caches.MembershipCache.ttl",
            "cocache.caches.[MembershipCache].ttl",
            "COCACHE_CACHES_MEMBERSHIPCACHE_TTL",
        )) {
            assertThrows<IllegalStateException> { RetiredCacheSettings.check(environment(key)) }
                .message.assert().contains(key).contains("cosec.authorization.membership.ttl")
        }
    }

    @Test
    fun `other caches' settings pass`() {
        RetiredCacheSettings.check(
            environment("cocache.caches.RevokedTokenCache.local-maximum-size", "cosec.key-prefix")
        )
        RetiredCacheSettings.of(StandardEnvironment()).assert().isEmpty()
    }
}
