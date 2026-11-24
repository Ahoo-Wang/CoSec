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

import me.ahoo.cosec.document.PolicyDocument
import me.ahoo.cosec.document.PolicyDocumentCodec
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.model.decision.DocumentKind
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

/** `MIG-6`: what names a principal by its id, read by direction over the documents that decide its tenant's requests. */
class PrincipalDeniesTest {
    private val acme = TenantId("acme")

    private fun document(kind: DocumentKind, owner: String, policies: List<String>, bindings: List<String> = emptyList()) =
        PolicyDocumentCodec.decode(
            kind,
            owner,
            (
                """{"schema":2,"revision":1,"publishedAt":"2026-10-01T00:00:00Z",""" +
                    """"policies":[${policies.joinToString(",")}],"bindings":[${bindings.joinToString(",")}]}"""
                ).encodeToByteArray(),
        )

    private fun platform(vararg policies: String, bindings: List<String> = emptyList()) =
        document(DocumentKind.PLATFORM, "", policies.toList(), bindings)

    private fun statement(effect: String, condition: String? = null, name: String? = null) =
        """{${name?.let { "\"name\":\"$it\"," }.orEmpty()}"effect":"$effect","action":"order:delete"""" +
            (condition?.let { ""","condition":$it""" } ?: "") + "}"

    private fun policy(id: String, vararg statements: String, condition: String? = null) =
        """{"id":"$id","statements":[${statements.joinToString(",")}]""" +
            (condition?.let { ""","condition":$it""" } ?: "") + "}"

    private fun named(documents: List<PolicyDocument>, principal: String = "bob", attachments: List<String> = emptyList()) =
        PrincipalDenies.of(documents, acme, principal, attachments)

    @Test
    fun `a condition names the principal by direction, through not, and, or and the policy's own condition`() {
        val shapes = mapOf(
            """{"eq":["subject.id","BOB"],"ignoreCase":true}""" to "deny",
            """{"and":[{"exists":"subject.id"},{"or":[{"eq":["subject.id","bob"]},{"cidr":["environment.ip",["10.0.0.0/8"]]}]}]}""" to "deny",
            """{"not":{"not":{"in":["subject.id",["bob"]]}}}""" to "deny",
            """{"not":{"prefix":["subject.id","b"]}}""" to "allow",
            """{"eq":["subject.id",{"attr":"http.header.X-User"}]}""" to "deny",
            """{"eq":["http.header.X-User",{"attr":"subject.id"}]}""" to "deny",
        )
        for ((condition, effect) in shapes) {
            named(
                listOf(platform(policy("p", statement(effect, condition, name = "s"))))
            ).assert().describedAs(condition)
                .hasSize(1)
        }
        // The policy's own condition is read with each statement, in that statement's direction.
        named(listOf(platform(policy("p", statement("deny"), condition = """{"eq":["subject.id","bob"]}"""))))
            .single().assert().contains("statement 0 (DENY)")
    }

    @Test
    fun `a condition that names someone else, or names in the ALLOW direction, or cannot tell principals apart, names no one`() {
        val shapes = listOf(
            statement("deny", """{"eq":["subject.id","mallory"]}"""),
            statement("deny", """{"in":["subject.id",["mallory"]]}"""),
            statement("deny", """{"prefix":["subject.id","m"]}"""),
            statement("deny", """{"prefix":["subject.id","B"],"ignoreCase":false}"""),
            statement("allow", """{"eq":["subject.id","bob"]}"""),
            statement("allow", """{"in":["subject.id",["bob"]]}"""),
            statement("allow", """{"prefix":["subject.id","b"]}"""),
            statement("deny", """{"not":{"eq":["subject.id","bob"]}}"""),
            statement("deny", """{"eq":["http.header.X-User","bob"]}"""),
            statement("deny", """{"in":["http.header.X-User",["bob"]]}"""),
            statement("deny", """{"prefix":["http.header.X-User","b"]}"""),
            statement("deny", """{"in":["admin","subject.roles"]}"""),
            statement("deny", """{"time":{"daysOfWeek":["SUN"]}}"""),
        )
        for (shape in shapes) {
            named(listOf(platform(policy("p", shape)))).assert().describedAs(shape).isEmpty()
        }
    }

    @Test
    fun `bindings and attachments name the principal when their policy holds a DENY, and documents of other tenants never do`() {
        val principal = """"subject":{"principal":"bob","tenant":"acme"},"app":"*","space":"*""""
        val shared = platform(
            policy("deny-all", statement("deny")),
            policy("allow-all", statement("allow")),
            bindings = listOf(
                """{"id":"b-deny",$principal,"policy":"deny-all"}""",
                """{"id":"b-allow",$principal,"policy":"allow-all"}""",
            ),
        )
        val beta =
            document(
                DocumentKind.TENANT,
                "beta",
                listOf(policy("x", statement("deny", """{"eq":["subject.id","bob"]}""")))
            )
        val documents = listOf(shared, beta)
        named(documents).assert().containsExactly("the binding [platform/b-deny] of a DENY")
        PrincipalDenies.bindings(documents, acme, "bob").assert().containsExactly("platform/b-deny")
        PrincipalDenies.grants(documents, acme, "bob").assert().containsExactly("platform/b-allow")
        PrincipalDenies.bindings(documents, acme, "alice").assert().isEmpty()
        PrincipalDenies.bindings(documents, TenantId("beta"), "bob").assert().isEmpty()
        // Through the published documents, as the plan asks: no attachments unless the export lists some.
        val published: PublishedDocuments = PublishedDocumentsFixture(shared, emptyMap())
        published.deniesNaming(acme, "bob").assert().containsExactly("the binding [platform/b-deny] of a DENY")
        // An attachment names it when its policy holds a DENY, or when no document defines it.
        named(documents, attachments = listOf("deny-all", "allow-all", "gone")).assert().containsExactly(
            "the binding [platform/b-deny] of a DENY",
            "the attachment [deny-all] the membership provider gives it, a DENY",
            "the attachment [gone] the membership provider gives it, a DENY",
        )
    }

    @Test
    fun `a boundary only narrows and is not read`() {
        val boundary = """{"id":"b","boundary":true,"statements":[${statement(
            "allow",
            """{"not":{"eq":["subject.id","bob"]}}"""
        )}]}"""
        named(listOf(document(DocumentKind.PLATFORM, "", listOf(boundary)))).assert().isEmpty()
    }
}
