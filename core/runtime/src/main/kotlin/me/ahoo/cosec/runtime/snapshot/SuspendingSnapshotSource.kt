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

package me.ahoo.cosec.runtime.snapshot

import kotlinx.coroutines.future.await
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.engine.SnapshotView
import me.ahoo.cosec.model.scope.Scope

/**
 * RFC 0006 §5.5.1: the installed view without suspending, else the loaded one. When the tenant's document needs the
 * app's although the scope has no roles ([ViewKey.withAppFor], RC1-17), the view with it, the same way.
 */
@InternalCoSecApi
class SuspendingSnapshotSource @InternalCoSecApi constructor(
    private val views: SnapshotViews,
    private val closeable: AutoCloseable? = null,
) : AutoCloseable {
    /**
     * The installed view, found without suspending, else the loaded one. Only the pipeline asks. Kotlin spills every local a suspend function
     * has in scope at a suspension point into the continuation each call allocates; [installed] keeps them out of it,
     * so the second step costs a request no allocation (the JMH A/B of #1194).
     */
    @InternalCoSecApi
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

    /** Closes what it was given to close; a no-op for a source built around views the host owns and closes. */
    override fun close() {
        closeable?.close()
    }
}
