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

package me.ahoo.cosec.runtime.attribute

import me.ahoo.cosec.annotations.CoSecSpi
import me.ahoo.cosec.model.condition.AttributeType
import me.ahoo.cosec.spi.attribute.AttributeDeclaration
import me.ahoo.cosec.spi.attribute.AttributeProvider
import me.ahoo.cosec.spi.attribute.AttributeRequest
import java.util.concurrent.ConcurrentHashMap

/**
 * Where the blacklist comes from (RFC 0006 §5.4.4): whether [value] of [kind] is blocked. A failed lookup throws; it
 * is never "not blocked", so the request is a 503 unless a DENY matches. Like every SPI of the pipeline, it must not
 * block the calling thread.
 */
@CoSecSpi
fun interface BlacklistSource {
    suspend fun contains(kind: BlacklistKind, value: String): Boolean
}

enum class BlacklistKind(val attribute: String) {
    /** An authenticated principal, by `<tenant>:<principal id>`: principal ids are unique only within a tenant. */
    PRINCIPAL(BlacklistAttributeProvider.PRINCIPAL),

    /** The client address, as the adapter reports it. */
    IP(BlacklistAttributeProvider.IP),

    /** The `CoSec-Device-Id` of the request. */
    DEVICE(BlacklistAttributeProvider.DEVICE),
    ;

    companion object {
        /** The value [kind] blocks for a principal of [tenant]. */
        fun principal(tenant: String, principal: String): String = "$tenant:$principal"
    }
}

/** A [BlacklistSource] in memory, for tests and small deployments. */
class InMemoryBlacklistSource : BlacklistSource {
    // One set per kind: a lookup allocates nothing, where a (kind, value) key would on every request.
    private val entries = BlacklistKind.entries.map { ConcurrentHashMap.newKeySet<String>() }

    override suspend fun contains(kind: BlacklistKind, value: String): Boolean = value in entries[kind.ordinal]

    fun block(kind: BlacklistKind, value: String) {
        entries[kind.ordinal] += value
    }

    fun unblock(kind: BlacklistKind, value: String) {
        entries[kind.ordinal] -= value
    }
}

/**
 * The blacklist as stage-A attributes (RFC 0006 §5.4.4): `ext.blacklist.principal`, `.ip` and `.device`, true when
 * [source] blocks the request's principal, address or device. Absent for an anonymous request or one without a device.
 *
 * The pipeline reads them before the snapshot view: a request one of them blocks loads only the platform document and
 * meets the built-in DENY `cosec:blacklist#0`. When [source] fails, every one is unavailable and the request is a 503
 * unless a DENY matches; never "not blocked".
 */
class BlacklistAttributeProvider(private val source: BlacklistSource) : AttributeProvider {
    override val attributes: List<AttributeDeclaration> = BlacklistKind.entries.map {
        AttributeDeclaration(it.attribute, AttributeType.BOOL, AttributeDeclaration.Stage.A)
    }

    override suspend fun fetch(request: AttributeRequest, names: Set<String>): Map<String, Any> {
        val values = HashMap<String, Any>(names.size)
        for (kind in BlacklistKind.entries) {
            if (kind.attribute !in names) {
                continue
            }
            val value = when (kind) {
                BlacklistKind.PRINCIPAL -> request.subject.takeIf { it.authenticated }
                    ?.let { BlacklistKind.principal(it.tenantId, it.id) }
                BlacklistKind.IP -> request.remoteIp.takeIf { it.isNotEmpty() }
                BlacklistKind.DEVICE -> request.deviceId.takeIf { it.isNotEmpty() }
            } ?: continue
            values[kind.attribute] = source.contains(kind, value)
        }
        return values
    }

    override fun toString(): String = "BlacklistAttributeProvider($source)"

    companion object {
        const val PRINCIPAL = "ext.blacklist.principal"
        const val IP = "ext.blacklist.ip"
        const val DEVICE = "ext.blacklist.device"
        val ATTRIBUTES: List<String> = listOf(PRINCIPAL, IP, DEVICE)
    }
}
