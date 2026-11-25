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

package me.ahoo.cosec.compat

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

/**
 * MIX-12 to MIX-15, the control plane's and the shadow window's records between versions (CTL-53, SNP-27, SNP-28): a
 * record with every member the current version writes, the previous one reads and writes back unchanged; a later member
 * without `crit` is dropped by a rewrite, which leaves the same record; one `crit` names is refused by a version that
 * does not know it, which then writes nothing back.
 */
class RecordCompatTest {
    private val digest = "SHA-256:000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"

    private val records = mapOf(
        "change" to """{"format":1,"id":"c1","key":"tenant:acme","base":{"revision":3,"digest":"$digest"},"operations":[],""" +
            """"author":{"tenant":"acme","principal":"alice"},"note":"n","kind":"PUBLISH","state":"READY",""" +
            """"findings":[{"check":"V9","path":"$","message":"m","status":400,"severity":"WARNING"}],""" +
            """"candidate":"c1@1","checkedWith":{"tenant:acme":"$digest"},"checkedApps":{"digest":"$digest"},""" +
            """"createdAt":"2026-11-01T00:00:00Z","updatedAt":"2026-11-01T00:00:00Z","skipShadow":"no traffic",""" +
            """"origin":{"asserted":true,"via":{"method":"jwt","credential":"k1","request":"r1"},"peer":"10.0.0.1"},""" +
            """"publishedRevision":4,"escalations":[{"subject":"p1","reason":"NOT_COVERED","detail":"d"}],""" +
            """"approval":{"approver":{"tenant":"acme","principal":"bob"},"at":"2026-11-01T00:00:00Z","candidate":"c1@1","covers":["p1"],""" +
            """"memberships":{"roles":{"default":["admin"]},"attachments":["p2"],"source":"CLAIMS"},""" +
            """"attributes":{"dept":"x"},"principalBindings":true,"breakGlass":false,"credentialId":"k2","issuedAt":"2026-11-01T00:00:00Z"},""" +
            """"shadow":{"window":"c1@1","startedAt":"2026-11-01T00:00:00Z","closed":{"closedAt":"2026-11-01T00:00:00Z","insufficient":false,"blocked":false,""" +
            """"counts":{"SAME":3},"vanished":["i2"]},"reporters":["i1"]},""" +
            """"confirmations":[{"policy":"p1","statement":0,"digest":"x","kind":"deny-nomatch"}]}""",
        "staging_slot" to """{"format":1,"holder":"c1","since":"2026-11-01T00:00:00Z"}""",
        "candidate" to """{"format":1,"window":"c1@1","baseRevision":4,""" +
            """"document":{"schema":2,"revision":5,"publishedAt":"2026-11-01T00:00:00Z","policies":[],"bindings":[]}}""",
        "shadow_report" to """{"format":1,"window":"c1@1","instance":"i1","counts":{"SAME":3},"samples":{"NARROWED":[{"tenant":"acme",""" +
            """"principal":"bob","app":"order","space":"","action":"http:GET","resource":"http//orders/1",""" +
            """"enforcedBy":"read[0]","candidateBy":null}]},"baseRevisions":[4],"first":"2026-11-01T00:00:00Z","last":"2026-11-01T00:00:00Z"}""",
        "resident_documents" to """{"format":1,"instance":"i1","at":"2026-11-01T00:00:00Z","documents":{"tenant:acme":3}}""",
    )

    /** An instance's status is the object under its id, in the record of every status. */
    private val status = """{"format":1,"at":"2026-11-01T00:00:00Z","formatLevel":1,"windows":["c1@1"],"shadows":false}"""

    private fun all(): Map<String, String> = records + ("instance_status" to """{"i1":$status}""")

    private fun rewrite(side: Side, kind: String, value: String) = side(
        "record.rewrite",
        "kind" to kind,
        "value" to value
    )

    /** [record] with members added at its top, or at its one instance's status. */
    private fun adding(kind: String, record: String, vararg members: String): String =
        if (kind == "instance_status") {
            """{"i1":${HeaderJson.adding(status, *members)}}"""
        } else {
            HeaderJson.adding(record, *members)
        }

    @Test
    fun `each version reads and writes back the record the other wrote, unchanged`() {
        for ((kind, record) in all()) {
            val current = rewrite(Side.CURRENT, kind, record)
            current.assert().describedAs(kind).doesNotStartWith("refused:")
            rewrite(Side.PREVIOUS, kind, current).assert().describedAs(kind).isEqualTo(current)
            rewrite(
                Side.CURRENT,
                kind,
                rewrite(Side.PREVIOUS, kind, record)
            ).assert().describedAs(kind).isEqualTo(current)
        }
    }

    @Test
    fun `a later member without crit is dropped by a rewrite, which leaves the same record`() {
        for ((kind, record) in all()) {
            val current = rewrite(Side.CURRENT, kind, record)
            val later = adding(kind, record, "\"hint\":1")
            for (side in listOf(Side.CURRENT, Side.PREVIOUS)) {
                rewrite(side, kind, later).assert().describedAs("$side, $kind").isEqualTo(current)
            }
        }
    }

    @Test
    fun `a record whose crit names a member a version does not know is refused by it`() {
        for ((kind, record) in all()) {
            val later = adding(kind, record, "\"device\":\"d1\"", "\"crit\":[\"device\"]")
            rewrite(Side.CURRENT, kind, later).assert().describedAs(kind).isEqualTo("refused:format")
            Baseline.from("8.0.0-beta.9", "the previous version refuses a $kind record crit names a member of") {
                rewrite(Side.PREVIOUS, kind, later).assert().describedAs(kind).isEqualTo("refused:format")
            }
        }
    }
}
