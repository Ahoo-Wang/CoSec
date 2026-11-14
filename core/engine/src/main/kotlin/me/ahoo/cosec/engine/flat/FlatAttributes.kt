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

package me.ahoo.cosec.engine.flat

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.engine.pattern.Captures
import me.ahoo.cosec.engine.pattern.EvaluationScratch
import me.ahoo.cosec.engine.pattern.HttpPath
import me.ahoo.cosec.engine.pattern.ResourceTarget
import me.ahoo.cosec.engine.pattern.ScratchSource
import me.ahoo.cosec.model.Action
import me.ahoo.cosec.model.AuthzRequest
import me.ahoo.cosec.model.Effect
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.SpaceId
import me.ahoo.cosec.model.condition.Attribute
import me.ahoo.cosec.model.condition.AttributeType
import me.ahoo.cosec.model.condition.CheckedCondition
import me.ahoo.cosec.model.condition.ConditionInput
import me.ahoo.cosec.model.condition.IpAddress
import me.ahoo.cosec.model.decision.BindingRef
import me.ahoo.cosec.model.decision.Cause
import me.ahoo.cosec.model.decision.Decision
import me.ahoo.cosec.model.decision.DocumentKind
import me.ahoo.cosec.model.decision.Obligation
import me.ahoo.cosec.model.decision.StatementRef
import java.util.IdentityHashMap
import me.ahoo.cosec.engine.flat.FlatEvaluator.Companion.MATCHED
import me.ahoo.cosec.engine.flat.FlatEvaluator.Companion.ACTION_MISS
import me.ahoo.cosec.engine.flat.FlatEvaluator.Companion.CONDITION_MISS
import me.ahoo.cosec.engine.flat.FlatEvaluator.Companion.RESOURCE_MISS
import me.ahoo.cosec.engine.flat.FlatEvaluator.Companion.GUARD_MISS
import me.ahoo.cosec.engine.flat.FlatEvaluator.Companion.GUARD_FAILED
import me.ahoo.cosec.engine.flat.FlatEvaluator.Companion.ERROR
import me.ahoo.cosec.engine.flat.FlatEvaluator.Companion.NEXT_STATEMENT
import me.ahoo.cosec.engine.flat.FlatEvaluator.Companion.NEXT_POLICY
import me.ahoo.cosec.engine.flat.FlatEvaluator.Companion.DECIDED
import me.ahoo.cosec.engine.flat.FlatEvaluator.Companion.GUARD_MATCHED
import me.ahoo.cosec.engine.flat.FlatEvaluator.Companion.GUARD_MISSED
import me.ahoo.cosec.engine.flat.FlatEvaluator.Companion.GUARD_ERROR
import me.ahoo.cosec.engine.flat.FlatEvaluator.Companion.INITIAL_GUARDS
import me.ahoo.cosec.engine.flat.FlatEvaluator.Companion.SCAN_GUARDS
import me.ahoo.cosec.engine.flat.FlatEvaluator.Companion.MAP_GUARDS
import me.ahoo.cosec.engine.flat.FlatEvaluator.Companion.NOT_REMEMBERED
import me.ahoo.cosec.engine.flat.FlatEvaluator.Companion.INITIAL_LISTS
import me.ahoo.cosec.engine.flat.FlatEvaluator.Companion.WORD_BITS
import me.ahoo.cosec.engine.flat.FlatEvaluator.Companion.WORD_SHIFT
import me.ahoo.cosec.engine.flat.FlatEvaluator.Companion.PATH_UNPARSED
import me.ahoo.cosec.engine.flat.FlatEvaluator.Companion.PATH_PARSED
import me.ahoo.cosec.engine.flat.FlatEvaluator.Companion.PATH_MALFORMED
import me.ahoo.cosec.engine.flat.FlatEvaluator.Companion.EMPTY

// What conditions and indexes read: request attributes, roles, the parsed path and the address.

/** The positions [index] gives for this request, sorted. */
internal fun FlatEvaluator.candidates(index: StatementIndex): IntArray {
    listCount = 0
    index.lookup(this)
    val count = listCount
    if (count <= 1) {
        val first = firstList
        firstList = null
        return first ?: EMPTY
    }
    val lists = lists!!
    lists[0] = firstList
    firstList = null
    var total = 0
    for (i in 0 until count) {
        total += lists[i]!!.size
    }
    val merged = IntArray(total)
    var at = 0
    for (i in 0 until count) {
        val list = lists[i]!!
        list.copyInto(merged, at)
        at += list.size
        lists[i] = null
    }
    merged.sort()
    var size = 0
    for (i in merged.indices) {
        if (size == 0 || merged[size - 1] != merged[i]) {
            merged[size++] = merged[i]
        }
    }
    return if (size == merged.size) merged else merged.copyOf(size)
}

@Suppress("SwallowedException")
internal fun FlatEvaluator.decodedPathOf(): HttpPath? {
    if (pathState == PATH_UNPARSED) {
        pathState = try {
            target.httpPath(true)
            PATH_PARSED
        } catch (malformed: IllegalArgumentException) {
            // Matching the patterns fails the statements instead; the index only learns it cannot use the path.
            PATH_MALFORMED
        }
    }
    return if (pathState == PATH_PARSED) target.httpPath(true) else null
}

internal fun FlatEvaluator.addOf(positions: IntArray) {
    if (positions.isEmpty()) {
        return
    }
    if (listCount == 0) {
        firstList = positions
        listCount = 1
        return
    }
    var lists = lists
    if (lists == null) {
        lists = arrayOfNulls(INITIAL_LISTS)
        this.lists = lists
    } else if (listCount == lists.size) {
        lists = lists.copyOf(listCount * 2)
        this.lists = lists
    }
    lists[listCount++] = positions
}

internal fun FlatEvaluator.stringOf(attribute: Attribute): String? =
    when (attribute.kind) {
        Attribute.Kind.SUBJECT_ID -> if (authz.subject.authenticated) authz.subject.id else null
        Attribute.Kind.SUBJECT_TENANT, Attribute.Kind.SCOPE_TENANT -> request.scope.tenant.value
        Attribute.Kind.SUBJECT_ATTRIBUTE -> SubjectAttributes.string(authz.subject.attributes, attribute.name)
        Attribute.Kind.SCOPE_APP -> request.scope.app.value.takeUnless { it.isEmpty() }
        Attribute.Kind.SCOPE_SPACE -> request.scope.space.value
        Attribute.Kind.ENVIRONMENT_DEVICE -> authz.environment.deviceId.takeUnless { it.isEmpty() }
        else -> target(attribute)
    }

/** The action, the resource and the captures; the host for the rest. */
internal fun FlatEvaluator.target(attribute: Attribute): String? =
    when (attribute.kind) {
        Attribute.Kind.ACTION_SERVICE -> authz.action.namespace
        Attribute.Kind.ACTION_VERB -> authz.action.verb
        Attribute.Kind.RESOURCE_TYPE -> authz.resource.type
        Attribute.Kind.RESOURCE_ID -> authz.resource.id
        Attribute.Kind.RESOURCE_ATTRIBUTE -> authz.resource.attributes[attribute.name]
        Attribute.Kind.RESOURCE_VAR -> captures?.get(attribute.name)
        Attribute.Kind.EXTERNAL -> request.external.read(attribute) as String?
        else -> host.string(attribute)
    }

internal fun FlatEvaluator.boolOf(attribute: Attribute): Boolean? =
    when (attribute.kind) {
        Attribute.Kind.SUBJECT_AUTHENTICATED -> authz.subject.authenticated
        Attribute.Kind.ENVIRONMENT_BLOCKED -> authz.environment.blocked
        Attribute.Kind.EXTERNAL -> request.external.read(attribute) as Boolean?
        else -> host.bool(attribute)
    }

internal fun FlatEvaluator.stringSetOf(attribute: Attribute): Set<String>? =
    when (attribute.kind) {
        Attribute.Kind.SUBJECT_ROLES -> subjectRoles ?: subjectRoles().also { subjectRoles = it }
        Attribute.Kind.SUBJECT_SCOPED_ROLES -> scopedRoles ?: scopedRoles().also { scopedRoles = it }
        Attribute.Kind.SCOPE_ROLES -> scopeRoles ?: request.scope.roles.mapTo(HashSet()) { it.value }
            .also { scopeRoles = it }

        @Suppress("UNCHECKED_CAST")
        Attribute.Kind.EXTERNAL
        -> request.external.read(attribute) as Set<String>?
        else -> host.stringSet(attribute)
    }

internal fun FlatEvaluator.ipOf(attribute: Attribute): IpAddress? {
    if (attribute.kind == Attribute.Kind.EXTERNAL) {
        return request.external.read(attribute) as IpAddress?
    }
    if (attribute.kind != Attribute.Kind.ENVIRONMENT_IP) {
        return host.ip(attribute)
    }
    if (!remoteIpParsed) {
        remoteIp = IpAddress.parse(authz.environment.remoteIp)
        remoteIpParsed = true
    }
    // The host reported something that is no address (`unknown`, a proxy's obfuscated identifier): the client's
    // address could not be read, so it is unavailable, as a missing external attribute is (PIP-17), never absent,
    // which a DENY would read as "no match". An empty one is absent: nothing was reported, as in a check or a
    // simulation that names no address.
    if (remoteIp == null && authz.environment.remoteIp.isNotEmpty()) {
        throw AttributeMissing(Attribute.Kind.ENVIRONMENT_IP.path)
    }
    return remoteIp
}

/** Bare role ids in any space. */
internal fun FlatEvaluator.subjectRoles(): Set<String> {
    val roles = HashSet<String>()
    for (spaceRoles in request.memberships.roles.values) {
        spaceRoles.mapTo(roles) { it.value }
    }
    return roles
}

/** As the token claims them: `role` in the default space, `role@space` elsewhere. */
internal fun FlatEvaluator.scopedRoles(): Set<String> {
    val roles = HashSet<String>()
    for ((space, spaceRoles) in request.memberships.roles) {
        for (role in spaceRoles) {
            roles += if (space == SpaceId.DEFAULT) role.value else role.value + "@" + space.value
        }
    }
    return roles
}
