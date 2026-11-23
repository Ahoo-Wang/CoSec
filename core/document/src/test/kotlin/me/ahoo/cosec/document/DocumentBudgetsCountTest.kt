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
import me.ahoo.cosec.model.decision.DocumentKind
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

/**
 * `CTL-40`, DR1-B-1: a budget counts what a document grants, the entries its short forms derive included: a role's
 * bindings are bindings to a role, a catalog permission with `action` a policy of one statement. Its written-out form
 * ([RoleExpansion.effective]) counts the same.
 */
@Suppress("MaxLineLength")
class DocumentBudgetsCountTest {
    private fun document(kind: DocumentKind, owner: String, body: String): PolicyDocument = PolicyDocumentCodec.decode(
        kind,
        owner,
        """{"schema":2,"revision":1,"publishedAt":"2026-11-01T00:00:00Z"$body}""".encodeToByteArray(),
    )

    @Test
    fun `a tenant's roles count as the bindings they derive`() {
        val tenant = document(
            DocumentKind.TENANT,
            "acme",
            ""","roles":[{"id":"night","app":"wms","permissions":["read","edit","ship"]},
                         {"id":"day","app":"wms","permissions":["read"]}],
               "policies":[{"id":"p","statements":[{"effect":"allow","action":"wms:read"}]}],
               "bindings":[{"id":"b","subject":{"role":"clerk"},"app":"wms","space":"*","policy":"p"}]""",
        )
        DocumentBudgets.Entry.ROLE_BINDINGS.count(tenant).assert().isEqualTo(1 + 4)
        DocumentBudgets.Entry.POLICIES.count(tenant).assert().isEqualTo(1)
    }

    @Test
    fun `an app's catalog permissions with an action count as policies of one statement, and its roles as bindings`() {
        val app = document(
            DocumentKind.APP,
            "wms",
            ""","catalog":{"groups":[{"name":"ops","permissions":[{"id":"read","action":"wms:read"},
                 {"id":"edit","action":"wms:edit"},{"id":"label"}]}]},
               "roles":[{"id":"all","permissions":"*"}],
               "policies":[{"id":"p","statements":[{"effect":"allow","action":"wms:a"},{"effect":"allow","action":"wms:b"}]}]""",
        )
        DocumentBudgets.Entry.POLICIES.count(app).assert().isEqualTo(1 + 2)
        DocumentBudgets.Entry.STATEMENTS.count(app).assert().isEqualTo(2 + 2)
        DocumentBudgets.Entry.ROLE_BINDINGS.count(app).assert().isEqualTo(2)
        val effective = RoleExpansion.effective(app)
        for (entry in DocumentBudgets.Entry.entries) {
            entry.count(effective).assert().describedAs("$entry written out").isEqualTo(entry.count(app))
        }
    }
}
