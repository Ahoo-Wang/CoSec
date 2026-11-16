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

package me.ahoo.cosec.control.change

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.lifecycle.Operations
import me.ahoo.cosec.document.PolicyDocument
import me.ahoo.cosec.document.PolicyDocumentCodec
import me.ahoo.cosec.model.decision.DocumentKind
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.lang.reflect.Field
import java.lang.reflect.Modifier

/**
 * [Operations] rebuilds a candidate from its base's entries and sections rather than copying it, so a member added to
 * [PolicyDocument] is carried through it only by hand. Applied with no operation, a base with every member set gives
 * a candidate that differs from it only where the applier sets a member by design: [PolicyDocument.revision], the
 * base's plus one, and [PolicyDocument.provenance], which only a publish writes. `publishedAt` is the base's, `kind` and
 * `owner` are the key's.
 */
class OperationsCarryTest {
    private val rule = """{"id":"login","action":"http:POST","resource":"http:path:/login","key":"environment.ip",""" +
        """"permitsPerSecond":0.5,"windowSeconds":60,"strictFailure":true}"""
    private val provenance = """"provenance":{"parent":{"revision":2,"digest":"$DIGEST"},"change":"0QH8K3",""" +
        """"kind":"PUBLISH","publishedBy":{"tenant":"(platform)","principal":"alice"},""" +
        """"approvedBy":[{"tenant":"(platform)","principal":"ops-1"}],"shadow":{"window":"0QH8M1","skipped":false}}"""

    private val bases: Map<SnapshotKey, PolicyDocument> = mapOf(
        SnapshotKey.Platform to decode(
            DocumentKind.PLATFORM,
            "",
            """"boundaries":{"default":"b"},"limits":{"maxNodes":32,"maxCost":100},""" +
                """"attributes":[{"path":"ext.geo.region","type":"string"}],""" +
                """"policies":[{"id":"b","boundary":true,"statements":[]},""" +
                """{"id":"g","statements":[{"effect":"deny","action":"http:*"}]}],""" +
                """"bindings":[{"id":"g","subject":"*","app":"*","space":"*","policy":"g"}],""" +
                """"rateLimits":[$rule],$provenance""",
        ),
        SnapshotKey.App("order") to decode(
            DocumentKind.APP,
            "order",
            """"catalog":{"groups":[{"name":"order","permissions":[{"id":"read"}]}]},""" +
                """"roles":[{"id":"clerk","permissions":["read"]}],""" +
                """"policies":[{"id":"own","statements":[{"effect":"allow","action":"order:read"}]}],""" +
                """"bindings":[{"id":"o","subject":{"role":"clerk"},"app":"order","space":"*","policy":"own"}],""" +
                """"rateLimits":[$rule],$provenance""",
        ),
        SnapshotKey.Tenant("acme") to decode(
            DocumentKind.TENANT,
            "acme",
            """"boundary":"b",""" +
                """"roles":[{"id":"picker","app":"wms","space":"","permissions":["pick"]}],""" +
                """"policies":[{"id":"own","statements":[{"effect":"allow","action":"wms:pick"}]}],""" +
                """"bindings":[{"id":"o","subject":{"role":"picker"},"app":"wms","space":"*","policy":"own"}],""" +
                """"rateLimits":[$rule],$provenance""",
        ),
    )

    private fun decode(kind: DocumentKind, owner: String, body: String): PolicyDocument =
        PolicyDocumentCodec.decode(
            kind,
            owner,
            """{"schema":2,"revision":3,"publishedAt":"2026-10-26T00:00:00Z",$body}""".encodeToByteArray(),
        )

    private fun fields(): List<Field> =
        PolicyDocument::class.java.declaredFields.filterNot { Modifier.isStatic(it.modifiers) }
            .onEach { it.isAccessible = true }

    /** Set: not null, not an empty collection, not the platform's empty owner. */
    private fun set(value: Any?): Boolean = when (value) {
        null -> false
        is Collection<*> -> value.isNotEmpty()
        is String -> value.isNotEmpty()
        else -> true
    }

    @Test
    fun `the bases set every field a document of their kind may hold`() {
        val unset = fields().filter { field -> bases.values.none { set(field.get(it)) } }.map { it.name }
        unset.assert().isEmpty()
        bases.getValue(SnapshotKey.Tenant("acme")).let { tenant ->
            fields().filter { !set(it.get(tenant)) }.map { it.name }
                .assert().containsExactlyInAnyOrder("catalog", "limits", "attributes")
        }
    }

    @Test
    fun `no operation carries every field of the base but the revision and the provenance`() {
        bases.forEach { (key, base) ->
            val candidate = Operations.apply(key, base, emptyList()).candidate!!
            fields().filter { it.get(candidate) != it.get(base) }.map { it.name }
                .assert().containsExactlyInAnyOrder("revision", "provenance")
            candidate.revision.assert().isEqualTo(base.revision + 1)
            candidate.provenance.assert().isNull()
        }
    }

    private companion object {
        const val DIGEST = "SHA-256:9f2c0b1e6a6d1f2c3b4a5d6e7f8091a2b3c4d5e6f708192a3b4c5d6e7f809102"
    }
}
