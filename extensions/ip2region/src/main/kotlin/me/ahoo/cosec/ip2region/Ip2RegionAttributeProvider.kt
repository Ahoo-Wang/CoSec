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

import me.ahoo.cosec.model.Identifiers
import me.ahoo.cosec.model.condition.AttributeType
import me.ahoo.cosec.model.condition.IpAddress
import me.ahoo.cosec.spi.attribute.AttributeDeclaration
import me.ahoo.cosec.spi.attribute.AttributeProvider
import me.ahoo.cosec.spi.attribute.AttributeRequest
import org.lionsoul.ip2region.xdb.Searcher
import org.lionsoul.ip2region.xdb.Version
import java.io.ByteArrayInputStream

/**
 * The region of the client address as external attributes (RFC 0006 §5.4.5): `ext.ip2region.country`, `.province`,
 * `.city` and `.isp`, from in-memory ip2region databases, so a fetch never suspends and does no I/O.
 *
 * [databases] are `.xdb` files, each for the IP version its header names, at most one per version: the bundled one is
 * IPv4's; an IPv6 one is the application's to give (`cosec.ip2region.ipv6-location`).
 *
 * What the database cannot tell is unavailable, never absent (round 2 M5): an address of a version no database is
 * loaded for, or no address at all, throws, so a statement that reads the attributes is decided as for any missing
 * external attribute (a DENY on them is Indeterminate unless `onMissing: nomatch`), and a geo block cannot be stepped
 * around over IPv6. A field the database does not know for an address it covers (`0`, as for a private address) is
 * absent: there is nothing more to know.
 */
class Ip2RegionAttributeProvider(databases: List<ByteArray> = listOf(loadDefaultIp2RegionDb())) : AttributeProvider {
    private val searchers: Map<Version, Searcher> = databases.map { bytes ->
        val content = Searcher.loadContentFromInputStream(ByteArrayInputStream(bytes))
        val version = Version.fromHeader(Searcher.loadHeaderFromBuffer(content))
        version to Searcher.newWithBuffer(version, content)
    }.also { loaded ->
        val versions = loaded.map { it.first.name }
        require(versions.size == versions.toSet().size) { "One ip2region database per IP version, not $versions." }
    }.toMap()

    override val attributes: List<AttributeDeclaration> =
        FIELDS.keys.map { AttributeDeclaration(it, AttributeType.STRING) }

    /** The IP versions a database is loaded for. */
    val versions: Set<String> get() = searchers.keys.mapTo(LinkedHashSet()) { it.name }

    override suspend fun fetch(request: AttributeRequest, names: Set<String>): Map<String, Any> {
        val address = IpAddress.parse(request.remoteIp)
            ?: throw UnlocatableAddressException("[${Identifiers.printable(request.remoteIp)}] is no address")
        val version = if (address.v4) Version.IPv4 else Version.IPv6
        val searcher = searchers[version]
            ?: throw UnlocatableAddressException("no ip2region database is loaded for ${version.name}")
        // country|region|province|city|isp, 0 where unknown.
        val region = searcher.search(address.toString()).split('|')
        val values = HashMap<String, Any>(names.size)
        for (name in names) {
            val value = FIELDS[name]?.let(region::getOrNull)
            if (value != null && value != UNKNOWN && value.isNotEmpty()) {
                values[name] = value
            }
        }
        return values
    }

    override fun toString(): String = "Ip2RegionAttributeProvider(${versions.joinToString()})"

    companion object {
        const val COUNTRY = "ext.ip2region.country"
        const val PROVINCE = "ext.ip2region.province"
        const val CITY = "ext.ip2region.city"
        const val ISP = "ext.ip2region.isp"
        private const val UNKNOWN = "0"
        const val IP2REGION_DB_RESOURCE = "ip2region.xdb"

        fun loadDefaultIp2RegionDb(): ByteArray =
            Ip2RegionAttributeProvider::class.java.classLoader.getResourceAsStream(IP2REGION_DB_RESOURCE)
                ?.use { it.readBytes() }
                ?: throw IllegalStateException("Classpath resource [$IP2REGION_DB_RESOURCE] not found.")

        /** Each attribute and its field in the database's `country|region|province|city|isp`. */
        private val FIELDS = linkedMapOf(COUNTRY to 0, PROVINCE to 2, CITY to 3, ISP to 4)
    }
}

/** The client address cannot be located: its attributes are unavailable, never absent. */
internal class UnlocatableAddressException(reason: String) : IllegalStateException("Cannot locate the client: $reason.")
