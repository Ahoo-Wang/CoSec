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
import me.ahoo.cosec.annotations.InternalCoSecApi

/**
 * One operation of a change (RFC 0007 §10.2, the wire's `op`). A put or a remove of an entry takes an [ifMatch]: the
 * digest of the entry it was read with (a change's answer writes it), so a change that would overwrite an entry someone
 * else changed since is refused; null puts or removes whatever is there. [PutCatalog] replaces an app's whole catalog,
 * which the change's base guards. An operation this version does not know (the platform document's sections, or one a
 * later host adds) reads as [Other], with its JSON kept; it is not made by an application.
 */
@ExperimentalCoSecApi
sealed class Operation(
    /** The wire's `op`. */
    val op: String,
    val ifMatch: String?,
) {
    internal abstract fun parts(): List<Any?>

    final override fun equals(other: Any?): Boolean =
        other is Operation && other::class == this::class && op == other.op && parts() == other.parts()

    final override fun hashCode(): Int = listOf(op, parts()).hashCode()

    /** `putPolicy`: Puts [policy], replacing the one of its id. */
    @ExperimentalCoSecApi
    class PutPolicy(val policy: Policy, ifMatch: String? = null) : Operation("putPolicy", ifMatch) {
        override fun parts(): List<Any?> = listOf(policy, ifMatch)

        override fun toString(): String = describe("PutPolicy", "policy" to policy, "ifMatch" to ifMatch)
    }

    /** `removePolicy`: Removes the policy [id]. */
    @ExperimentalCoSecApi
    class RemovePolicy(val id: String, ifMatch: String? = null) : Operation("removePolicy", ifMatch) {
        override fun parts(): List<Any?> = listOf(id, ifMatch)

        override fun toString(): String = describe("RemovePolicy", "id" to id, "ifMatch" to ifMatch)
    }

    /** `putBinding`: Puts [binding], replacing the one of its id. */
    @ExperimentalCoSecApi
    class PutBinding(val binding: Binding, ifMatch: String? = null) : Operation("putBinding", ifMatch) {
        override fun parts(): List<Any?> = listOf(binding, ifMatch)

        override fun toString(): String = describe("PutBinding", "binding" to binding, "ifMatch" to ifMatch)
    }

    /** `removeBinding`: Removes the binding [id]. */
    @ExperimentalCoSecApi
    class RemoveBinding(val id: String, ifMatch: String? = null) : Operation("removeBinding", ifMatch) {
        override fun parts(): List<Any?> = listOf(id, ifMatch)

        override fun toString(): String = describe("RemoveBinding", "id" to id, "ifMatch" to ifMatch)
    }

    /** `putRole`: Puts [role], replacing the one of its id. */
    @ExperimentalCoSecApi
    class PutRole(val role: Role, ifMatch: String? = null) : Operation("putRole", ifMatch) {
        override fun parts(): List<Any?> = listOf(role, ifMatch)

        override fun toString(): String = describe("PutRole", "role" to role, "ifMatch" to ifMatch)
    }

    /** `removeRole`: Removes the role [id]. */
    @ExperimentalCoSecApi
    class RemoveRole(val id: String, ifMatch: String? = null) : Operation("removeRole", ifMatch) {
        override fun parts(): List<Any?> = listOf(id, ifMatch)

        override fun toString(): String = describe("RemoveRole", "id" to id, "ifMatch" to ifMatch)
    }

    /** `putRateLimit`: Puts [rule], replacing the one of its id. */
    @ExperimentalCoSecApi
    class PutRateLimit(val rule: RateLimitRule, ifMatch: String? = null) : Operation("putRateLimit", ifMatch) {
        override fun parts(): List<Any?> = listOf(rule, ifMatch)

        override fun toString(): String = describe("PutRateLimit", "rule" to rule, "ifMatch" to ifMatch)
    }

    /** `removeRateLimit`: Removes the rate limit rule [id]. */
    @ExperimentalCoSecApi
    class RemoveRateLimit(val id: String, ifMatch: String? = null) : Operation("removeRateLimit", ifMatch) {
        override fun parts(): List<Any?> = listOf(id, ifMatch)

        override fun toString(): String = describe("RemoveRateLimit", "id" to id, "ifMatch" to ifMatch)
    }

    /** `putCatalog`: replaces the app's catalog with [catalog]; null removes it. */
    @ExperimentalCoSecApi
    class PutCatalog(val catalog: Catalog?) : Operation("putCatalog", null) {
        override fun parts(): List<Any?> = listOf(catalog)

        override fun toString(): String = describe("PutCatalog", "catalog" to catalog)
    }

    /** An operation this version does not type, as the control plane wrote it: [json]. */
    @ExperimentalCoSecApi
    class Other @InternalCoSecApi constructor(op: String, val json: String) : Operation(op, null) {
        override fun parts(): List<Any?> = listOf(json)

        override fun toString(): String = json
    }
}
