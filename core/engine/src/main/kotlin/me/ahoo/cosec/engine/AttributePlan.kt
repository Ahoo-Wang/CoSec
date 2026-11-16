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
 * The external attributes one evaluation may read (RFC 0006 §5.4.2): those of the candidate statements, the
 * index's candidates intersected with the applicable bindings, that pass their action and index keys, and of their
 * policies' conditions; of the rate limit rules; and of the boundary that holds the request's tenant, its statements'
 * and its condition (RFC 0007 §7.6, #1208). Evaluation reads no external attribute outside it (P14, `ATR-8`); one it
 * would read anyway is missing.
 */
@InternalCoSecApi
class AttributePlan internal constructor(val attributes: List<Attribute>) {
    val isEmpty: Boolean get() = attributes.isEmpty()

    override fun toString(): String = "AttributePlan$attributes"

    @InternalCoSecApi
    companion object {
        val EMPTY: AttributePlan = AttributePlan(emptyList())
    }
}
