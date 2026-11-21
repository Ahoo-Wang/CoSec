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

package me.ahoo.cosec.control.sdk

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.sdk.WireJson.array
import me.ahoo.cosec.control.sdk.WireJson.bool
import me.ahoo.cosec.control.sdk.WireJson.need
import me.ahoo.cosec.control.sdk.WireJson.oneOrMany
import me.ahoo.cosec.control.sdk.WireJson.only
import me.ahoo.cosec.control.sdk.WireJson.opt
import me.ahoo.cosec.control.sdk.WireJson.optInstant
import me.ahoo.cosec.control.sdk.WireJson.optText
import me.ahoo.cosec.control.sdk.WireJson.putOneOrMany
import me.ahoo.cosec.control.sdk.WireJson.putTexts
import me.ahoo.cosec.control.sdk.WireJson.text
import me.ahoo.cosec.control.sdk.WireJson.texts
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ObjectNode
import tools.jackson.databind.node.StringNode

/**
 * A document's entries as the document format writes them (`schema/8.0/`), both ways (`CTL-76`). Reading is strict
 * about members ([MEMBERS]); what each value may be is the control plane's to check, when the change is proposed.
 */
internal object DocumentJson {
    /** The members the typed layer reads of each entry: the document format's, which `CTL-76`'s test holds to the schema. */
    val MEMBERS: Map<String, Set<String>> = mapOf(
        "policy" to setOf("id", "statements", "attachable", "condition", "boundary"),
        "statement" to setOf("effect", "action", "resource", "condition", "name", "onMissing", "onMissingConfirmed"),
        "onMissingConfirmed" to setOf("by", "change"),
        "resource" to setOf("pattern", "caseSensitive", "decodeAndParseSegments"),
        "binding" to setOf("id", "subject", "app", "space", "policy", "expiresAt", "breakGlass"),
        "principalSubject" to setOf("principal", "tenant"),
        "roleSubject" to setOf("role", "tenant"),
        "role" to setOf("id", "app", "space", "name", "description", "permissions"),
        "catalog" to setOf("groups"),
        "group" to setOf("name", "permissions", "spaced"),
        "permission" to setOf("id", "name", "action", "resource"),
        "document" to setOf(
            "schema", "revision", "publishedAt", "provenance",
            "policies", "bindings", "roles", "catalog", "rateLimits", "boundary",
        ),
    )

    private fun members(entry: String): Set<String> = MEMBERS.getValue(entry)

    fun content(node: JsonNode): DocumentContent {
        node.only("document", members("document"))
        return DocumentContent(
            policies = node.array("policies").map(::policy),
            bindings = node.array("bindings").map(::binding),
            roles = node.array("roles").map(::role),
            catalog = node.opt("catalog")?.let(::catalog),
            rateLimits = node.array("rateLimits").map { RateLimitRule.of(WireJson.write(it)) },
            boundary = node.optText("boundary"),
        )
    }

    /** [content] as a document of revision [revision]: the header `:apply` reads its base from. */
    fun document(content: DocumentContent, revision: Long): ObjectNode = WireJson.objectNode().apply {
        put("schema", SCHEMA)
        put("revision", revision)
        // `:apply` reads the header's revision only; the time is the control plane's to write.
        put("publishedAt", "1970-01-01T00:00:00Z")
        if (content.policies.isNotEmpty()) putArray("policies").apply { content.policies.forEach { add(policy(it)) } }
        if (content.bindings.isNotEmpty()) putArray("bindings").apply { content.bindings.forEach { add(binding(it)) } }
        if (content.roles.isNotEmpty()) putArray("roles").apply { content.roles.forEach { add(role(it)) } }
        content.catalog?.let { set("catalog", catalog(it)) }
        if (content.rateLimits.isNotEmpty()) {
            putArray("rateLimits").apply { content.rateLimits.forEach { add(ValueJson.rateLimit(it)) } }
        }
        content.boundary?.let { put("boundary", it) }
    }

    fun policy(node: JsonNode): Policy {
        node.only("policy", members("policy"))
        return Policy(
            id = node.text("id"),
            statements = node.array("statements").map(::statement),
            attachable = node.bool("attachable", false),
            condition = node.opt("condition")?.let(ValueJson::condition),
            boundary = node.bool("boundary", false),
        )
    }

    fun policy(policy: Policy): ObjectNode = WireJson.objectNode().apply {
        put("id", policy.id)
        putArray("statements").apply { policy.statements.forEach { add(statement(it)) } }
        if (policy.attachable) put("attachable", true)
        policy.condition?.let { set("condition", ValueJson.condition(it)) }
        if (policy.boundary) put("boundary", true)
    }

    private fun statement(node: JsonNode): Statement {
        node.only("statement", members("statement"))
        return Statement(
            effect = Effect.of(node.text("effect")),
            actions = node.oneOrMany("action").map(ValueJson::action),
            resources = node.oneOrMany("resource").map(ValueJson::resource),
            condition = node.opt("condition")?.let(ValueJson::condition),
            name = node.optText("name"),
            onMissing = node.optText("onMissing")?.let(OnMissing::of),
            onMissingConfirmed = node.opt("onMissingConfirmed")?.let { confirmed ->
                confirmed.only("onMissingConfirmed", members("onMissingConfirmed"))
                OnMissingConfirmation(AnswerJson.actor(confirmed.need("by")), confirmed.text("change"))
            },
        )
    }

    private fun statement(statement: Statement): ObjectNode = WireJson.objectNode().apply {
        statement.name?.let { put("name", it) }
        put("effect", statement.effect.text)
        putOneOrMany("action", statement.actions.map(StringNode::valueOf))
        putOneOrMany("resource", statement.resources.map(ValueJson::resource))
        statement.condition?.let { set("condition", ValueJson.condition(it)) }
        statement.onMissing?.let { put("onMissing", it.text) }
        statement.onMissingConfirmed?.let {
            putObject("onMissingConfirmed").apply {
                set("by", AnswerJson.actor(it.by))
                put("change", it.change)
            }
        }
    }

    fun binding(node: JsonNode): Binding {
        node.only("binding", members("binding"))
        return Binding(
            id = node.text("id"),
            subject = subject(node.need("subject")),
            app = node.text("app"),
            space = node.text("space"),
            policy = node.text("policy"),
            expiresAt = node.optInstant("expiresAt"),
            breakGlass = node.bool("breakGlass", false),
        )
    }

    fun binding(binding: Binding): ObjectNode = WireJson.objectNode().apply {
        put("id", binding.id)
        set("subject", subject(binding.subject))
        put("app", binding.app)
        put("space", binding.space)
        put("policy", binding.policy)
        binding.expiresAt?.let { put("expiresAt", it.toString()) }
        if (binding.breakGlass) put("breakGlass", true)
    }

    private fun subject(node: JsonNode): Subject = when {
        node.isString && node.asString() == ANYONE -> Subject.Anyone
        node.isObject && node.has("principal") -> {
            node.only("subject", members("principalSubject"))
            Subject.Principal(node.text("principal"), node.optText("tenant"))
        }
        node.isObject && node.has("role") -> {
            node.only("subject", members("roleSubject"))
            Subject.Role(node.text("role"), node.optText("tenant"))
        }
        else -> throw WireJson.format("[subject] is not *, a principal or a role")
    }

    private fun subject(subject: Subject): JsonNode = when (subject) {
        Subject.Anyone -> StringNode.valueOf(ANYONE)
        is Subject.Principal -> WireJson.objectNode().put("principal", subject.id).apply {
            subject.tenant?.let { put("tenant", it) }
        }
        is Subject.Role -> WireJson.objectNode().put("role", subject.id).apply {
            subject.tenant?.let { put("tenant", it) }
        }
    }

    fun role(node: JsonNode): Role {
        node.only("role", members("role"))
        val permissions = node.need("permissions")
        return Role(
            id = node.text("id"),
            permissions = when {
                permissions.isString && permissions.asString() == ANYONE -> Permissions.All
                permissions.isArray -> Permissions.Of(node.texts("permissions"))
                else -> throw WireJson.format("[permissions] is not * or a list of permissions")
            },
            app = node.optText("app"),
            space = node.optText("space"),
            name = node.optText("name"),
            description = node.optText("description"),
        )
    }

    fun role(role: Role): ObjectNode = WireJson.objectNode().apply {
        put("id", role.id)
        role.app?.let { put("app", it) }
        role.space?.let { put("space", it) }
        role.name?.let { put("name", it) }
        role.description?.let { put("description", it) }
        when (val permissions = role.permissions) {
            Permissions.All -> put("permissions", ANYONE)
            is Permissions.Of -> putTexts("permissions", permissions.ids)
        }
    }

    fun catalog(node: JsonNode): Catalog {
        node.only("catalog", members("catalog"))
        return Catalog(
            node.array("groups").map { group ->
                group.only("catalog group", members("group"))
                CatalogGroup(
                    group.text("name"),
                    group.array("permissions").map { permission ->
                        permission.only("catalog permission", members("permission"))
                        CatalogPermission(
                            permission.text("id"),
                            permission.optText("name"),
                            permission.oneOrMany("action").map(ValueJson::action),
                            permission.oneOrMany("resource").map(ValueJson::resource),
                        )
                    },
                    group.bool("spaced", false),
                )
            },
        )
    }

    fun catalog(catalog: Catalog): ObjectNode = WireJson.objectNode().apply {
        putArray("groups").apply {
            catalog.groups.forEach { group ->
                addObject().apply {
                    put("name", group.name)
                    if (group.spaced) put("spaced", true)
                    putArray("permissions").apply {
                        group.permissions.forEach { permission ->
                            addObject().apply {
                                put("id", permission.id)
                                permission.name?.let { put("name", it) }
                                putOneOrMany("action", permission.actions.map(StringNode::valueOf))
                                putOneOrMany("resource", permission.resources.map(ValueJson::resource))
                            }
                        }
                    }
                }
            }
        }
    }


    private const val ANYONE = "*"
    private const val SCHEMA = 2
}
