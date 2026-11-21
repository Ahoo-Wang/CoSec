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

package me.ahoo.cosec.control.sdk

import me.ahoo.cosec.annotations.ExperimentalCoSecApi

/**
 * One statement of a [Policy] (the document format's `statement`): what it does when it matches ([effect]), the actions
 * and resources it matches, and the [condition] it holds to. An empty [resources] matches every resource the actions
 * act on (a statement written without `resource`). [onMissing] says what an external attribute the condition reads,
 * that cannot be fetched, means; null is the format's default. [onMissingConfirmed] is the control plane's: who
 * confirmed a DENY that reads `nomatch`, and in which change (RFC 0007 §9.5); it is kept as read, so a document read
 * and applied back says the same.
 */
@ExperimentalCoSecApi
class Statement(
    val effect: Effect,
    val actions: List<String>,
    val resources: List<Resource> = emptyList(),
    val condition: Condition? = null,
    val name: String? = null,
    val onMissing: OnMissing? = null,
    val onMissingConfirmed: OnMissingConfirmation? = null,
) {
    init {
        require(actions.isNotEmpty()) { "A statement names at least one action." }
    }

    override fun equals(other: Any?): Boolean = other is Statement && parts() == other.parts()

    override fun hashCode(): Int = parts().hashCode()

    override fun toString(): String = describe(
        "Statement",
        "effect" to effect,
        "actions" to actions,
        "resources" to resources,
        "condition" to condition,
        "name" to name,
        "onMissing" to onMissing,
        "onMissingConfirmed" to onMissingConfirmed,
    )

    private fun parts(): List<Any?> = listOf(effect, actions, resources, condition, name, onMissing, onMissingConfirmed)
}

/** Who confirmed a statement's `nomatch` (the document format's `onMissingConfirmed`): [by], in change [change]. */
@ExperimentalCoSecApi
class OnMissingConfirmation(val by: Actor, val change: String) {
    override fun equals(other: Any?): Boolean =
        other is OnMissingConfirmation && by == other.by && change == other.change

    override fun hashCode(): Int = listOf(by, change).hashCode()

    override fun toString(): String = describe("OnMissingConfirmation", "by" to by, "change" to change)
}
