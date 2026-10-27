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

package me.ahoo.cosec.snapshot.document

import me.ahoo.cosec.model.AppId
import me.ahoo.cosec.model.Match
import me.ahoo.cosec.model.SpaceId
import me.ahoo.cosec.model.SubjectRef
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.model.decision.DocumentKind
import me.ahoo.cosec.model.scope.Scope
import me.ahoo.cosec.serialization.CoSecJsonSerializer
import me.ahoo.cosec.snapshot.SnapshotFormatException
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/** Documents are written inline, one JSON line each. */
@Suppress("MaxLineLength")
class PolicyDocumentCodecTest {
    private val header = """"schema":2,"revision":3,"publishedAt":"2026-10-26T00:00:00Z""""

    private fun decode(body: String, kind: DocumentKind = DocumentKind.PLATFORM, owner: String = "") =
        PolicyDocumentCodec.decode(kind, owner, "{$header$body}".encodeToByteArray())

    private fun error(body: String, kind: DocumentKind = DocumentKind.PLATFORM, owner: String = ""): String =
        try {
            decode(body, kind, owner)
            throw AssertionError("decoded {$header$body}")
        } catch (error: SnapshotFormatException) {
            error.message!!
        }

    private val full = """
        {"schema":2,"revision":3,"publishedAt":"2026-10-26T00:00:00Z",
         "catalog":{"groups":[{"name":"order","description":"Orders","spaced":true,
                               "permissions":[{"id":"read","name":"Read","description":"Read orders"},{"id":"ship"}]}]},
         "policies":[{"id":"perm:order/read","condition":{"eq":["subject.authenticated",true]},
                      "statements":[{"name":"read","effect":"allow","action":["order:read","http:GET"],
                                     "resource":["order/{id}",{"pattern":"http:path:/Orders/{id}","caseSensitive":false,"decodeAndParseSegments":true}],
                                     "condition":{"and":[{"eq":["resource.var.id",{"attr":"subject.id"}]},
                                                         {"eq":["scope.space","Team"],"ignoreCase":true},
                                                         {"in":["scope.app",["order","billing"]]},
                                                         {"in":["admin","scope.roles"]},
                                                         {"prefix":["http.origin.host.labels","com.example"]},
                                                         {"not":{"cidr":["environment.ip",["10.0.0.0/8"]]}},
                                                         {"or":[{"exists":"http.header.x-trace"},
                                                                {"time":{"notBefore":"2026-01-01T00:00:00Z","notAfter":"2027-01-01T00:00:00Z","zone":"Asia/Shanghai","daysOfWeek":["MON"],"from":"09:00","to":"18:00"}}]}]}},
                                    {"effect":"deny","action":"*"}]}],
         "bindings":[{"id":"r","subject":{"role":"editor"},"app":"order","space":"*","policy":"perm:order/read"},
                     {"id":"t","subject":{"role":"viewer","tenant":"acme"},"app":"*","space":"team","policy":"shared","expiresAt":"2026-12-31T00:00:00Z"}]}
    """.trimIndent()

    @Test
    fun `a document reads and writes back the same`() {
        val document = PolicyDocumentCodec.decode(DocumentKind.APP, "order", full.encodeToByteArray())
        document.revision.assert().isEqualTo(3)
        document.catalog!!.groups.single().permissions.map { it.id }.assert().containsExactly("read", "ship")
        val statements = document.policies.single().statements
        statements.map { it.name }.assert().containsExactly("read", "")
        statements[1].actions.assert().containsExactly("*")
        statements[0].resources[1].caseSensitive.assert().isFalse()
        document.bindings[0].scope.space.assert().isEqualTo(Match.Any)
        document.bindings[0].subject.assert().isEqualTo(SubjectRef.Role(null, me.ahoo.cosec.model.RoleId("editor")))
        document.bindings[1].expiresAt.assert().isEqualTo(1_798_675_200_000L)
        val written = PolicyDocumentCodec.encode(document)
        val again = PolicyDocumentCodec.decode(DocumentKind.APP, "order", written)
        PolicyDocumentCodec.encode(again).assert().isEqualTo(written)
        CoSecJsonSerializer.readTree(written).get("bindings").get(0).get("space").asString().assert().isEqualTo("*")
        // The written document compiles as the read one does.
        PolicyDocumentCompiler().compile(again).owner.assert().isEqualTo("order")
    }

    /** RFC 0007 §5.3: the control plane's provenance reads and writes back the same, and decides nothing. */
    @Test
    fun `a provenance reads and writes back the same`() {
        val digest = "9f2c0b1e6a6d1f2c3b4a5d6e7f8091a2b3c4d5e6"
        val document = decode(
            ""","provenance":{"parent":{"revision":2,"digest":"$digest"},"change":"0QH8K3","kind":"ROLLBACK",""" +
                """"publishedBy":{"tenant":"acme","principal":"alice"},"approvedBy":[{"tenant":"(platform)","principal":"ops-1"}],""" +
                """"shadow":{"window":"0QH8M1","skipped":false}}"""
        )
        document.provenance.assert().isEqualTo(
            Provenance(
                parent = ParentRevision(2, digest),
                change = "0QH8K3",
                kind = ProvenanceKind.ROLLBACK,
                publishedBy = Actor(TenantId("acme"), me.ahoo.cosec.model.PrincipalId("alice")),
                approvedBy = listOf(Actor(TenantId("(platform)"), me.ahoo.cosec.model.PrincipalId("ops-1"))),
                shadow = ShadowWindow("0QH8M1", skipped = false),
            )
        )
        val written = PolicyDocumentCodec.encode(document)
        PolicyDocumentCodec.encode(
            PolicyDocumentCodec.decode(DocumentKind.PLATFORM, "", written)
        ).assert().isEqualTo(written)
        val first =
            decode(
                ""","provenance":{"change":"c","kind":"BOOTSTRAP","publishedBy":{"tenant":"(platform)","principal":"p"}}"""
            )
        first.provenance!!.parent.assert().isNull()
        first.provenance.approvedBy.assert().isEmpty()
        CoSecJsonSerializer.readTree(
            PolicyDocumentCodec.encode(first)
        ).get("provenance").has("approvedBy").assert().isFalse()
        // The compiled snapshot does not depend on it.
        PolicyDocumentCompiler().compile(document).owner.assert().isEqualTo("")
    }

    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = [
            """{"kind":"PUBLISH","publishedBy":{"tenant":"t","principal":"p"}}|$.provenance: [change] is required""",
            """{"change":"","kind":"PUBLISH","publishedBy":{"tenant":"t","principal":"p"}}|$.provenance.change: must not be empty""",
            """{"change":"c","kind":"publish","publishedBy":{"tenant":"t","principal":"p"}}|$.provenance.kind: a kind is one of""",
            """{"change":"c","kind":"PUBLISH"}|$.provenance: [publishedBy] is required""",
            """{"change":"c","kind":"PUBLISH","publishedBy":{"tenant":"t"}}|$.provenance.publishedBy: [principal] is required""",
            """{"change":"c","kind":"PUBLISH","publishedBy":{"tenant":"t","principal":""}}|$.provenance.publishedBy.principal: must not be empty""",
            """{"change":"c","kind":"PUBLISH","publishedBy":{"tenant":"t","principal":"p"},"by":1}|$.provenance: unknown field by""",
            """{"parent":{"revision":0,"digest":"9f2c0b1e6a6d1f2c3b4a5d6e7f8091a2b3c4d5e6"},"change":"c","kind":"PUBLISH","publishedBy":{"tenant":"t","principal":"p"}}|$.provenance.parent.revision: must be positive""",
            """{"parent":{"revision":1,"digest":"9F2C"},"change":"c","kind":"PUBLISH","publishedBy":{"tenant":"t","principal":"p"}}|$.provenance.parent.digest: is 40 lowercase hex digits""",
            """{"change":"c","kind":"PUBLISH","publishedBy":{"tenant":"t","principal":"p"},"shadow":{"window":"w"}}|$.provenance.shadow: [skipped] is required""",
        ],
    )
    fun `a provenance that is not exactly the format is an error at its path`(provenance: String, message: String) {
        error(""","provenance":$provenance""").assert().contains(message)
    }

    /** DOC-2: `*` is Match.Any and only that; nothing can hold Match.Exactly("*"). */
    @Test
    fun `the wildcard is only ever Any`() {
        val document = decode(
            ""","policies":[{"id":"p","statements":[]}],"bindings":[{"id":"b","subject":"*","app":"*","space":"*","policy":"p"}]"""
        )
        document.bindings.single().scope.app.assert().isEqualTo(Match.Any)
        document.bindings.single().scope.space.assert().isEqualTo(Match.Any)
        assertThrownBy<IllegalArgumentException> { Match.Exactly(AppId("*")) }
        assertThrownBy<IllegalArgumentException> { Match.Exactly(SpaceId("*")) }
        assertThrownBy<IllegalArgumentException> { Match.Exactly("*") }
    }

    /** SCP-5: no decoding path builds a resolved scope; only ScopeResolver does. */
    @Test
    fun `a scope is never decoded`() {
        assertThrownBy<Exception> {
            CoSecJsonSerializer.readValue(
                """{"tenant":"acme","app":"order","appSource":"HOST","space":"","roles":["admin"]}""",
                Scope::class.java,
            )
        }
    }

    @Test
    fun `a subject's tenant defaults to the tenant document's own`() {
        val tenant = decode(
            ""","policies":[{"id":"p","statements":[]}],"bindings":[{"id":"b","subject":{"principal":"alice"},"app":"*","space":"*","policy":"p"}]""",
            DocumentKind.TENANT,
            "acme",
        )
        tenant.bindings.single().subject.assert()
            .isEqualTo(SubjectRef.Principal(TenantId("acme"), me.ahoo.cosec.model.PrincipalId("alice")))
        error(""","bindings":[{"id":"b","subject":{"principal":"alice"},"app":"*","space":"*","policy":"p"}]""")
            .assert().contains("$.bindings[0].subject: a principal of the PLATFORM document names its tenant")
    }

    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = [
            ""","extra":1|$: unknown field extra""",
            ""","catalog":{"groups":[]}|$: unknown field catalog""",
            ""","policies":{}|$.policies: expected an array""",
            ""","policies":[{"id":"p"}]|$.policies[0]: [statements] is required""",
            ""","policies":[{"id":"p","statements":[],"attach":true}]|$.policies[0]: unknown field attach""",
            ""","policies":[{"id":"p","attachable":"yes","statements":[]}]|$.policies[0].attachable: expected a boolean""",
            ""","policies":[{"id":1,"statements":[]}]|$.policies[0].id: expected a string""",
            ""","policies":[{"id":"p","statements":[{"effect":"permit","action":"*"}]}]|$.policies[0].statements[0].effect: an effect is allow or deny""",
            ""","policies":[{"id":"p","statements":[{"effect":"deny","action":[]}]}]|$.policies[0].statements[0].action: expected at least one value""",
            ""","policies":[{"id":"p","statements":[{"effect":"deny","action":"*","resource":[]}]}]|$.policies[0].statements[0].resource: expected at least one resource""",
            ""","policies":[{"id":"p","statements":[{"effect":"deny","action":"*","resource":{"pattern":"order","case":true}}]}]|$.policies[0].statements[0].resource: unknown field case""",
            ""","policies":[{"id":"p","statements":[{"effect":"deny","action":"*","condition":{"eq":["a","b"],"in":["a",["b"]]}}]}]|$.policies[0].statements[0].condition: a condition has exactly one operator""",
            ""","policies":[{"id":"p","statements":[{"effect":"deny","action":"*","condition":{"in":["a",["b"]],"ignoreCase":true}}]}]|ignoreCase applies to eq and prefix only""",
            ""","policies":[{"id":"p","statements":[{"effect":"deny","action":"*","condition":{"eq":["a",1]}}]}]|$.policies[0].statements[0].condition.eq[1]: a literal is a JSON string or boolean""",
            ""","policies":[{"id":"p","statements":[{"effect":"deny","action":"*","condition":{"eq":["a"]}}]}]|$.policies[0].statements[0].condition.eq: expected [left, right]""",
            ""","policies":[{"id":"p","statements":[{"effect":"deny","action":"*","condition":{"and":[]}}]}]|and needs a condition""",
            ""","policies":[{"id":"p","statements":[{"effect":"deny","action":"*","condition":{"like":["a","b"]}}]}]|unknown operator [like]""",
            ""","policies":[{"id":"p","statements":[{"effect":"deny","action":"*","condition":{"time":{"after":"x"}}}]}]|unknown field after""",
            ""","policies":[{"id":"p","statements":[{"effect":"deny","action":"*","condition":{"eq":["a",{"attr":"b","x":1}]}}]}]|unknown field x""",
            ""","bindings":[{"id":"b","subject":"anyone","app":"*","space":"*","policy":"p"}]|$.bindings[0].subject: a subject is *""",
            ""","bindings":[{"id":"b","subject":{"principal":"a","role":"r","tenant":"t"},"app":"*","space":"*","policy":"p"}]|not both""",
            ""","bindings":[{"id":"b","subject":{"tenant":"t"},"app":"*","space":"*","policy":"p"}]|a subject is *, a principal or a role""",
            ""","bindings":[{"id":"b","subject":"*","space":"*","policy":"p"}]|$.bindings[0]: [app] is required""",
            ""","bindings":[{"id":"b","subject":"*","app":"","space":"*","policy":"p"}]|$.bindings[0].app: an app is an app id or *""",
            ""","bindings":[{"id":"b","subject":"*","app":"*","space":"*","policy":"p","expiresAt":"tomorrow"}]|$.bindings[0].expiresAt: expected an ISO-8601 instant""",
            ""","bindings":[{"id":"b","subject":"*","app":"*","space":"*","policy":"p","breakGlass":1}]|$.bindings[0].breakGlass: expected a boolean""",
        ],
    )
    fun `what is not exactly the format is an error at its path`(body: String, message: String) {
        error(body).assert().contains(message)
    }

    /**
     * The shapes 7.x's codecs read as something broader than written (#1038 review, F2): an unknown key dropped, only
     * the first key of a matcher object read, a scalar read as an empty list. None of them decodes in 8.0 (`DOC-3`).
     */
    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = [
            ""","policies":[{"id":"p","statements":[{"effect":"allow","action":"*","conditon":{"exists":"subject.id"}}]}]|$.policies[0].statements[0]: unknown field conditon""",
            ""","policies":[{"id":"p","conditon":{"exists":"subject.id"},"statements":[]}]|$.policies[0]: unknown field conditon""",
            ""","policies":[{"id":"p","statements":[{"effect":"allow","action":"*","condition":{"exists":"subject.id","not":{"exists":"subject.id"}}}]}]|$.policies[0].statements[0].condition: a condition has exactly one operator""",
            ""","policies":[{"id":"p","statements":[{"effect":"allow","action":{"all":{},"path":"/admin/**"}}]}]|$.policies[0].statements[0].action: expected a string, found an object""",
            ""","policies":[{"id":"p","statements":[{"effect":"allow","action":"*","resource":{"pattern":"order/**","path":"x"}}]}]|$.policies[0].statements[0].resource: unknown field path""",
            ""","policies":[{"id":"p","statements":[{"effect":"allow","action":"*","condition":{"and":"exists"}}]}]|$.policies[0].statements[0].condition.and: expected an array, found a string""",
            ""","policies":[{"id":"p","statements":[{"effect":"allow","action":"*","condition":{"or":{"exists":"subject.id"}}}]}]|$.policies[0].statements[0].condition.or: expected an array, found an object""",
            ""","policies":[{"id":"p","statements":[{"effect":"allow","action":"*","condition":{"not":[{"exists":"subject.id"}]}}]}]|$.policies[0].statements[0].condition.not: expected an object, found an array""",
        ],
    )
    fun `what the 7 codecs read as broader than written does not decode`(body: String, message: String) {
        error(body).assert().contains(message)
    }

    @Test
    fun `the header must be this format's`() {
        fun decodeRaw(text: String) = PolicyDocumentCodec.decode(DocumentKind.PLATFORM, "", text.encodeToByteArray())
        assertThrownBy<SnapshotFormatException> {
            decodeRaw(
                """{"schema":1,"revision":1,"publishedAt":"2026-10-26T00:00:00Z"}"""
            )
        }
            .hasMessageContaining("schema 1 is not this version's document format, 2")
        assertThrownBy<SnapshotFormatException> {
            decodeRaw(
                """{"schema":2,"revision":0,"publishedAt":"2026-10-26T00:00:00Z"}"""
            )
        }
            .hasMessageContaining("$.revision: must be positive")
        assertThrownBy<SnapshotFormatException> {
            decodeRaw(
                """{"schema":2,"revision":1.5,"publishedAt":"2026-10-26T00:00:00Z"}"""
            )
        }
        assertThrownBy<SnapshotFormatException> { decodeRaw("""{"schema":2,"revision":1}""") }
        assertThrownBy<SnapshotFormatException> {
            decodeRaw(
                """{"schema":2,"schema":2,"revision":1,"publishedAt":"2026-10-26T00:00:00Z"}"""
            )
        }
            .hasMessageContaining("not a JSON document")
        assertThrownBy<SnapshotFormatException> { decodeRaw("") }
        assertThrownBy<SnapshotFormatException> {
            decodeRaw(
                "[]"
            )
        }.hasMessageContaining("$: expected an object, found an array")
        assertThrownBy<IllegalArgumentException> {
            PolicyDocument(
                DocumentKind.TENANT,
                "acme",
                1,
                java.time.Instant.EPOCH,
                emptyList(),
                emptyList(),
                Catalog(emptyList())
            )
        }
    }
}
