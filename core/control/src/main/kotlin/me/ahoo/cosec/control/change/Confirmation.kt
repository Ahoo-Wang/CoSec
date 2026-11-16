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

@file:OptIn(InternalCoSecApi::class, ExperimentalCoSecApi::class)

package me.ahoo.cosec.control.change

import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.model.PolicyId

/**
 * A confirmation an author gives with a change (RFC 0007 §9.5): that statement [statement] of [policy], whose content
 * has [digest] ([StatementDigest]), may do what [kind] says. Only `deny-nomatch` exists: a DENY with
 * `onMissing: nomatch`, which lapses while its attributes cannot be fetched.
 */
class Confirmation(val policy: PolicyId, val statement: Int, val digest: String, val kind: String = DENY_NOMATCH) {
    init {
        requireRequest(
            statement >= 0 && kind == DENY_NOMATCH
        ) { "A confirmation is deny-nomatch, of a statement index." }
    }

    // A plain class, not a `data class`: a `ChangeEvent` carries it, so it is stable, and a field it gains is a new
    // constructor (RFC 0010 C4). It compares by value: a change's confirmations are matched against its statements.
    override fun equals(other: Any?): Boolean =
        this === other || other is Confirmation && policy == other.policy && statement == other.statement &&
            digest == other.digest && kind == other.kind

    override fun hashCode(): Int = ((policy.hashCode() * 31 + statement) * 31 + digest.hashCode()) * 31 + kind.hashCode()

    override fun toString(): String = "Confirmation(policy=$policy, statement=$statement, digest=$digest, kind=$kind)"

    companion object {
        const val DENY_NOMATCH: String = "deny-nomatch"
    }
}
