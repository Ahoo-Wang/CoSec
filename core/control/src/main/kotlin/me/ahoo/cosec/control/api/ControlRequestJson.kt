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

package me.ahoo.cosec.control.api

import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.api.JsonFields.arrayOf
import me.ahoo.cosec.control.api.JsonFields.field
import me.ahoo.cosec.control.api.JsonFields.long
import me.ahoo.cosec.control.api.JsonFields.longIn
import me.ahoo.cosec.control.api.JsonFields.need
import me.ahoo.cosec.control.api.JsonFields.objectOf
import me.ahoo.cosec.control.api.JsonFields.only
import me.ahoo.cosec.control.api.JsonFields.optBool
import me.ahoo.cosec.control.api.JsonFields.optLong
import me.ahoo.cosec.control.api.JsonFields.optText
import me.ahoo.cosec.control.api.JsonFields.stringIn
import me.ahoo.cosec.control.api.JsonFields.strings
import me.ahoo.cosec.control.api.JsonFields.text
import me.ahoo.cosec.control.change.ChangeId
import me.ahoo.cosec.control.change.ChangeRequestCodec
import me.ahoo.cosec.control.change.Confirmation
import me.ahoo.cosec.control.change.InvalidControlRequestException
import me.ahoo.cosec.control.change.ShadowAck
import me.ahoo.cosec.engine.ViewVersion
import me.ahoo.cosec.model.Action
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.Resource
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.spi.snapshot.SnapshotFormatException
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import tools.jackson.databind.JsonNode
import java.time.Instant

/**
 * The values a control API request carries, read from its JSON body, path and query (RFC 0007 §10.2, §10.3): a
 * simulated request, an access query, a view version, confirmations, a shadow acknowledgement, a document key, a change
 * id, a revision, an instant.
 */
internal object ControlRequestJson {
    fun simulated(node: JsonNode): SimulatedRequest {
        node.only(
            "request",
            "tenant",
            "principal",
            "roles",
            "policies",
            "app",
            "space",
            "action",
            "resource",
            "remoteIp",
            "time",
            "headers",
            "requestAttributes",
            "principalBindings",
            "breakGlass",
        )
        return SimulatedRequest(
            tenant = node.text("tenant").let { field("tenant", "a tenant id") { TenantId(it) } },
            principal = node.optText("principal"),
            roles = node.strings("roles"),
            policies = node.strings("policies"),
            app = node.optText("app").orEmpty(),
            space = node.optText("space").orEmpty(),
            action = action(node),
            resource = resource(node.text("resource")),
            remoteIp = node.optText("remoteIp").orEmpty(),
            time = node.optText("time")?.let { instantOf("time", it).toEpochMilli() } ?: System.currentTimeMillis(),
            headers = node.get("headers")?.let { headers ->
                objectOf("headers", headers).properties().associate { (name, value) ->
                    name to if (value.isArray) value.values().map { it.stringIn("headers") } else listOf(value.stringIn("headers"))
                }
            }.orEmpty(),
            requestAttributes = node.get("requestAttributes")?.let { attributes ->
                objectOf("requestAttributes", attributes).properties().associate { (name, value) ->
                    name to value.stringIn("requestAttributes")
                }
            }.orEmpty(),
            principalBindings = node.optBool("principalBindings") ?: true,
            breakGlass = node.optBool("breakGlass") ?: true,
        )
    }

    fun action(node: JsonNode): Action {
        val action = node.text("action").split(':', limit = 2).takeIf { it.size == 2 }
            ?: throw InvalidControlRequestException("An action is service:verb.")
        return field("action", "service:verb") { Action(action[0], action[1]) }
    }

    fun resource(text: String): Resource = field("resource", "a resource") {
        if (text.startsWith('/')) return@field Resource("http", text, emptyMap())
        val slash = text.indexOf('/')
        if (slash < 0) {
            Resource(
                text,
                "",
                emptyMap()
            )
        } else {
            Resource(text.substring(0, slash), text.substring(slash + 1), emptyMap())
        }
    }

    fun query(node: JsonNode): AccessQuery {
        node.only("query", "tenant", "app", "space", "action", "resource")
        return AccessQuery(
            node.text("tenant").let { field("tenant", "a tenant id") { TenantId(it) } },
            node.optText("app"),
            node.optText("space"),
            action(node),
            resource(node.text("resource"))
        )
    }

    fun version(node: JsonNode): ViewVersion {
        node.only("at", "platform", "app", "tenant")
        return ViewVersion(node.long("platform"), node.optLong("app"), node.optLong("tenant"))
    }

    /** The `deny-nomatch` confirmations a request carries (RFC 0007 §9.5); none when it has no such field. */
    fun confirmations(node: JsonNode): List<Confirmation> =
        node.get("confirmations")?.let { arrayOf("confirmations", it) }?.map(::confirmation).orEmpty()

    fun confirmation(node: JsonNode): Confirmation {
        node.only("confirmations", "policy", "statement", "digest", "kind")
        val policy = node.text("policy")
        val statement = node.long("statement")
        return field("confirmations", "a confirmation") {
            Confirmation(
                PolicyId(policy),
                Math.toIntExact(statement),
                node.text("digest"),
                node.optText("kind") ?: Confirmation.DENY_NOMATCH
            )
        }
    }

    fun ack(node: JsonNode): ShadowAck {
        node.only("ack", "window", "counts")
        return field("ack", "a shadow window's acknowledgement") {
            ShadowAck(
                node.text("window"),
                objectOf("counts", node.need("counts")).properties().associate { (outcome, count) ->
                    outcome to count.longIn("counts")
                }
            )
        }
    }

    /** `platform`, `app:<id>` or `tenant:<id>`; the text is not echoed, as it may hold control characters. */
    fun documentKey(text: String): SnapshotKey = try {
        ChangeRequestCodec.documentKeyOf(text)
    } catch (e: SnapshotFormatException) {
        throw InvalidControlRequestException("That names no document: platform, app:<id> or tenant:<id>.", e)
    }

    fun changeIdOf(text: String): ChangeId = field("change", "a change id") { ChangeId(unescaped(text)) }

    fun revisionOf(name: String, text: String): Long =
        text.toLongOrNull() ?: throw InvalidControlRequestException("[$name] is a revision number.")

    /** An instant in epoch milliseconds' range: one beyond it parses, and would fail later (review L2 of #1240). */
    fun instantOf(name: String, text: String): Instant =
        field(
            name,
            "an ISO-8601 instant such as 2026-01-01T00:00:00Z"
        ) { Instant.parse(text).also { it.toEpochMilli() } }
}
