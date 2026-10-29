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

package me.ahoo.cosec.control.store

import me.ahoo.cosec.engine.flat.StatementSource
import me.ahoo.cosec.model.AppId
import me.ahoo.cosec.model.Binding
import me.ahoo.cosec.model.BindingId
import me.ahoo.cosec.model.BindingScope
import me.ahoo.cosec.model.Effect
import me.ahoo.cosec.model.Match
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.RoleId
import me.ahoo.cosec.model.SubjectRef
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.runtime.document.Actor
import me.ahoo.cosec.runtime.document.PolicyDefinition
import me.ahoo.cosec.runtime.document.PolicyDocument
import me.ahoo.cosec.runtime.document.ProvenanceKind
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** Candidates, publications and a settable clock for the revision tests. */
object StoreFixtures {
    val ACME: SnapshotKey = SnapshotKey.Tenant("acme")
    val ALICE = Actor(TenantId("acme"), PrincipalId("alice"))
    val START: Instant = Instant.parse("2026-11-01T00:00:00Z")

    fun policy(id: String, effect: Effect = Effect.ALLOW) =
        PolicyDefinition(
            PolicyId(id),
            attachable = false,
            condition = null,
            statements = listOf(StatementSource("", effect, listOf("*")))
        )

    fun binding(id: String, policy: String, app: String? = null) = Binding(
        BindingId(id),
        SubjectRef.Role(null, RoleId("editor")),
        BindingScope(app?.let { Match.Exactly(AppId(it)) } ?: Match.Any, Match.Any),
        PolicyId(policy),
    )

    /** A candidate of [key]; its revision, time and provenance are what [Revisions.publish] replaces. */
    fun candidate(
        key: SnapshotKey,
        policies: List<PolicyDefinition> = emptyList(),
        bindings: List<Binding> = emptyList()
    ): PolicyDocument {
        val (kind, owner) = Revisions.ownerOf(key)
        return PolicyDocument(kind, owner, 1, START, policies, bindings)
    }

    fun publication(change: String = "c1", kind: ProvenanceKind = ProvenanceKind.PUBLISH) = Publication(
        change,
        kind,
        ALICE
    )
}

/** A clock tests move forward. */
class MutableClock(var now: Instant = StoreFixtures.START) : Clock() {
    override fun getZone() = ZoneOffset.UTC

    override fun withZone(zone: java.time.ZoneId?): Clock = this

    override fun instant(): Instant = now
}
