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

@file:OptIn(InternalCoSecApi::class, ExperimentalCoSecApi::class)

@file:OptIn(InternalCoSecApi::class)

package me.ahoo.cosec.control.change

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.store.AppList
import me.ahoo.cosec.control.store.ControlStore
import me.ahoo.cosec.control.store.Head
import me.ahoo.cosec.control.store.Revisions
import me.ahoo.cosec.control.store.Versioned
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.seconds

/** What the checks of a change read of the other documents, from the store of record. */
internal class ChangeContext(private val store: ControlStore, private val revisions: Revisions) : ChangeChecks.Context {
    private val reconciled = AtomicBoolean(false)
    private val attempts = Throttle(RECONCILE_INTERVAL)

    override suspend fun head(key: SnapshotKey): Head? = revisions.head(key)

    override suspend fun referrers(target: SnapshotKey, policy: PolicyId): Set<SnapshotKey> =
        revisions.referrers(target, policy)

    /**
     * The app list once this instance has reconciled it with the documents (RC1-4, B1 review): until a
     * reconciliation has succeeded here, a read runs one, so a complete list a downgraded writer left stale is never
     * trusted. One that fails throws, and the analysis takes the list as untrusted; so does a read while the last
     * attempt is recent (review item 19): until one succeeds, not every analysis reads every document's key.
     */
    override suspend fun apps(): Versioned<AppList>? {
        if (!reconciled.get()) {
            check(attempts.ready()) { "the app list is not reconciled on this instance yet" }
            revisions.reconcileApps(store.documents())
            reconciled.set(true)
        }
        return store.apps()
    }

    /** This instance has reconciled the app list ([me.ahoo.cosec.control.store.ControlMaintenance.reconcileApps]). */
    fun reconciled() {
        reconciled.set(true)
    }

    private companion object {
        /** How often an analysis may try to reconcile the app list until a reconciliation succeeds here. */
        val RECONCILE_INTERVAL = 30.seconds
    }
}
