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

import me.ahoo.cosec.model.Match
import me.ahoo.cosec.model.scope.ScopeResolver

/**
 * The key of a snapshot document.
 *
 * [Platform], [App] and [Tenant] hold the 8.0 documents (RFC 0004 §7.1, schema 2): app and tenant documents may name
 * platform policies, never the other way round, and each is read and versioned on its own.
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
                "Tenant id [$tenantId] must not be blank or [${Match.WILDCARD}], contain '${App.APP_ID_SEPARATOR}' or a " +
                    "control character, or exceed ${ScopeResolver.MAX_APP_ID_LENGTH} characters."
            }
        }

        override val kind: SnapshotKind get() = SnapshotKind.TENANT

        override fun toString(): String = "tenant:$tenantId"

        companion object {
            /**
             * A tenant id names exactly one Redis key: the rule of a declared app id (`ScopeResolver.isValidAppId`, #1024:
             * not blank, not `*`, no `:`, bounded length) and no control characters, so no id can reach another key,
             * a key pattern or a log line it does not own. `(platform)` and `(0)` are valid and encoded as they are.
             */
            fun isValidTenantId(tenantId: String): Boolean =
                ScopeResolver.isValidAppId(tenantId) && tenantId.none { it.isISOControl() }
        }
    }

    /**
     * One app's document, loaded on demand and evicted least recently used: its catalog, permission policies and role
     * bindings.
     */
    data class App(val appId: String) : SnapshotKey {
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
            fun isValidAppId(appId: String): Boolean = appId.isNotEmpty() && !appId.contains(APP_ID_SEPARATOR)
        }
    }
}

/** The kind of a [SnapshotKey]: low-cardinality, so it can tag metrics where the app id cannot. */
enum class SnapshotKind {
    APP,
    PLATFORM,
    TENANT,
}
