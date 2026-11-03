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
import me.ahoo.cosec.engine.flat.CompiledDocument
import me.ahoo.cosec.engine.flat.SnapshotView
import me.ahoo.cosec.model.AppId
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.model.scope.Scope
import java.util.concurrent.CompletableFuture

/**
 * The documents one evaluation needs (RFC 0004 §7.4): always the platform document; unless the request is blocked,
 * the [tenant]'s; and the [app]'s when the scope has roles in an app, or when the tenant's document binds one of that
 * app's permission policies to `Anyone` or to a principal ([withAppFor], RC1-17). Null means not needed.
 */
class ViewKey private constructor(val tenant: TenantId?, val app: AppId?) {
    override fun equals(other: Any?): Boolean = other is ViewKey && other.tenant == tenant && other.app == app

    override fun hashCode(): Int = 31 * tenant.hashCode() + app.hashCode()

    override fun toString(): String = "ViewKey(tenant=${tenant?.value}, app=${app?.value})"

    /**
     * This key with [scope]'s app, when this key reads no app and [tenantDocument], the tenant's document it read,
     * needs that app's document whatever the roles ([CompiledDocument.needsApp]); null otherwise. Decided by one set
     * lookup on the compiled document: no I/O, and the client's app alone never loads a document.
     */
    internal fun withAppFor(scope: Scope, tenantDocument: CompiledDocument?): ViewKey? {
        // Most keys already read the app (a scope with roles): one field read, and done.
        if (app != null || tenantDocument == null) {
            return null
        }
        val tenant = tenant ?: return null
        val wanted = scope.app
        return if (wanted.value.isNotEmpty() && tenantDocument.needsApp(wanted.value)) ViewKey(tenant, wanted) else null
    }

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

/**
 * RFC 0006 §5.5.1: the installed view without suspending, else the loaded one. When the tenant's document needs the
 * app's although the scope has no roles ([ViewKey.withAppFor], RC1-17), the view with it, the same way.
 */
class SuspendingSnapshotSource(private val views: SnapshotViews) {
    /**
     * The installed view, found without suspending, else the loaded one. Kotlin spills every local a suspend function
     * has in scope at a suspension point into the continuation each call allocates; [installed] keeps them out of it,
     * so the second step costs a request no allocation (the JMH A/B of #1194).
     */
    suspend fun view(scope: Scope, blocked: Boolean): SnapshotView = installed(scope, blocked) ?: loaded(scope, blocked)

    /** The view when every document it needs is installed; null when one is not. No I/O, no suspension. */
    private fun installed(scope: Scope, blocked: Boolean): SnapshotView? {
        val key = ViewKey.of(scope, blocked)
        val view = views.peek(key) ?: return null
        val withApp = key.withAppFor(scope, view.tenant) ?: return view
        return views.peek(withApp)
    }

    private suspend fun loaded(scope: Scope, blocked: Boolean): SnapshotView {
        val key = ViewKey.of(scope, blocked)
        val view = views.peek(key) ?: views.load(key).await()
        val withApp = key.withAppFor(scope, view.tenant) ?: return view
        return views.peek(withApp) ?: views.load(withApp).await()
    }
}
