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

package me.ahoo.cosec.migrate

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.engine.StatementSource
import me.ahoo.cosec.model.condition.Condition
import me.ahoo.cosec.model.condition.Operand

/**
 * What a converted DENY or rate limit rule says in the vocabulary of an *unmapped* request (R2E-1, `MIG-5`): an `http:`
 * action, an `http:path:` resource, or a condition on `resource.id`, `action.verb` or `resource.var.*`, which 7.x called
 * `request.path`, `request.method` and `request.path.var.*`. A route map takes the routes it maps out of that vocabulary:
 * a mapped request is its business action on its own resource, and what such a statement reads is then another value, so it
 * stops applying to the route. For an ALLOW the same effect is loud (the route is 403, C8); for a DENY it is a request let
 * through, which only the report and `upgrading.md` can say.
 */
internal object UnmappedVocabulary {
    /** What [statement] says in that vocabulary, in words; empty when it says none. */
    fun of(statement: StatementSource): List<String> = buildList {
        if (statement.actions.any { it.startsWith(MatcherTranslator.HTTP) }) {
            add("an http: action")
        }
        if (statement.resources.any { it.pattern.startsWith(MatcherTranslator.HTTP_PATH) }) {
            add("an http:path: resource")
        }
        addAll(reads(statement.condition))
    }

    /** The attributes [condition] reads that a mapped request changes, each as a phrase; empty for none. */
    fun reads(condition: Condition?): List<String> =
        paths(condition).filter { it == RESOURCE_ID || it == ACTION_VERB || it.startsWith(RESOURCE_VAR) }
            .distinct().map { "a condition on $it" }

    /** Whether [reads] names `resource.id`: the path as received, encoded, which 7.x read from the raw request URI. */
    fun readsPath(condition: Condition?): Boolean = paths(condition).any { it == RESOURCE_ID }

    private fun paths(condition: Condition?): List<String> = when (condition) {
        null, is Condition.Time -> emptyList()
        is Condition.Eq -> listOfNotNull(condition.attribute, (condition.value as? Operand.Attr)?.path)
        is Condition.In -> listOf(condition.attribute)
        is Condition.Contains -> listOf(condition.attribute)
        is Condition.Prefix -> listOf(condition.attribute)
        is Condition.Cidr -> listOf(condition.attribute)
        is Condition.Exists -> listOf(condition.attribute)
        is Condition.And -> condition.conditions.flatMap(::paths)
        is Condition.Or -> condition.conditions.flatMap(::paths)
        is Condition.Not -> paths(condition.condition)
    }

    private const val RESOURCE_ID = "resource.id"
    private const val ACTION_VERB = "action.verb"
    private const val RESOURCE_VAR = "resource.var."
}
