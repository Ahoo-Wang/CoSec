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
import me.ahoo.cosec.model.Effect
import me.ahoo.cosec.model.condition.Condition

/** A statement as a document writes it (RFC 0005 §5.1), before it is compiled. */
@InternalCoSecApi
class StatementSource(
    val name: String,
    val effect: Effect,
    /** `service:verb`, `service:*` or `*`; at least one. */
    val actions: List<String>,
    /** Empty for any resource. */
    val resources: List<ResourceSource> = emptyList(),
    val condition: Condition? = null,
    /** What an external attribute the condition reads that cannot be fetched means; null for the default. */
    val onMissing: OnMissing? = null,
    /**
     * Who confirmed that this DENY may lapse while its attributes cannot be fetched (`onMissing: nomatch`, RFC 0007 §9.5,
     * N6): the control plane writes it when it publishes a change carrying the confirmation. Null otherwise.
     */
    val onMissingConfirmed: OnMissingConfirmation? = null,
)
