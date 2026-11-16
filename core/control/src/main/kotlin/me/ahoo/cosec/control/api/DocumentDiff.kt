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

/**
 * What changed between two revisions, entry by entry (RFC 0007 §10.1): the history of one policy is a view of it.
 * [policies] and [bindings] are of the expanded documents (RFC 0012 §7.4), so a role's change, and what a catalog that
 * grows gives a `"*"` role, shows as the bindings it derives; [roles] are the roles as written.
 */
@InternalCoSecApi
data class DocumentDiff(
    val from: Long,
    val to: Long,
    val policies: EntryChanges,
    val bindings: EntryChanges,
    val roles: EntryChanges,
    /**
     * Whether anything besides the policies, bindings and roles differs: the catalog, the limits, the attributes, the boundary
     * or the rate limits, compared by their whole content (#1205).
     */
    val sections: Boolean,
)

@InternalCoSecApi
data class EntryChanges(val added: List<String>, val removed: List<String>, val changed: List<String>)
