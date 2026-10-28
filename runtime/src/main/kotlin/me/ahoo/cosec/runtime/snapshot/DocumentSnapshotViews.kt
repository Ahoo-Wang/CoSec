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

import me.ahoo.cosec.engine.flat.CompiledDocument
import me.ahoo.cosec.engine.flat.SnapshotView
import me.ahoo.cosec.engine.flat.ViewVersion
import me.ahoo.cosec.model.AppId
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.model.decision.Cause
import me.ahoo.cosec.model.decision.DocumentKind
import me.ahoo.cosec.snapshot.DocumentState
import me.ahoo.cosec.snapshot.SnapshotEntry
import me.ahoo.cosec.snapshot.SnapshotKey
import me.ahoo.cosec.snapshot.SnapshotSet
import me.ahoo.cosec.snapshot.SnapshotSource
import me.ahoo.cosec.snapshot.SnapshotStaleException
import java.util.concurrent.CompletableFuture

/**
 * [SnapshotViews] over the 8.0 snapshot source (`DocumentSnapshots.source`, RFC 0004 §7.4): the platform document and
 * the tenant and app documents [ViewKey] names, composed into one view per evaluation. Each entry is immutable, so an
 * evaluation sees one revision of each document (`SNP-1`); installs follow the source's guarantees (`SNP-2`).
 *
 * - A document the store does not hold is an empty one of its owner: nothing is published for it yet.
 * - A document that failed (unreadable, undecodable, uncompilable, lost) is unresolved: the view leaves it out and the
 *   evaluation is indeterminate unless a DENY matches (RFC 0004 §7.4, `DOC-1`). A failed platform document is an empty
 *   one plus that cause, so the blacklist rule still decides a blocked request.
 * - A tenant id that cannot name a document (RFC 0004 §7.1) is unresolved in the same way: never another key.
 * - With [platformRequired], an absent platform document is unresolved too, not empty. Once the control plane's
 *   bootstrap has published a platform document, its absence means it was lost (for example, evicted from Redis), and
 *   reading it as empty would silently drop every global DENY (RFC 0008 §4.4, #1053).
 * - A tenant the source is configured to fail closed for (`SnapshotOptions.failClosedTenants`, RFC 0003 §4.5) is
 *   unresolved when the oldest document of its view was confirmed longer ago than `failClosedMaxLag`
 *   ([SnapshotSource.checkFresh]); other tenants are never checked.
 */
class DocumentSnapshotViews(
    private val source: SnapshotSource<CompiledDocument, CompiledDocument>,
    private val platformRequired: Boolean = false,
) : SnapshotViews {
    override fun peek(key: ViewKey): SnapshotView? {
        val keys = keysOf(key)
        return source.peekAll(keys.documents)?.let { view(key, keys, it) }
    }

    override fun load(key: ViewKey): CompletableFuture<SnapshotView> {
        val keys = keysOf(key)
        return source.loadAll(keys.documents).thenApply { view(key, keys, it) }
    }

    /** The documents [key] needs, and why a tenant it names cannot be read. */
    private class Keys(val documents: List<SnapshotKey>, val invalidTenant: Cause?)

    private fun keysOf(key: ViewKey): Keys {
        val documents = ArrayList<SnapshotKey>(2)
        var invalidTenant: Cause? = null
        key.tenant?.let { tenant ->
            if (SnapshotKey.Tenant.isValidTenantId(tenant.value)) {
                documents += SnapshotKey.Tenant(tenant.value)
            } else {
                val error = IllegalArgumentException("Tenant id [${tenant.value}] cannot name a snapshot document.")
                invalidTenant = Cause.SourceUnavailable(DocumentKind.TENANT, error)
            }
        }
        key.app?.let { documents += SnapshotKey.App(it.value) }
        return Keys(documents, invalidTenant)
    }

    private fun view(key: ViewKey, keys: Keys, set: SnapshotSet<CompiledDocument, CompiledDocument>): SnapshotView {
        val unresolved = ArrayList<Cause>(0)
        keys.invalidTenant?.let(unresolved::add)
        val platform = document(set.shared, DocumentKind.PLATFORM, unresolved) {
            if (platformRequired) {
                unresolved += Cause.SourceUnavailable(DocumentKind.PLATFORM, MissingPlatformException())
            }
            CompiledDocument.platform(emptyList(), emptyList())
        }
        // Every key the view needs was asked for, so the set holds its entry.
        val tenant = key.tenant?.takeIf { keys.invalidTenant == null }?.let { id ->
            document(set.entry(SnapshotKey.Tenant(id.value))!!, DocumentKind.TENANT, unresolved) { empty(id) }
        }
        val app = key.app?.let { id ->
            document(set.entry(SnapshotKey.App(id.value))!!, DocumentKind.APP, unresolved) { empty(id) }
        }
        key.tenant?.let { stale(set, it) }?.let(unresolved::add)
        val tenantKey = key.tenant?.takeIf { keys.invalidTenant == null }?.let { SnapshotKey.Tenant(it.value) }
        val version = ViewVersion(
            set.shared.revision,
            key.app?.let { set.entry(SnapshotKey.App(it.value))!!.revision },
            tenantKey?.let { set.entry(it)!!.revision },
        )
        return SnapshotView(
            platform ?: CompiledDocument.platform(emptyList(), emptyList()),
            app,
            tenant,
            unresolved,
            version
        )
    }

    /** The compiled document of [entry]; [absent] when none is stored; null, with the cause added, when it failed. */
    @Suppress("UNCHECKED_CAST")
    private fun document(
        entry: SnapshotEntry<*>,
        kind: DocumentKind,
        unresolved: MutableList<Cause>,
        absent: () -> CompiledDocument,
    ): CompiledDocument? =
        when (val state = entry.state) {
            is DocumentState.Present<*> -> state.compiled as CompiledDocument
            DocumentState.Absent -> absent()
            is DocumentState.Failed -> {
                unresolved += Cause.SourceUnavailable(kind, state.error)
                null
            }
        }

    /** Why [tenant] may not decide on [set]: it fails closed, and [set] lags more than it allows; null otherwise. */
    private fun stale(set: SnapshotSet<*, *>, tenant: TenantId): Cause? = try {
        source.checkFresh(set, tenant.value)
        null
    } catch (error: SnapshotStaleException) {
        Cause.SourceUnavailable(DocumentKind.TENANT, error)
    }

    private fun empty(tenant: TenantId) = CompiledDocument.tenant(tenant, emptyList(), emptyList())

    private fun empty(app: AppId) = CompiledDocument.app(app, emptyList(), emptyList())
}

/** No platform document is published where one is required ([DocumentSnapshotViews.platformRequired]). */
class MissingPlatformException : IllegalStateException("No platform document is published; requests fail closed.")
