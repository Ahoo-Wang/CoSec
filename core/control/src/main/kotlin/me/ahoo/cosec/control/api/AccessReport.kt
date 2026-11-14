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

@file:OptIn(ExperimentalCoSecApi::class, InternalCoSecApi::class)

package me.ahoo.cosec.control.api

import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.model.Action
import me.ahoo.cosec.model.Resource
import me.ahoo.cosec.model.TenantId

/** Which bindings could permit, and which DENYs could deny, [action] on [resource] in a scope of [tenant] (§10.3). */
@InternalCoSecApi
data class AccessQuery(
    val tenant: TenantId,
    val app: String?,
    val space: String?,
    val action: Action,
    val resource: Resource
)

/** A binding whose policy has a statement matching the query; its condition, if any, is not evaluated. */
@InternalCoSecApi
data class AccessEntry(
    val document: String,
    val binding: String,
    val subject: String,
    val app: String,
    val space: String,
    val policy: String,
    val statement: Int,
    val conditional: Boolean,
)

/**
 * [allows] and [denies] from the documents the caller may read. [hidden] counts the documents it may not read, and
 * [opaque] the bindings of readable documents whose policy is in one of those: their content is not shown, nor whether
 * they match.
 */
@InternalCoSecApi
data class AccessReport(val allows: List<AccessEntry>, val denies: List<AccessEntry>, val hidden: Int, val opaque: Int)
