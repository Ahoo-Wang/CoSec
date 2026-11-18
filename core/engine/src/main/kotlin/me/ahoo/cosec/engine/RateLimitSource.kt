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
import me.ahoo.cosec.model.decision.RateLimitSpec

/**
 * A rate limit rule as a document writes it (RFC 0006 §8.1): the requests it limits, as one statement (its action,
 * resource and condition; its effect is irrelevant), the attribute that splits it into buckets, and its quota.
 */
@InternalCoSecApi
class RateLimitSource(
    val id: String,
    val statement: StatementSource,
    /** The attribute path whose value names the bucket; null for one bucket shared by every matching request. */
    val key: String?,
    val limit: RateLimitSpec,
    /** Whether a backend that cannot count is a refusal (503) instead of skipping the rule. */
    val strictFailure: Boolean = false,
)
