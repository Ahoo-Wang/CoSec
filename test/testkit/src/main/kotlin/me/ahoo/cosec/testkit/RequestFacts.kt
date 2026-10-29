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

@file:OptIn(ExperimentalCoSecApi::class)

package me.ahoo.cosec.testkit

import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.engine.pattern.Captures
import me.ahoo.cosec.model.Action
import me.ahoo.cosec.model.Memberships
import me.ahoo.cosec.model.Resource
import me.ahoo.cosec.model.SpaceId
import me.ahoo.cosec.model.condition.Attribute
import me.ahoo.cosec.model.condition.Attribute.Kind
import me.ahoo.cosec.model.condition.ConditionInput
import me.ahoo.cosec.model.condition.IpAddress
import me.ahoo.cosec.model.scope.Scope
import java.net.URI

/**
 * What the statements of a scenario read about its request: the attribute catalog of RFC 0005 §3.2, filled from the
 * scenario. The engine answers most attributes from its own request; [host] answers the rest (`http.*`,
 * `request.attributes.*`) and injects `"fault"`s.
 */
@ExperimentalCoSecApi
class RequestFacts(
    val authenticated: Boolean,
    val principal: String,
    val scope: Scope,
    val memberships: Memberships,
    val subjectAttributes: Map<String, String>,
    val action: Action,
    val resource: Resource,
    val ip: IpAddress?,
    val time: Long,
    val device: String?,
    val blocked: Boolean,
    /** By lower-case name. */
    val headers: Map<String, String>,
    val requestAttributes: Map<String, String>,
) {
    /** The input of a condition; [captures] are the variables of the statement's resource pattern, if any. */
    fun input(captures: Captures?): ConditionInput = FactsInput(this, captures)

    /**
     * The host attributes of the engine's request: the engine answers the rest itself. A read of
     * `request.attributes.<FAULT><message>` throws [ScenarioFault] (see [DocumentSnapshotCompiler]).
     */
    val host: ConditionInput = object : ConditionInput by FactsInput(this, null) {
        override fun string(attribute: Attribute): String? {
            if (attribute.kind == Kind.REQUEST_ATTRIBUTE && attribute.name.startsWith(FAULT)) {
                throw ScenarioFault(attribute.name.removePrefix(FAULT))
            }
            return input(null).string(attribute)
        }
    }
}

/** An absent attribute reads as null (RFC 0005 §3.4). */
private class FactsInput(private val facts: RequestFacts, private val captures: Captures?) : ConditionInput {
    @Suppress("CyclomaticComplexMethod")
    override fun string(attribute: Attribute): String? = when (attribute.kind) {
        Kind.SUBJECT_ID -> if (facts.authenticated) facts.principal else null
        Kind.SUBJECT_TENANT, Kind.SCOPE_TENANT -> facts.scope.tenant.value
        Kind.SUBJECT_ATTRIBUTE -> facts.subjectAttributes[attribute.name]
        Kind.SCOPE_APP -> facts.scope.app.value
        Kind.SCOPE_SPACE -> facts.scope.space.value
        Kind.ACTION_SERVICE -> facts.action.namespace
        Kind.ACTION_VERB -> facts.action.verb
        Kind.RESOURCE_TYPE -> facts.resource.type
        Kind.RESOURCE_ID -> facts.resource.id
        Kind.RESOURCE_ATTRIBUTE -> facts.resource.attributes[attribute.name]
        Kind.RESOURCE_VAR -> captures?.get(attribute.name)
        Kind.ENVIRONMENT_DEVICE -> facts.device
        Kind.HTTP_HEADER -> facts.headers[attribute.name]
        Kind.HTTP_ORIGIN -> facts.headers[ORIGIN]
        Kind.HTTP_REFERER -> facts.headers[REFERER]
        Kind.HTTP_ORIGIN_HOST -> facts.headers[ORIGIN]?.let(::host)
        Kind.HTTP_REFERER_HOST -> facts.headers[REFERER]?.let(::host)
        Kind.REQUEST_ATTRIBUTE -> facts.requestAttributes[attribute.name]
        else -> null
    }

    override fun bool(attribute: Attribute): Boolean? = when (attribute.kind) {
        Kind.SUBJECT_AUTHENTICATED -> facts.authenticated
        Kind.ENVIRONMENT_BLOCKED -> facts.blocked
        else -> null
    }

    override fun stringSet(attribute: Attribute): Set<String>? = when (attribute.kind) {
        Kind.SUBJECT_ROLES -> facts.memberships.roles.values.flatMapTo(
            LinkedHashSet()
        ) { roles -> roles.map { it.value } }
        Kind.SUBJECT_SCOPED_ROLES -> facts.memberships.roles.flatMapTo(LinkedHashSet()) { (space, roles) ->
            roles.map { if (space == SpaceId.DEFAULT) it.value else "${it.value}@${space.value}" }
        }

        Kind.SCOPE_ROLES -> facts.scope.roles.mapTo(LinkedHashSet()) { it.value }
        else -> null
    }

    override fun ip(attribute: Attribute): IpAddress? = if (attribute.kind == Kind.ENVIRONMENT_IP) facts.ip else null

    override fun time(attribute: Attribute): Long =
        if (attribute.kind == Kind.ENVIRONMENT_TIME) facts.time else ConditionInput.NO_TIME

    private fun host(url: String): String? = runCatching { URI(url).host }.getOrNull()

    private companion object {
        const val ORIGIN = "origin"
        const val REFERER = "referer"
    }
}
