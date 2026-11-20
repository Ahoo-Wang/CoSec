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
import me.ahoo.cosec.engine.pattern.ActionPattern
import me.ahoo.cosec.engine.pattern.ResourcePattern
import me.ahoo.cosec.model.Effect
import me.ahoo.cosec.model.condition.Attribute

/**
 * A DENY statement or a rate limit rule's statement that is shaped for HTTP: an action pattern of service `http`, or an
 * `http:path:` resource pattern (UC-1). Only such a statement can stop applying to a request a route map maps: one on
 * every action and every resource applies to the mapped request as well. [label] names it in a log line or a finding.
 */
@InternalCoSecApi
class HttpStatement(val label: String, val actions: List<ActionPattern>, val resources: List<ResourcePattern>)

/** This document's [HttpStatement]s, DENY statements first, then rate limit rules; empty for most tenant documents. */
@InternalCoSecApi
fun CompiledDocument.httpStatements(): List<HttpStatement> {
    val denies = allPolicies.flatMap { policy ->
        policy.statements.filter { it.effect == Effect.DENY && httpShaped(it) }
            .map { HttpStatement("DENY ${policy.id}#${it.index}", it.actionPatterns, it.resourcePatterns) }
    }
    val limits = rateLimits.filter { httpShaped(it.statement) }
        .map { HttpStatement("rate limit ${it.id}", it.statement.actionPatterns, it.statement.resourcePatterns) }
    return denies + limits
}

private fun httpShaped(statement: CompiledStatement): Boolean =
    statement.actionPatterns.any { it.service == ResourcePattern.HTTP_TYPE } ||
        statement.resourcePatterns.any { it is ResourcePattern.Http }

/**
 * The labels of the DENY statements and rate limit rules of this document that are on every action and every resource, so
 * not [httpStatements], and whose condition reads `action.verb`, `resource.id` or `resource.var.*` (RTE-5): what a route
 * map changes. A mapped request has the business action's verb and the route's resource, so such a statement, written for
 * the request as received, may stop applying to a route the map maps.
 * Empty for most documents.
 */
@InternalCoSecApi
fun CompiledDocument.mappedVocabularyStatements(): List<String> {
    val denies = allPolicies.flatMap { policy ->
        policy.statements.filter { it.effect == Effect.DENY && readsMapped(it) }.map { "DENY ${policy.id}#${it.index}" }
    }
    return denies + rateLimits.filter { readsMapped(it.statement) }.map { "rate limit ${it.id}" }
}

private fun readsMapped(statement: CompiledStatement): Boolean {
    val condition = statement.condition ?: return false
    val everything = statement.actionPatterns.any { it.service == null } &&
        statement.resourcePatterns.all { it === ResourcePattern.AnyResource }
    return everything && ConditionAttributes.of(condition).any {
        it.kind == Attribute.Kind.ACTION_VERB || it.kind == Attribute.Kind.RESOURCE_ID ||
            it.kind == Attribute.Kind.RESOURCE_VAR
    }
}
