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

package me.ahoo.cosec.cocache.snapshot

import me.ahoo.cocache.core.KeyEncoder
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.spi.snapshot.SnapshotKey

/**
 * Explicit key encoding (RFC 0003 §10, RFC 0004 §7.1): `platform`, `app:<appId>` and `tenant:<tenantId>`.
 * `KeyEncoder.ofToString()` would need a reflection hint, and would tie the Redis layout to [SnapshotKey.toString].
 */
@InternalCoSecApi
object SnapshotKeyEncoder : KeyEncoder<SnapshotKey> {
    const val PLATFORM = "platform"
    const val APP_PREFIX = "app:"
    const val TENANT_PREFIX = "tenant:"

    override fun encode(key: SnapshotKey): String = when (key) {
        SnapshotKey.Platform -> PLATFORM
        is SnapshotKey.App -> APP_PREFIX + key.appId
        is SnapshotKey.Tenant -> TENANT_PREFIX + key.tenantId
    }

    /**
     * Whether [encoded] is an app or tenant key whose id is no longer valid: written by an earlier version, such as an id
     * with `/` (#1403). It names no document any instance reads.
     */
    fun retired(encoded: String): Boolean = when {
        encoded.startsWith(APP_PREFIX) -> !SnapshotKey.App.isValidAppId(encoded.substring(APP_PREFIX.length))
        encoded.startsWith(
            TENANT_PREFIX
        ) -> !SnapshotKey.Tenant.isValidTenantId(encoded.substring(TENANT_PREFIX.length))
        else -> false
    }

    /** The key [encoded] names, or null when it names none, such as an invalid app or tenant id. */
    fun decode(encoded: String): SnapshotKey? = when {
        encoded == PLATFORM -> SnapshotKey.Platform
        encoded.startsWith(APP_PREFIX) -> encoded.substring(APP_PREFIX.length)
            .takeIf { SnapshotKey.App.isValidAppId(it) }
            ?.let { SnapshotKey.App(it) }

        encoded.startsWith(TENANT_PREFIX) -> encoded.substring(TENANT_PREFIX.length)
            .takeIf { SnapshotKey.Tenant.isValidTenantId(it) }
            ?.let { SnapshotKey.Tenant(it) }

        else -> null
    }
}
