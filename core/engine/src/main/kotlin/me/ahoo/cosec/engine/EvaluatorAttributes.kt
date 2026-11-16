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

package me.ahoo.cosec.engine

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.engine.Evaluator.Companion.EMPTY
import me.ahoo.cosec.engine.Evaluator.Companion.INITIAL_LISTS
import me.ahoo.cosec.engine.Evaluator.Companion.PATH_MALFORMED
import me.ahoo.cosec.engine.Evaluator.Companion.PATH_PARSED
import me.ahoo.cosec.engine.Evaluator.Companion.PATH_UNPARSED
import me.ahoo.cosec.engine.pattern.HttpPath
import me.ahoo.cosec.model.SpaceId
import me.ahoo.cosec.model.condition.Attribute
import me.ahoo.cosec.model.condition.IpAddress

// What conditions and indexes read (RFC 0013 §6): the request's attributes, its roles and address, its decoded path,
// and the positions an index lookup collected.

/** The positions [index] gives for this request, sorted. */
internal fun Evaluator.candidates(index: StatementIndex): IntArray {
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
internal fun Evaluator.decodedPathOf(): HttpPath? {
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

internal fun Evaluator.addOf(positions: IntArray) {
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

/*
 * The attributes of the request (RFC 0005 §3.2). The engine answers what it holds itself: the subject, the scope
 * ScopeResolver checked and the bindings used, the action, the resource and the variables the statement captured,
 * the request time and the blacklist flag. A host input cannot contradict them, nor make up a captured variable.
 * The rest (headers, request attributes) comes from [EngineRequest.attributes].
 */
internal fun Evaluator.stringOf(attribute: Attribute): String? =
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
internal fun Evaluator.target(attribute: Attribute): String? =
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

internal fun Evaluator.boolOf(attribute: Attribute): Boolean? =
    when (attribute.kind) {
        Attribute.Kind.SUBJECT_AUTHENTICATED -> authz.subject.authenticated
        Attribute.Kind.ENVIRONMENT_BLOCKED -> authz.environment.blocked
        Attribute.Kind.EXTERNAL -> request.external.read(attribute) as Boolean?
        else -> host.bool(attribute)
    }

internal fun Evaluator.stringSetOf(attribute: Attribute): Set<String>? =
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

internal fun Evaluator.ipOf(attribute: Attribute): IpAddress? {
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
internal fun Evaluator.subjectRoles(): Set<String> {
    val roles = HashSet<String>()
    for (spaceRoles in request.memberships.roles.values) {
        spaceRoles.mapTo(roles) { it.value }
    }
    return roles
}

/** As the token claims them: `role` in the default space, `role@space` elsewhere. */
internal fun Evaluator.scopedRoles(): Set<String> {
    val roles = HashSet<String>()
    for ((space, spaceRoles) in request.memberships.roles) {
        for (role in spaceRoles) {
            roles += if (space == SpaceId.DEFAULT) role.value else role.value + "@" + space.value
        }
    }
    return roles
}
