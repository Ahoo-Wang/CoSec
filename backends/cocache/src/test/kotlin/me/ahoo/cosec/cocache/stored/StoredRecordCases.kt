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

package me.ahoo.cosec.cocache.stored

import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.cocache.control.AppListCodec
import me.ahoo.cosec.cocache.control.ControlRecordMembers
import me.ahoo.cosec.cocache.control.RevisionIndexCodec
import me.ahoo.cosec.cocache.shadow.InstanceIndexCodec
import me.ahoo.cosec.cocache.shadow.ReportersCodec
import me.ahoo.cosec.cocache.shadow.ShadowRecordMembers
import me.ahoo.cosec.control.change.ChangeMembers
import me.ahoo.cosec.control.change.ChangeRequestCodec
import me.ahoo.cosec.document.json.CoSecJson
import me.ahoo.cosec.document.shadow.ShadowCodec
import me.ahoo.cosec.document.shadow.ShadowMembers
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ObjectNode

/** Every kind of record but the session's (`TokenFamilyMembersTest`), each with every member its registry names. */
internal object StoredRecordCases {
    private fun bytes(node: JsonNode) = CoSecJson.mapper.writeValueAsBytes(node)

    private fun tree(bytes: ByteArray) = CoSecJson.mapper.readTree(bytes)

    /** One instance's status is the object under its id in the record of every status. */
    private fun status(node: ObjectNode): JsonNode {
        val statuses = CoSecJson.mapper.createObjectNode().set("i1", node)
        return tree(ShadowCodec.encodeStatuses(ShadowCodec.decodeStatuses(bytes(statuses)))).get("i1")
    }

    val ALL: List<StoredRecordCase> = listOf(
        StoredRecordCase(
            ChangeMembers.CHANGE,
            """{"format":1,"id":"c1","key":"tenant:acme","base":{"revision":3,"digest":"SHA-256:000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"},"operations":[],
              "author":{"tenant":"acme","principal":"alice"},"note":"n","kind":"PUBLISH","state":"READY",
              "findings":[{"check":"V9","path":"$","message":"m","status":400,"severity":"WARNING"}],
              "candidate":"c1@1","checkedWith":{"tenant:acme":"SHA-256:000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"},"checkedApps":{"digest":"SHA-256:000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"},
              "createdAt":"2026-11-01T00:00:00Z","updatedAt":"2026-11-01T00:00:00Z","skipShadow":"no traffic",
              "origin":{"asserted":true,"via":{"method":"jwt","credential":"k1","request":"r1"},"peer":"10.0.0.1"},
              "publishedRevision":4,"escalations":[{"subject":"p1","reason":"NOT_COVERED","detail":"d"}],
              "approval":{"approver":{"tenant":"acme","principal":"bob"},"at":"2026-11-01T00:00:00Z","candidate":"c1@1","covers":["p1"],
                "memberships":{"roles":{"default":["admin"]},"attachments":["p2"],"source":"CLAIMS"},
                "attributes":{"dept":"x"},"principalBindings":true,"breakGlass":false,"credentialId":"k2","issuedAt":"2026-11-01T00:00:00Z"},
              "shadow":{"window":"c1@1","startedAt":"2026-11-01T00:00:00Z","closed":{"closedAt":"2026-11-01T00:00:00Z","insufficient":false,"blocked":false,
                "counts":{"SAME":3},"vanished":["i2"]},"reporters":["i1"]},
              "confirmations":[{"policy":"p1","statement":0,"digest":"x","kind":"deny-nomatch"}]}""",
            // Only the base of a document not yet published, revision 0, has no digest.
            absent = mapOf("base.digest" to { (it.get("base") as ObjectNode).put("revision", 0) }),
        ) { tree(ChangeRequestCodec.encode(ChangeRequestCodec.decode(bytes(it)))) },
        StoredRecordCase(ChangeMembers.STAGING_SLOT, """{"format":1,"holder":"c1","since":"2026-11-01T00:00:00Z"}""") {
            tree(ChangeRequestCodec.encode(ChangeRequestCodec.decodeSlot(bytes(it))))
        },
        StoredRecordCase(
            ControlRecordMembers.REVISION_INDEX,
            """{"format":1,"entries":[{"revision":2,"digest":"SHA-256:000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f","publishedAt":"2026-11-01T00:00:00Z","parent":"SHA-256:000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f","size":10}],
              "pending":[{"digest":"SHA-256:000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f","since":"2026-11-01T00:00:00Z"}],"floor":2,"reclaiming":["SHA-256:000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"],
              "indexed":[{"target":"app:order","policy":"perm:order/read","revision":3,"own":true}]}""",
        ) { tree(RevisionIndexCodec.encode(RevisionIndexCodec.decode(bytes(it)))) },
        StoredRecordCase(ControlRecordMembers.APP_LIST, """{"format":1,"apps":["order"],"complete":true}""") {
            tree(AppListCodec.encode(AppListCodec.decode(bytes(it))))
        },
        StoredRecordCase(
            ShadowMembers.CANDIDATE,
            """{"format":1,"window":"c1@1","baseRevision":4,
              "document":{"schema":2,"revision":5,"publishedAt":"2026-11-01T00:00:00Z","policies":[],"bindings":[]}}""",
        ) { tree(ShadowCodec.encode(ShadowCodec.decodeCandidate(bytes(it)))) },
        StoredRecordCase(
            ShadowMembers.SHADOW_REPORT,
            """{"format":1,"window":"c1@1","instance":"i1","counts":{"SAME":3},"samples":{"NARROWED":[{"tenant":"acme",
              "principal":"bob","app":"order","space":"","action":"http:GET","resource":"http//orders/1",
              "enforcedBy":"read[0]","candidateBy":null}]},"baseRevisions":[4],"first":"2026-11-01T00:00:00Z","last":"2026-11-01T00:00:00Z"}""",
        ) { tree(ShadowCodec.encodeReport(ShadowCodec.decodeReport(bytes(it)))) },
        StoredRecordCase(
            ShadowMembers.INSTANCE_STATUS,
            """{"format":1,"at":"2026-11-01T00:00:00Z","formatLevel":1,"windows":["c1@1"],"shadows":false}""",
            rewrite = ::status,
        ),
        StoredRecordCase(
            ShadowMembers.RESIDENT_DOCUMENTS,
            """{"format":1,"instance":"i1","at":"2026-11-01T00:00:00Z","documents":{"tenant:acme":3}}""",
        ) { tree(ShadowCodec.encodeResident(ShadowCodec.decodeResident(bytes(it)))) },
        StoredRecordCase(ShadowRecordMembers.INSTANCE_INDEX, """{"format":1,"instances":{"i1":"2026-11-01T00:00:00Z"}}""") {
            tree(InstanceIndexCodec.encode(InstanceIndexCodec.decode(bytes(it))))
        },
        StoredRecordCase(ShadowRecordMembers.REPORTERS, """{"format":1,"reporters":["i1","i2"]}""") {
            tree(ReportersCodec.encode(ReportersCodec.decode(bytes(it))))
        },
    )
}
