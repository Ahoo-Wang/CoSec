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

package me.ahoo.cosec.ip2region

import me.ahoo.cosec.model.condition.AttributeType
import me.ahoo.cosec.model.condition.IpAddress
import me.ahoo.cosec.runtime.attribute.AttributeDeclaration
import me.ahoo.cosec.runtime.attribute.AttributeProvider
import me.ahoo.cosec.runtime.attribute.AttributeRequest
import org.lionsoul.ip2region.xdb.Searcher
import org.lionsoul.ip2region.xdb.Version
import java.io.ByteArrayInputStream

/**
 * The region of the client address as external attributes (RFC 0006 §5.4.5): `ext.ip2region.country`, `.province`,
 * `.city` and `.isp`, from the in-memory ip2region database, so a fetch never suspends and does no I/O.
 *
 * An address the database does not know a field for (`0`), or one that is not an address of its [version], has the
 * field absent. A failed search throws, which makes the attributes unavailable, never absent.
 */
class Ip2RegionAttributeProvider(
    ip2regionDb: ByteArray = Ip2RegionRequestAttributesAppender.loadDefaultIp2RegionDb(),
    private val version: Version = Version.IPv4,
) : AttributeProvider {
    private val searcher: Searcher =
        Searcher.newWithBuffer(version, Searcher.loadContentFromInputStream(ByteArrayInputStream(ip2regionDb)))

    override val attributes: List<AttributeDeclaration> =
        FIELDS.keys.map { AttributeDeclaration(it, AttributeType.STRING) }

    override suspend fun fetch(request: AttributeRequest, names: Set<String>): Map<String, Any> {
        val address = IpAddress.parse(request.remoteIp)
        if (address == null || address.v4 != (version == Version.IPv4)) {
            return emptyMap()
        }
        // country|region|province|city|isp, 0 where unknown.
        val region = searcher.search(request.remoteIp).split('|')
        val values = HashMap<String, Any>(names.size)
        for (name in names) {
            val value = FIELDS[name]?.let(region::getOrNull)
            if (value != null && value != UNKNOWN && value.isNotEmpty()) {
                values[name] = value
            }
        }
        return values
    }

    override fun toString(): String = "Ip2RegionAttributeProvider($version)"

    companion object {
        const val COUNTRY = "ext.ip2region.country"
        const val PROVINCE = "ext.ip2region.province"
        const val CITY = "ext.ip2region.city"
        const val ISP = "ext.ip2region.isp"
        private const val UNKNOWN = "0"

        /** Each attribute and its field in the database's `country|region|province|city|isp`. */
        private val FIELDS = linkedMapOf(COUNTRY to 0, PROVINCE to 2, CITY to 3, ISP to 4)
    }
}
