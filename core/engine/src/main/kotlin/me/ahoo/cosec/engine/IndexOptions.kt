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
import me.ahoo.cosec.model.condition.Attribute

/**
 * How [CompiledPolicy] indexes its statements (RFC 0005 §6.2). The decision never depends on them (I2); only how many
 * statements are looked at does.
 */
@InternalCoSecApi
class IndexOptions(
    /** A policy's statements of one effect are indexed from this many on; fewer are scanned in order. */
    val minStatements: Int = 8,
    /** A partition of the index with more statements than this is split by a condition attribute. */
    val conditionThreshold: Int = 256,
    /**
     * The attributes a large partition may be split by. Only attributes the engine reads itself. A statement's index
     * keys cover every such attribute its condition reads, whichever these are.
     */
    val attributes: List<Attribute> = DEFAULT_ATTRIBUTES,
) {
    init {
        require(minStatements >= 0 && conditionThreshold >= 0) { "Index thresholds must not be negative." }
        for (attribute in attributes) {
            require(indexable(attribute)) { "Conditions cannot be indexed by $attribute." }
        }
    }

    @InternalCoSecApi
    companion object {
        /** RFC 0005 §4.1. */
        val DEFAULT_ATTRIBUTES: List<Attribute> = listOf(
            Attribute.of(Attribute.Kind.SCOPE_APP),
            Attribute.of(Attribute.Kind.SCOPE_SPACE),
            Attribute.of(Attribute.Kind.SUBJECT_TENANT),
            Attribute.of(Attribute.Kind.RESOURCE_TYPE),
        )

        /**
         * String attributes the evaluator answers from the request itself, so reading one never calls the host and
         * never throws: the index may then rule a statement out before anything else of it is evaluated.
         */
        private val INDEXABLE = setOf(
            Attribute.Kind.SUBJECT_TENANT,
            Attribute.Kind.SCOPE_TENANT,
            Attribute.Kind.SCOPE_APP,
            Attribute.Kind.SCOPE_SPACE,
            Attribute.Kind.RESOURCE_TYPE,
            Attribute.Kind.RESOURCE_ATTRIBUTE,
        )

        val DEFAULT: IndexOptions = IndexOptions()

        internal fun indexable(attribute: Attribute): Boolean = attribute.kind in INDEXABLE
    }
}
