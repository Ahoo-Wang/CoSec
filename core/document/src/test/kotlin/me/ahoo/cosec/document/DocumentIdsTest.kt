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

package me.ahoo.cosec.document

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * `DOC-13` (R1B-3): no id a document writes holds a control character or an unpaired surrogate. Each is refused at its
 * JSON path, and the message, which a host logs when it cannot install a stored document, forges no line itself.
 */
class DocumentIdsTest {
    private val compiler = PolicyDocumentCompiler()

    private fun refused(key: SnapshotKey, body: String): DocumentCompileException = assertThrows {
        compiler.compile(
            key,
            SnapshotDocuments.read(
                """{"schema":2,"revision":1,"publishedAt":"2026-11-19T00:00:00Z"$body}""".encodeToByteArray(),
            ),
        )
    }

    /** The paths of the `DOC-13` errors; every message of the document is printable. */
    private fun DocumentCompileException.unprintable(): List<String> {
        val message = message.orEmpty()
        message.none { it.isISOControl() }.assert().describedAs(message).isTrue()
        errors.filter { "DOC-13" in it.message }.forEach {
            it.message.none { c -> c.isISOControl() }.assert().describedAs(it.message).isTrue()
        }
        return errors.filter { "DOC-13" in it.message }.map { it.path }
    }

    @Test
    fun `every id a tenant document writes is checked`() {
        val body = """
            ,"policies":[{"id":"p\n","statements":[{"name":"s\u0007","effect":"deny","action":"order:cancel"}]},
                         {"id":"ok","statements":[]}],
             "bindings":[{"id":"b\n","subject":{"principal":"u\n","tenant":"acme"},"app":"*","space":"*","policy":"ok"},
                         {"id":"c","subject":{"role":"r\u001b"},"app":"order\u0085","space":"s\n","policy":"q\n"}],
             "boundary":"bnd\n",
             "roles":[{"id":"role\n","app":"order","space":"sp\n","permissions":["ship\n"]}]
        """.trimIndent()
        refused(SnapshotKey.Tenant("acme"), body).unprintable().assert().containsExactlyInAnyOrder(
            "$.policies[0].id",
            "$.policies[0].statements[0].name",
            "$.boundary",
            "$.bindings[0].id",
            "$.bindings[0].subject.principal",
            "$.bindings[1].subject.role",
            "$.bindings[1].app",
            "$.bindings[1].space",
            "$.bindings[1].policy",
            "$.roles[0].id",
            "$.roles[0].space",
            "$.roles[0].permissions[0]",
        )
    }

    @Test
    fun `a binding's tenant and a role's app are ids too`() {
        val body = """
            ,"policies":[{"id":"ok","statements":[]}],
             "bindings":[{"id":"b","subject":{"principal":"u","tenant":"t\n"},"app":"*","space":"*","policy":"ok"},
                         {"id":"c","subject":{"role":"r","tenant":"t\u0000"},"app":"*","space":"*","policy":"ok"}]
        """.trimIndent()
        refused(SnapshotKey.Platform, body).unprintable().assert()
            .containsExactly("$.bindings[0].subject.tenant", "$.bindings[1].subject.tenant")
        val role = ""","policies":[],"bindings":[],"roles":[{"id":"r","app":"a\n","permissions":["x"]}]"""
        refused(SnapshotKey.Tenant("acme"), role).unprintable().assert().containsExactly("$.roles[0].app")
    }

    @Test
    fun `an app document's catalog entries and roles, and the platform's boundary and attributes, are checked`() {
        val app = """
            ,"catalog":{"groups":[{"name":"order\ngroup","permissions":[{"id":"ship\n","description":"a\nb"}]}]},
             "policies":[],"bindings":[],"roles":[{"id":"sys\u0001","name":"n\n","permissions":"*"}]
        """.trimIndent()
        // A group's name and an entry's description are display text, not ids.
        refused(SnapshotKey.App("order"), app).unprintable().assert()
            .containsExactly("$.roles[0].id", "$.catalog.groups[0].permissions[0].id")
        val platform = """
            ,"policies":[{"id":"b","boundary":true,"statements":[{"effect":"allow","action":"*"}]}],"bindings":[],
             "boundaries":{"default":"b\n"},"attributes":[{"path":"ext.geo.c\n","type":"string"}]
        """.trimIndent()
        refused(SnapshotKey.Platform, platform).unprintable().assert()
            .containsExactly("$.boundaries.default", "$.attributes[0].path")
    }

    @Test
    fun `an unpaired surrogate is refused, a pair is a character like any other`() {
        val lone = ""","policies":[{"id":"p\uD800","statements":[]}],"bindings":[]"""
        refused(SnapshotKey.Platform, lone).unprintable().assert().containsExactly("$.policies[0].id")
        refused(SnapshotKey.Platform, lone).errors.single().message.assert().contains("[p\\uD800]")
        compiler.compile(
            SnapshotKey.Platform,
            SnapshotDocuments.read(
                (
                    """{"schema":2,"revision":1,"publishedAt":"2026-11-19T00:00:00Z","policies":[{"id":"p😀",""" +
                        """"statements":[{"name":"é","effect":"allow","action":"*"}]}],"bindings":[]}"""
                    ).encodeToByteArray(),
            ),
        )
    }

    @Test
    fun `the message of a document that does not compile forges no line, whatever it quotes`() {
        // Not an id: an action pattern quoted in another check's message.
        val error = refused(
            SnapshotKey.Platform,
            ""","policies":[{"id":"p\n","statements":[{"effect":"deny","action":"http:head"}]}],"bindings":[]"""
        )
        error.errors.map { it.path }.assert().contains("$.policies[0].id", "$.policies[0].statements[0].action[0]")
        error.message.orEmpty().assert().contains("[p\\u000A]").doesNotContain("\n")
    }
}
