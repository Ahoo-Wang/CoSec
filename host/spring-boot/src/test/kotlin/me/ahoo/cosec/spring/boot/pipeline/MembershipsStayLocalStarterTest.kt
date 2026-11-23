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

package me.ahoo.cosec.spring.boot.pipeline

import kotlinx.coroutines.runBlocking
import me.ahoo.cocache.spring.boot.CoCacheAutoConfiguration
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.cocache.CoSecCacheSpecs
import me.ahoo.cosec.cocache.MembershipLoader
import me.ahoo.cosec.cocache.RevokedSubjectCache
import me.ahoo.cosec.model.Memberships
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.RoleId
import me.ahoo.cosec.model.SpaceId
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.spi.membership.MembershipAnswer
import me.ahoo.cosec.spi.membership.MembershipProvider
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.data.redis.core.StringRedisTemplate
import java.util.UUID

/**
 * `MEM-5`, DR1-D-2: as the starter wires it on real Redis, the membership provider answers what the application's loader
 * said, never a record in Redis. A membership record at the key the 8.0 previews' shared cache used (what anyone holding
 * a data-plane credential could write) is not read, and answering writes no `membership:*` key.
 */
class MembershipsStayLocalStarterTest {
    private val keyPrefix = "mem5-${UUID.randomUUID()}"
    private val tenant = TenantId("acme")
    private val alice = PrincipalId("alice")

    private val runner = ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                JacksonAutoConfiguration::class.java,
                DataRedisAutoConfiguration::class.java,
                CoCacheAutoConfiguration::class.java,
                CoSecMembershipAutoConfiguration::class.java,
            ),
        )
        .withPropertyValues("cosec.key-prefix=$keyPrefix")
        .withBean(MembershipLoader::class.java, {
            MembershipLoader { _, _ ->
                MembershipAnswer.Known(
                    Memberships(
                        Memberships.Source.PROVIDER,
                        mapOf(SpaceId.DEFAULT to setOf(RoleId("viewer"))),
                        emptyList()
                    )
                )
            }
        })

    @Test
    fun `a membership record in Redis is never the provider's answer, and none is written`() {
        runner.run { context ->
            context.startupFailure.assert().isNull()
            val redis = context.getBean(StringRedisTemplate::class.java)
            val path = CoSecCacheSpecs.membershipPath(keyPrefix)
            val seeded = "$path:${RevokedSubjectCache.key(tenant, alice)}"
            redis.opsForValue().set(seeded, """{"roles":{"":["admin"]},"attachments":[]}""")
            try {
                val answer = runBlocking { context.getBean(MembershipProvider::class.java).memberships(tenant, alice) }
                (answer as MembershipAnswer.Known).memberships.roles
                    .assert().isEqualTo(mapOf(SpaceId.DEFAULT to setOf(RoleId("viewer"))))
                redis.keys("$path*").assert().containsExactly(seeded)
            } finally {
                redis.delete(seeded)
            }
        }
    }
}
