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

@file:OptIn(InternalCoSecApi::class, ExperimentalCoSecApi::class)

package me.ahoo.cosec.compat.side

import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.cocache.CoCacheTokenStore
import me.ahoo.cosec.cocache.CoSecCacheSpecs
import me.ahoo.cosec.cocache.RedisApiKeyStore
import me.ahoo.cosec.cocache.RevokedSubjectCache
import me.ahoo.cosec.cocache.RevokedTokenCache
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.spi.apikey.ApiKey
import java.time.Duration
import java.time.Instant

/**
 * Revocations (MIX-6) and API keys (MIX-7) through this version's stores over Redis, under the key prefix `prefix`.
 */
internal object CredentialOperations {
    private val TTL: Duration = Duration.ofMinutes(10)

    val ALL: Map<String, (Map<String, String>) -> String> = mapOf(
        "revoke.token" to { a -> tokens(a) { it.revoke(a.getValue("id"), TTL); "ok" } },
        "revoke.subject" to { a ->
            tokens(a) {
                it.revokeSubject(TenantId("acme"), PrincipalId(a.getValue("principal")), Instant.parse(a.getValue("before")), TTL)
                "ok"
            }
        },
        "revoked.token" to { a -> tokens(a) { it.reader.isRevoked(a.getValue("id")).toString() } },
        "revoked.subject" to { a ->
            tokens(a) { it.reader.subjectRevokedBefore(TenantId("acme"), PrincipalId(a.getValue("principal"))).toString() }
        },
        "apikey.save" to { a ->
            val key = when (a.getValue("sample")) {
                "capped" -> ApiKey("k1", "alice", "acme", "order", setOf("clerk"), Instant.parse("2099-01-01T00:00:00Z"))
                "uncapped" -> ApiKey("k2", "svc", "acme", null, null, null, false)
                else -> throw IllegalArgumentException("no sample")
            }
            RedisApiKeyStore(Redis.strings, a.getValue("prefix")).save(a.getValue("hash"), key)
            "ok"
        },
        "apikey.read" to { a ->
            RedisApiKeyStore(Redis.strings, a.getValue("prefix")).findByHash(a.getValue("hash"))?.let {
                "id=${it.id};principal=${it.principalId};tenant=${it.tenantId};app=${it.app};roles=${it.roles?.sorted()};" +
                    "expires=${it.expiresAt};enabled=${it.enabled}"
            } ?: "none"
        },
    )

    private fun tokens(arguments: Map<String, String>, block: suspend (CoCacheTokenStore) -> String): String =
        Redis.caches { factory ->
            val prefix = arguments.getValue("prefix")
            val token = CoSecCacheSpecs.revokedToken(prefix)
            val subject = CoSecCacheSpecs.revokedSubject(prefix)
            val store = CoCacheTokenStore(
                factory.create(RevokedTokenCache::class.java, token, CoSecCacheSpecs.locks(token.name)),
                factory.create(RevokedSubjectCache::class.java, subject, CoSecCacheSpecs.locks(subject.name)),
            )
            runBlocking { block(store) }
        }
}
