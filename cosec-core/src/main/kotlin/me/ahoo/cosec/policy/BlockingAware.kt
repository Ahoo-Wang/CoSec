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
package me.ahoo.cosec.policy

import me.ahoo.cosec.api.permission.AppPermission
import me.ahoo.cosec.api.policy.ConditionMatcher
import me.ahoo.cosec.api.policy.Policy

/**
 * Declares whether evaluating a matcher (or a policy, or an app permission) performs blocking I/O.
 *
 * Matchers are evaluated synchronously. When the repositories answer from the local cache, that happens on
 * the caller's thread, which for WebFlux and Gateway is the Netty event loop. `SimpleAuthorization` moves the
 * evaluation of a layer to `Schedulers.boundedElastic()` only when that layer contains a blocking matcher, so
 * policies without one keep the zero-hop hot path.
 *
 * A [ConditionMatcher] that blocks (e.g. a Redis round trip) implements this with [requiresBlocking] `= true`.
 * A composite matcher implements it by aggregating its children, so the flag propagates through nesting.
 * Matchers that do not implement it are treated as non-blocking.
 */
interface BlockingAware {
    val requiresBlocking: Boolean
}

val ConditionMatcher.requiresBlocking: Boolean
    get() = this is BlockingAware && requiresBlocking

/**
 * Whether verifying this policy may block. [PolicyData] computes this once at construction; other
 * implementations are inspected on every call.
 */
val Policy.requiresBlocking: Boolean
    get() = if (this is BlockingAware) {
        requiresBlocking
    } else {
        computeRequiresBlocking(condition, statements.map { it.condition })
    }

/**
 * Whether verifying any permission of this app may block. Conservative: one blocking permission makes the
 * whole app hop, regardless of which roles the principal holds.
 */
val AppPermission.requiresBlocking: Boolean
    get() = if (this is BlockingAware) {
        requiresBlocking
    } else {
        computeRequiresBlocking(condition, groups.flatMap { group -> group.permissions.map { it.condition } })
    }

/** Action matchers are pure path/method matching and never block, so only conditions are inspected. */
internal fun computeRequiresBlocking(condition: ConditionMatcher, conditions: List<ConditionMatcher>): Boolean =
    condition.requiresBlocking || conditions.any { it.requiresBlocking }
