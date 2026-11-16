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

/** The policy [policyId] does not compile. It is never installed (RFC 0003 F4), so it never decides anything. */
@InternalCoSecApi
class CompileException(val policyId: String, val errors: List<CompileError>) :
    IllegalArgumentException(
        "Policy [$policyId] does not compile: " + errors.joinToString("; ") { "${it.path}: ${it.message}" }
    )
