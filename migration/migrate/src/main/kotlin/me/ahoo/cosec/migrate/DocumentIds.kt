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

package me.ahoo.cosec.migrate

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.model.Identifiers
import me.ahoo.cosec.model.Match
import me.ahoo.cosec.model.SubjectRef
import me.ahoo.cosec.runtime.document.PolicyDocument

/**
 * The ids of a converted document that hold an unpaired UTF-16 surrogate (RC1-52, `IDN-10`), as the control plane
 * checks a request's: policy, binding, principal, tenant, role, app, space and permission ids. 7.x JSON can write one as
 * an escape (`"\uD800"`); a Redis key would write it as `?`, naming another id's keys. The document keys themselves are
 * held to the app and tenant id rule where the converter places them.
 */
internal object DocumentIds {
    /** Each id of [document] with an unpaired surrogate, by its JSON path in the document. */
    fun unpaired(document: PolicyDocument): List<Pair<String, String>> {
        val found = ArrayList<Pair<String, String>>(0)
        fun check(id: String, path: String) {
            if (!Identifiers.isWellFormed(id)) found += path to id
        }
        document.policies.forEachIndexed { i, policy -> check(policy.id.value, "$.policies[$i].id") }
        document.bindings.forEachIndexed { i, binding ->
            val at = "$.bindings[$i]"
            check(binding.id.value, "$at.id")
            when (val subject = binding.subject) {
                is SubjectRef.Principal -> {
                    check(subject.tenant.value, "$at.subject.tenant")
                    check(subject.id.value, "$at.subject.principal")
                }
                is SubjectRef.Role -> {
                    subject.tenant?.let { check(it.value, "$at.subject.tenant") }
                    check(subject.id.value, "$at.subject.role")
                }
                SubjectRef.Anyone -> Unit
            }
            (binding.scope.app as? Match.Exactly)?.let { check(it.value.value, "$at.app") }
            (binding.scope.space as? Match.Exactly)?.let { check(it.value.value, "$at.space") }
            check(binding.policy.value, "$at.policy")
        }
        document.catalog?.groups?.forEachIndexed { g, group ->
            group.permissions.forEachIndexed { i, permission ->
                check(permission.id, "$.catalog.groups[$g].permissions[$i].id")
            }
        }
        return found
    }

    /** Why the conversion refuses [id], with the surrogate escaped. */
    fun unpairedMessage(id: String): String =
        "the id [${Identifiers.printable(id)}] holds an unpaired UTF-16 surrogate, which a Redis key writes as `?`: it " +
            "would name another id's keys"
}
