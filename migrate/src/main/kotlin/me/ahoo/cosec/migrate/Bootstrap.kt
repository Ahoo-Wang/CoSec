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

package me.ahoo.cosec.migrate

import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.control.change.CandidateDigest
import me.ahoo.cosec.control.store.ControlStore
import me.ahoo.cosec.control.store.Publication
import me.ahoo.cosec.control.store.PublishOutcome
import me.ahoo.cosec.control.store.Revisions
import me.ahoo.cosec.engine.flat.BuiltinPolicies
import me.ahoo.cosec.model.Binding
import me.ahoo.cosec.model.BindingId
import me.ahoo.cosec.model.BindingScope
import me.ahoo.cosec.model.Match
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.SubjectRef
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.model.decision.DocumentKind
import me.ahoo.cosec.snapshot.SnapshotKey
import me.ahoo.cosec.snapshot.document.PolicyDocument
import me.ahoo.cosec.snapshot.document.PolicyDocumentCompiler
import me.ahoo.cosec.snapshot.document.ProvenanceKind
import java.time.Clock

/** `bootstrap` refused: a platform administrator exists, and recovery was not asked for. */
class BootstrapRefusedException(message: String) : IllegalStateException(message)

/**
 * `bootstrap` (RFC 0007 §5.9, Q26): the control plane authorizes itself with the policies in the snapshot, so with an
 * empty store nobody may write. This binds [principal] of the platform tenant to `cosec:platform-admin` in the platform
 * tenant's own document, `Tenant((platform))`, in every scope, as a revision of kind `BOOTSTRAP` with its record. It runs
 * only while that document binds nobody to `cosec:platform-admin`; `force` is for the recovery where every one of them
 * is lost. Whoever holds the Redis credentials can write anyway: this only leaves a record of it.
 */
class Bootstrap(
    private val store: ControlStore,
    private val clock: Clock = Clock.systemUTC(),
    private val compiler: PolicyDocumentCompiler = PolicyDocumentCompiler(),
) {
    private val revisions = Revisions(store, clock)

    /** The revision written. */
    fun run(principal: PrincipalId, force: Boolean = false): Long = runBlocking {
        val head = revisions.head(KEY)
        val admins = head?.document?.bindings.orEmpty().filter { it.policy == BuiltinPolicies.PLATFORM_ADMIN }
        if (admins.isNotEmpty() && !force) {
            throw BootstrapRefusedException(
                "$KEY already binds cosec:platform-admin (${admins.map { it.id.value }}): bootstrap runs once; " +
                    "--force recovers when every administrator is lost.",
            )
        }
        val binding = Binding(
            BindingId("platform-admin-${principal.value}"),
            SubjectRef.Principal(TenantId.PLATFORM, principal),
            BindingScope(Match.Any, Match.Any),
            BuiltinPolicies.PLATFORM_ADMIN,
        )
        val current = head?.document
        val candidate = PolicyDocument(
            DocumentKind.TENANT,
            TenantId.PLATFORM.value,
            (current?.revision ?: 0) + 1,
            clock.instant(),
            current?.policies.orEmpty(),
            current?.bindings.orEmpty().filterNot { it.id == binding.id } + binding,
        )
        compiler.compile(candidate)
        if (current != null && CandidateDigest.of(current) == CandidateDigest.of(candidate)) {
            return@runBlocking current.revision
        }
        when (
            val outcome = revisions.publish(
                KEY,
                head,
                candidate,
                Publication("bootstrap", ProvenanceKind.BOOTSTRAP, DocumentPublisher.MIGRATE_ACTOR)
            )
        ) {
            is PublishOutcome.Published -> outcome.revision
            else -> throw BootstrapRefusedException("$KEY changed while bootstrap ran ($outcome): run it again.")
        }
    }

    companion object {
        /** The platform tenant's own document, where its principals' bindings are. */
        val KEY: SnapshotKey = SnapshotKey.Tenant(TenantId.PLATFORM.value)
    }
}
