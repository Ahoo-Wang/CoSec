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

package me.ahoo.cosec.runtime.snapshot

import kotlinx.coroutines.future.await
import me.ahoo.cosec.engine.flat.SnapshotView
import me.ahoo.cosec.model.AppId
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.model.scope.Scope
import java.util.concurrent.CompletableFuture

/**
 * The documents one evaluation needs (RFC 0004 §7.4): always the platform document; unless the request is blocked,
 * the [tenant]'s; and the [app]'s when the scope has roles in an app. Null means not needed.
 */
class ViewKey private constructor(val tenant: TenantId?, val app: AppId?) {
    override fun equals(other: Any?): Boolean = other is ViewKey && other.tenant == tenant && other.app == app

    override fun hashCode(): Int = 31 * tenant.hashCode() + app.hashCode()

    override fun toString(): String = "ViewKey(tenant=${tenant?.value}, app=${app?.value})"

    companion object {
        /** A blocked request reads only the platform document, where the blacklist rule is (RFC 0004 §4.7). */
        val BLOCKED = ViewKey(null, null)

        fun of(scope: Scope, blocked: Boolean): ViewKey {
            if (blocked) {
                return BLOCKED
            }
            val app = scope.app.takeIf { scope.roles.isNotEmpty() && it.value.isNotEmpty() }
            return ViewKey(scope.tenant, app)
        }
    }
}

/**
 * Composes snapshot views from installed documents: the RFC 0003 `SnapshotSource` contract over the platform, app and
 * tenant documents of RFC 0004, which implement it. The SPI stays future-based (RFC 0003 Q10);
 * [SuspendingSnapshotSource] is the pipeline's view of it.
 */
interface SnapshotViews {
    /** The view when every document [key] needs is installed, else null. No I/O, no future, on the caller's thread. */
    fun peek(key: ViewKey): SnapshotView?

    /**
     * Loads what [key] needs. Never blocks; each caller gets its own future, so cancelling it leaves a shared load
     * running. A document that cannot be read or does not load in time completes the future normally, as
     * [SnapshotView.unresolved]; the future fails only when the source is closed.
     */
    fun load(key: ViewKey): CompletableFuture<SnapshotView>
}

/** RFC 0006 §5.5.1: the installed view without suspending, else the loaded one. */
class SuspendingSnapshotSource(private val views: SnapshotViews) {
    suspend fun view(scope: Scope, blocked: Boolean): SnapshotView {
        val key = ViewKey.of(scope, blocked)
        return views.peek(key) ?: views.load(key).await()
    }
}
