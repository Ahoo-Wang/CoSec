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
import me.ahoo.cosec.cocache.CoCacheTokenFamilyStore
import me.ahoo.cosec.cocache.CoSecCacheSpecs
import me.ahoo.cosec.cocache.createLocked
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.spi.token.TokenFamily
import java.time.Instant

/**
 * Session records (MIX-4) through this version's store over Redis, under the key prefix `prefix`: `session.create`,
 * `session.read`, and `session.rotate`, the read and conditional rewrite a refresh makes. `raw.put` and `raw.get`
 * write and read any store of record's value as bytes (text), as a later version would have written it.
 */
internal object SessionOperations {
    private val AUTH: Instant = Instant.parse("2026-11-01T00:00:00Z")
    private val EXPIRES: Instant = Instant.parse("2099-01-01T00:00:00Z")

    val ALL: Map<String, (Map<String, String>) -> String> = mapOf(
        "session.create" to { a ->
            val family = TokenFamily(
                a.getValue("id"), TenantId("acme"), PrincipalId("alice"), AUTH, EXPIRES, 1, null, false, a["root"],
            )
            store(a) { it.create(family).toString() }
        },
        "session.read" to { a -> store(a) { it.read(a.getValue("id"))?.family?.let(::describe) ?: "none" } },
        "session.rotate" to { a ->
            store(a) { store ->
                val read = store.read(a.getValue("id"))
                if (read == null) "none" else store.replace(read.version, read.family.rotated(AUTH.plusSeconds(60))).toString()
            }
        },
        "raw.put" to { a ->
            Redis.caches { factory ->
                Redis.raw(factory, a.getValue("cache"), a.getValue("prefix")).put(a.getValue("key"), a.getValue("value").encodeToByteArray())
            }
            "ok"
        },
        "raw.get" to { a ->
            Redis.caches { factory ->
                Redis.raw(factory, a.getValue("cache"), a.getValue("prefix")).get(a.getValue("key"))?.decodeToString() ?: "none"
            }
        },
    )

    private fun store(arguments: Map<String, String>, block: suspend (CoCacheTokenFamilyStore) -> String): String =
        Redis.caches { factory ->
            val store = CoCacheTokenFamilyStore(factory.createLocked(CoSecCacheSpecs.tokenFamily(arguments.getValue("prefix"))))
            runBlocking { block(store) }
        }

    private fun describe(family: TokenFamily): String =
        "id=${family.id};tenant=${family.tenant.value};principal=${family.principal.value};generation=${family.generation};" +
            "rotated=${family.rotatedAt != null};revoked=${family.revoked};root=${family.root}"
}
