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

package me.ahoo.cosec.cocache

import kotlinx.coroutines.runBlocking
import me.ahoo.cocache.api.Codec
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.core.CoCacheFactory
import me.ahoo.cocache.core.KeyEncoder
import me.ahoo.cocache.core.StoreSpec
import me.ahoo.cocache.core.policy.UnknownReadMode
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.document.json.CoSecJson
import me.ahoo.cosec.login.token.SessionStoreUnavailableException
import me.ahoo.cosec.login.token.TokenFamilies
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.spi.identity.Identity.Credential.Kind.PERSON
import me.ahoo.cosec.spi.token.TokenFamily
import me.ahoo.cosec.spi.token.VerifiedToken
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import tools.jackson.databind.node.ObjectNode

/**
 * `IDN-40` and `SNP-27` over the real store on Redis (beta.9 review A-5): a login whose session record a later version
 * wrote, its `crit` naming a member this version does not know, is refused by every reader of it here: a refresh, a
 * logout of the login, and a logout of a session switched from it (which reads its root first). Each is answered as the
 * session store being unavailable (`503`), nothing is revoked, and the record stays as the later version wrote it.
 */
class LaterSessionRecordStoreTest {
    @Volatile
    private var readCrit = false

    /** A later version's codec: this version's members, and one more that `crit` names. */
    private val later = object : Codec<TokenFamily> {
        override fun encode(value: TokenFamily): ByteArray {
            val node = CoSecJson.mapper.readTree(TokenFamilyCodec.encode(value)) as ObjectNode
            node.put("device", "d1")
            node.putArray("crit").add("device")
            return CoSecJson.mapper.writeValueAsBytes(node)
        }

        override fun decode(bytes: ByteArray): TokenFamily {
            val node = CoSecJson.mapper.readTree(bytes) as ObjectNode
            readCrit = node.has("crit") && node.has("device")
            node.remove("device")
            node.remove("crit")
            return TokenFamilyCodec.decode(CoSecJson.mapper.writeValueAsBytes(node))
        }
    }

    private val redis = CoSecTestCaches.redisBackend()
    private val older = CoSecTestCaches(redis, CoSecTestCaches.newKeyPrefix())
    private val laterRedis = CoSecTestCaches.redisBackend()
    private val laterRuntime = CacheRuntime.builder().backend(laterRedis).build()

    @AfterEach
    fun close() {
        laterRuntime.close()
        older.close()
        redis.close()
        laterRedis.close()
    }

    private suspend fun refused(block: suspend () -> Unit): Throwable? = try {
        block()
        null
    } catch (error: SessionStoreUnavailableException) {
        error
    }

    @Test
    fun `a refresh, a logout and a switched session's logout meet a later record, refused and never rewritten - IDN-40`() =
        runBlocking<Unit> {
            val base = CoSecCacheSpecs.tokenFamily(older.keyPrefix)
            val laterSpec = StoreSpec.builder<String, TokenFamily>(CoSecCacheSpecs.TOKEN_FAMILY_CACHE_NAME)
                .keyPrefix(base.keyPrefix).namespace(base.namespace).keyEncoder(KeyEncoder.ofToString())
                .codec(later).unknownRead(UnknownReadMode.THROW).build()
            val laterStore = CoCacheTokenFamilyStore(CoCacheFactory(laterRuntime).createLocked(laterSpec))
            val olderStore = CoCacheTokenFamilyStore(older.tokenFamilyCache)
            val families = TokenFamilies(olderStore)
            val root = families.start(TenantId("acme"), PrincipalId("alice")).session
            val switched = families.startAt(TenantId("beta"), PrincipalId("alice"), root.authTime, root.id).session
            // The later version rewrites the login's record with a member its crit names.
            val read = checkNotNull(laterStore.read(root.id))
            laterStore.replace(read.version, read.family).assert().isTrue()
            older.clearLocal(CoSecCacheSpecs.TOKEN_FAMILY_CACHE_NAME)

            refused { families.rotate(VerifiedToken("jti-1", "alice", "acme", null, PERSON, session = root)) }
                .assert().describedAs("a refresh of the login").isNotNull()
            refused { families.logout(root.id) }.assert().describedAs("a logout of the login").isNotNull()
            refused {
                families.logout(
                    switched.id
                )
            }.assert().describedAs("a logout of the switched session").isNotNull()

            readCrit = false
            checkNotNull(laterStore.read(root.id)).family.revoked.assert().describedAs("the login").isFalse()
            readCrit.assert().describedAs("the login's record still holds crit").isTrue()
            checkNotNull(
                olderStore.read(switched.id)
            ).family.revoked.assert().describedAs("the switched session").isFalse()
        }
}
