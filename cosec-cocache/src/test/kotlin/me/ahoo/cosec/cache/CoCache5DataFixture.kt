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

package me.ahoo.cosec.cache

import me.ahoo.cosec.api.policy.Effect
import me.ahoo.cosec.api.policy.PolicyType
import me.ahoo.cosec.permission.AppPermissionData
import me.ahoo.cosec.policy.PolicyData
import me.ahoo.cosec.policy.StatementData
import me.ahoo.cosec.policy.action.AllActionMatcher
import me.ahoo.cosec.serialization.CoSecJsonSerializer
import org.springframework.data.redis.core.StringRedisTemplate
import java.time.Duration

/**
 * Writes Redis data exactly as CoSec 6.x stored it with CoCache 5, under [keyPrefix].
 */
class CoCache5DataFixture(private val redis: StringRedisTemplate, private val keyPrefix: String) {
    val globalDeny = PolicyData(
        "global-deny",
        "c",
        "global-deny",
        "",
        PolicyType.GLOBAL,
        "t",
        statements = listOf(StatementData(effect = Effect.DENY, action = AllActionMatcher.INSTANCE)),
    )
    val customAllow = PolicyData(
        "custom-allow",
        "c",
        "custom-allow",
        "",
        PolicyType.CUSTOM,
        "t",
        statements = listOf(StatementData(effect = Effect.ALLOW, action = AllActionMatcher.INSTANCE)),
    )
    val appPermission = AppPermissionData(id = "app")

    fun write() {
        val values = redis.opsForValue()
        values.set("$keyPrefix:policy:global-deny", CoSecJsonSerializer.writeValueAsString(globalDeny))
        values.set("$keyPrefix:policy:custom-allow", CoSecJsonSerializer.writeValueAsString(customAllow))
        // a cached miss (missing guard)
        values.set("$keyPrefix:policy:missing", MISSING_GUARD, Duration.ofMinutes(1))
        // policy history shares the prefix: a revision list and its counter, not CoCache data
        redis.opsForList().leftPush("$keyPrefix:policy:history:custom-allow", "{}")
        values.set("$keyPrefix:policy:history:custom-allow:seq", "1")
        redis.opsForSet().add("$keyPrefix:global:policy", "global-deny")
        values.set("$keyPrefix:app:permission:app", CoSecJsonSerializer.writeValueAsString(appPermission))
        values.set("$keyPrefix:token:revoked:jti", "true", Duration.ofMinutes(10))
        // role grants: migrated by RolePermissionKeyMigration, never by the CoCache migrator
        redis.opsForSet().add("$keyPrefix:role:permission:admin", "app.manage")
    }

    companion object {
        const val MISSING_GUARD = "_nil_"
    }
}
