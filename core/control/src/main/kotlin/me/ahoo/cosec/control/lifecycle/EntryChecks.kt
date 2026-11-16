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

@file:OptIn(me.ahoo.cosec.annotations.InternalCoSecApi::class, ExperimentalCoSecApi::class)

package me.ahoo.cosec.control.lifecycle

import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.change.Finding
import me.ahoo.cosec.control.change.Operation
import me.ahoo.cosec.document.PolicyDocument
import me.ahoo.cosec.document.PolicyDocumentCompiler
import me.ahoo.cosec.document.ProvenanceKind
import me.ahoo.cosec.model.Identifiers
import me.ahoo.cosec.model.Match
import me.ahoo.cosec.model.scope.ScopeResolver

/** The names and ids a candidate and its change use (V2, RFC 0007 §6.2, §7.1, §3.1 N2). */
internal object EntryChecks {
    private const val BAD_REQUEST = 400
    private const val UNPROCESSABLE = 422

    /**
     * A tenant policy takes a platform policy's id only as an exact copy of it (RFC 0007 N2, §9.4, #1224): that is the
     * first step of the partition flow, where both layers hold the same content and resolve it alike. Any other policy
     * of that id would be ambiguous (the engine fails closed on it), and the copy cannot be edited while the platform's
     * stands, since the edit would no longer be a copy. Removing the copy, or the platform's, is not checked here.
     */
    fun sharesPlatformIds(candidate: PolicyDocument, platform: PolicyDocument, findings: MutableList<Finding>) {
        val shared = platform.policies.associateBy { it.id }
        candidate.policies.withIndex().forEach { (i, policy) ->
            val original = shared[policy.id] ?: return@forEach
            if (EntryDigest.of(policy) != EntryDigest.of(original)) {
                findings += Finding(
                    "V2",
                    "$.policies[$i].id",
                    "[${policy.id.value}] is a platform policy's id, and this policy is not an exact copy of it",
                    UNPROCESSABLE,
                )
            }
        }
    }

    /**
     * No change uses a reserved prefix: cosec-migrate writes its reserved ids through Revisions, never through a change,
     * so a change's kind exempts nothing (review K1).
     */
    @Suppress("UnusedParameter")
    fun reservedNames(operations: List<Operation>, kind: ProvenanceKind, findings: MutableList<Finding>) {
        for ((i, operation) in operations.withIndex()) {
            val id = when (operation) {
                is Operation.PutPolicy -> operation.policy.id.value
                is Operation.PutBinding -> operation.binding.id.value
                else -> continue
            }
            ChangeChecks.RESERVED.firstOrNull { id.startsWith(it) }?.let { prefix ->
                findings += Finding("V2", "operations[$i]", "[$id]: the prefix [$prefix] is reserved for built-in policies, cosec-migrate and role expansion", UNPROCESSABLE)
            }
        }
    }

    /**
     * An entry's id is one segment of the resource `cosec:write` is decided on (RFC 0007 §7.1): an id with a `/` adds
     * segments, and so walks out of a rule whose last segment is a `*`. Only the ids a change puts: one a document
     * already holds is not this change's to rename, and removing it stays possible. A permission policy's id is
     * `perm:<app>/<id>` by definition, the one exception, which a rule reaches with `**`.
     */
    fun entryIds(operations: List<Operation>, findings: MutableList<Finding>) {
        for ((i, operation) in operations.withIndex()) {
            val id = when (operation) {
                is Operation.PutPolicy -> operation.policy.id.value.takeUnless {
                    it.startsWith(PolicyDocumentCompiler.PERMISSION_PREFIX)
                }
                is Operation.PutBinding -> operation.binding.id.value
                is Operation.PutRole -> operation.role.id.value
                is Operation.PutRateLimit -> operation.rule.id
                else -> null
            } ?: continue
            if ('/' in id) {
                findings += Finding("V2", "operations[$i]", "[${printable(id)}]: an entry's id has no [/]", UNPROCESSABLE)
            }
        }
    }

    /**
     * Every app the candidate names is an app id (SCP-10): the document format takes any id but `*` in a binding's
     * `app`, but no request is ever in an app the rule refuses, and no key names its document. The whole candidate is
     * checked, its head's entries included.
     */
    fun appIds(candidate: PolicyDocument, findings: MutableList<Finding>) {
        // The bindings as written: a role's app is the compiler's to check, at the path the role writes it.
        for ((i, binding) in candidate.bindings.withIndex()) {
            val app = (binding.scope.app as? Match.Exactly)?.value?.value ?: continue
            if (!ScopeResolver.isValidAppId(app)) {
                findings += Finding(
                    "V2",
                    "$.bindings[$i].app",
                    "binding [${printable(binding.id.value)}] is in app [${printable(app)}], which cannot be an app id " +
                        "(blank, *, ':', a control character or an unpaired surrogate, or over " +
                        "${ScopeResolver.MAX_APP_ID_LENGTH} characters): " +
                        "no request is ever in it. Remove the binding.",
                    BAD_REQUEST,
                )
            }
        }
    }

    /** [text] for a finding: a control character or an unpaired surrogate as its `\uXXXX` escape. */
    private fun printable(text: String): String = Identifiers.printable(text)
}
