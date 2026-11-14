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

/** [document] and where each entry it derived was written; the compiler and the checks report errors there. */
@InternalCoSecApi
class Expansion internal constructor(
    val document: PolicyDocument,
    private val ownPolicies: Int,
    private val policySources: List<String>,
    private val ownBindings: Int,
    private val bindingSources: List<String>,
) {
    /** Whether the policy at [index] of [document] is derived from a catalog entry. */
    fun derivedPolicy(index: Int): Boolean = index >= ownPolicies

    /** The JSON path the policy at [index] of [document] was written at. */
    fun policyPath(index: Int): String =
        if (derivedPolicy(index)) policySources[index - ownPolicies] else "$.policies[$index]"

    /** The JSON path the binding at [index] of [document] was written at. */
    fun bindingPath(index: Int): String =
        if (index >= ownBindings) bindingSources[index - ownBindings] else "$.bindings[$index]"
}
