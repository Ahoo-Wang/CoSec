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

package me.ahoo.cosec.control.grant

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.model.Binding

/** The entries a change touches that need `cosec:unprotect` (RFC 0007 §7.4.4, Q9). */
internal class ProtectedEntries(private val equality: GrantEquality) {
    /** The entries of [before] marked `protected` that [after] changes or removes, or unmarks. */
    fun unprotect(before: GrantDocument, after: GrantDocument): List<ProtectedEntry> {
        val entries = ArrayList<ProtectedEntry>()
        for (policy in before.policies) {
            if (policy.protected && after.policy(policy.id)?.let { equality.samePolicy(policy, it) } != true) {
                entries += ProtectedEntry.Policy(policy.id)
            }
        }
        for (binding in before.bindings) {
            if (binding.id !in before.protectedBindings) continue
            val kept = after.bindings.any { GrantEquality.same(it, binding) } && binding.id in after.protectedBindings
            if (!kept) entries += ProtectedEntry.Binding(binding.id)
        }
        return entries
    }
}
