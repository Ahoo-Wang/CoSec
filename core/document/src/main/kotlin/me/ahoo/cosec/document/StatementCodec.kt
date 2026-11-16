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
import me.ahoo.cosec.document.DocumentJson.actor
import me.ahoo.cosec.document.DocumentJson.factory
import me.ahoo.cosec.engine.OnMissing
import me.ahoo.cosec.engine.OnMissingConfirmation
import me.ahoo.cosec.engine.ResourceSource
import me.ahoo.cosec.engine.StatementSource
import me.ahoo.cosec.model.Effect
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.TenantId
import tools.jackson.databind.node.ObjectNode

/**
 * The statement of a policy or a rate limit rule, both ways, with the parts only a statement is made of: its resources,
 * its `onMissing` and the confirmation of a `nomatch` DENY. Resources are also a catalog permission's, so they are read
 * and written here for it too.
 */
internal object StatementCodec {
    private val STATEMENT_FIELDS =
        arrayOf("name", "effect", "action", "resource", "condition", "onMissing", "onMissingConfirmed")
    private val CONFIRMATION_FIELDS = arrayOf("by", "change")
    private val RESOURCE_FIELDS = arrayOf("pattern", "caseSensitive", "decodeAndParseSegments")

    internal fun statement(node: DocumentNode): StatementSource {
        node.requireObject(*STATEMENT_FIELDS)
        val effect = node.required("effect")
        return StatementSource(
            name = node.field("name")?.text() ?: "",
            effect = when (val text = effect.text()) {
                "allow" -> Effect.ALLOW
                "deny" -> Effect.DENY
                else -> effect.fail("an effect is allow or deny, not [$text]")
            },
            actions = node.required("action").strings(),
            resources = node.field("resource")?.let(::resources).orEmpty(),
            condition = node.field("condition")?.let(ConditionJson::read),
            onMissing = node.field("onMissing")?.let(::onMissing),
            onMissingConfirmed = node.field("onMissingConfirmed")?.let(::confirmation),
        )
    }

    /** RFC 0007 §9.5: who confirmed a `nomatch` DENY, and in which change. */
    private fun confirmation(node: DocumentNode): OnMissingConfirmation {
        node.requireObject(*CONFIRMATION_FIELDS)
        val by = actor(node.required("by"))
        return OnMissingConfirmation(by.tenant.value, by.principal.value, node.required("change").text())
    }

    internal fun onMissing(node: DocumentNode): OnMissing = when (val text = node.text()) {
        "indeterminate" -> OnMissing.INDETERMINATE
        "nomatch" -> OnMissing.NOMATCH
        else -> node.fail("onMissing is indeterminate or nomatch, not [$text]")
    }

    /** A pattern or an object with options, or an array of them. */
    internal fun resources(node: DocumentNode): List<ResourceSource> {
        val items = if (node.json.isArray) {
            node.elements().ifEmpty {
                node.fail(
                    "expected at least one resource"
                )
            }
        } else {
            listOf(node)
        }
        return items.map { item ->
            if (item.json.isObject) {
                item.requireObject(*RESOURCE_FIELDS)
                ResourceSource(
                    item.required("pattern").text(),
                    caseSensitive = item.field("caseSensitive")?.bool() ?: true,
                    decodeAndParseSegments = item.field("decodeAndParseSegments")?.bool() ?: true,
                )
            } else {
                ResourceSource(item.text())
            }
        }
    }

    internal fun statement(statement: StatementSource): ObjectNode {
        val item = factory.objectNode()
        if (statement.name.isNotEmpty()) item.put("name", statement.name)
        item.put("effect", if (statement.effect == Effect.DENY) "deny" else "allow")
        item.putArray("action").also { actions -> statement.actions.forEach(actions::add) }
        putResources(item, statement.resources)
        statement.condition?.let { item.set("condition", ConditionJson.write(it)) }
        statement.onMissing?.let {
            item.put(
                "onMissing",
                if (it == OnMissing.NOMATCH) "nomatch" else "indeterminate"
            )
        }
        statement.onMissingConfirmed?.let {
            item.putObject("onMissingConfirmed")
                .put("change", it.change)
                .set("by", actor(Actor(TenantId(it.tenant), PrincipalId(it.principal))))
        }
        return item
    }

    internal fun putResources(item: ObjectNode, written: List<ResourceSource>) {
        if (written.isEmpty()) {
            return
        }
        val resources = item.putArray("resource")
        for (resource in written) {
            if (resource.hasOptions()) {
                resources.addObject().put("pattern", resource.pattern)
                    .put("caseSensitive", resource.caseSensitive)
                    .put("decodeAndParseSegments", resource.decodeAndParseSegments)
            } else {
                resources.add(resource.pattern)
            }
        }
    }
}
