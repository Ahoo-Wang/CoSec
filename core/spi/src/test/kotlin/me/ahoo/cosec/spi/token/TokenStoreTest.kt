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

package me.ahoo.cosec.spi.token

import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.TenantId
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration
import java.time.Instant

class TokenStoreTest {

    /** A store that cannot record a principal's revocation throws, so an emergency revocation never does nothing. */
    @Test
    fun aStoreWithoutSubjectRevocationThrows() {
        val store = object : TokenStore {
            override suspend fun revoke(tokenId: String, ttl: Duration) = Unit
        }
        assertThrows<UnsupportedOperationException> {
            runBlocking { store.revokeSubject(TenantId("t"), PrincipalId("alice"), Instant.now(), Duration.ofDays(7)) }
        }
    }
}
