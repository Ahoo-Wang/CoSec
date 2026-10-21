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
 * The key of a snapshot document (RFC 0003 §3.2). Phase 4 replaces both kinds with platform and tenant documents.
 *
 * The two kinds never reference each other, so each is read and versioned on its own.
 */
sealed interface SnapshotKey {
    val kind: SnapshotKind

    /** Every policy, global or not. Every request needs it, so it is prewarmed and never evicted. */
    data object Policies : SnapshotKey {
        override val kind: SnapshotKind get() = SnapshotKind.POLICIES

        override fun toString(): String = "policies"
    }

    /** One app's permissions and its role grants in every space. Loaded on demand and evicted least recently used. */
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
}
