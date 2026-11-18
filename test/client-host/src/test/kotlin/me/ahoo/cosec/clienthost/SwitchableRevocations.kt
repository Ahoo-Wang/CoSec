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

package me.ahoo.cosec.clienthost

import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.spi.token.RevocationReader
import me.ahoo.cosec.spi.token.RevocationUnavailableException
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The host's [RevocationReader]: CoCache's, until a test takes it down. Down, every read throws
 * [RevocationUnavailableException], as a reader whose store cannot be reached does, and the token whose read failed is
 * recorded, so a test says which read a refresh failed on.
 */
internal class SwitchableRevocations(private val reader: RevocationReader) : RevocationReader {
    @Volatile
    var down = false

    val failedReads: MutableList<String> = CopyOnWriteArrayList()

    override suspend fun isRevoked(tokenId: String): Boolean {
        if (down) {
            failedReads += tokenId
            throw RevocationUnavailableException("The client host's revocations are down.")
        }
        return reader.isRevoked(tokenId)
    }

    override suspend fun subjectRevokedBefore(tenant: TenantId, principal: PrincipalId): Instant? {
        if (down) throw RevocationUnavailableException("The client host's revocations are down.")
        return reader.subjectRevokedBefore(tenant, principal)
    }
}
