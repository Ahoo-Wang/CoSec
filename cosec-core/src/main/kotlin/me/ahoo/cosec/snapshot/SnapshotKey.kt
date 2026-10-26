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

package me.ahoo.cosec.snapshot

import me.ahoo.cosec.api.context.request.AppId

/**
 * The key of a snapshot document.
 *
 * - [Platform], [App] and [Tenant] hold the 8.0 documents (RFC 0004 §7.1, schema 2): app and tenant documents may name
 *   platform policies, never the other way round, and each is read and versioned on its own.
 * - [Policies], with [App] in schema 1, is RFC 0003's 7.0-shaped pair (§3.2), removed at the switch (RFC 0008 §4.2).
 */
sealed interface SnapshotKey {
    val kind: SnapshotKind

    /** The platform document: platform policies and bindings. Every request needs it: prewarmed, never evicted. */
    data object Platform : SnapshotKey {
        override val kind: SnapshotKind get() = SnapshotKind.PLATFORM

        override fun toString(): String = "platform"
    }

    /** One tenant's own policies and bindings. Loaded on demand and evicted least recently used. */
    data class Tenant(val tenantId: String) : SnapshotKey {
        init {
            require(isValidTenantId(tenantId)) {
                "Tenant id [$tenantId] must be non-empty and must not contain '${App.APP_ID_SEPARATOR}'."
            }
        }

        override val kind: SnapshotKind get() = SnapshotKind.TENANT

        override fun toString(): String = "tenant:$tenantId"

        companion object {
            /** The same rule as an app id: `(platform)` and `(0)` are valid and encoded as they are. */
            fun isValidTenantId(tenantId: String): Boolean =
                tenantId.isNotEmpty() && !tenantId.contains(App.APP_ID_SEPARATOR)
        }
    }

    /** 7.0-shaped: every policy, global or not. Every request needs it, so it is prewarmed and never evicted. */
    data object Policies : SnapshotKey {
        override val kind: SnapshotKind get() = SnapshotKind.POLICIES

        override fun toString(): String = "policies"
    }

    /**
     * One app's document, loaded on demand and evicted least recently used: in schema 2 its catalog, permission
     * policies and role bindings; in RFC 0003's schema 1 its permissions and role grants.
     */
    data class App(val appId: AppId) : SnapshotKey {
        init {
            require(
                isValidAppId(appId)
            ) { "App id [$appId] must be non-empty and must not contain '$APP_ID_SEPARATOR'." }
        }

        override val kind: SnapshotKind get() = SnapshotKind.APP

        override fun toString(): String = "app:$appId"

        companion object {
            const val APP_ID_SEPARATOR = ":"

            /** Whether [appId] can name an app document; the same rule as the Redis key of role grants. */
            fun isValidAppId(appId: AppId): Boolean = appId.isNotEmpty() && !appId.contains(APP_ID_SEPARATOR)
        }
    }
}

/** The kind of a [SnapshotKey]: low-cardinality, so it can tag metrics where the app id cannot. */
enum class SnapshotKind {
    POLICIES,
    APP,
    PLATFORM,
    TENANT,
}
