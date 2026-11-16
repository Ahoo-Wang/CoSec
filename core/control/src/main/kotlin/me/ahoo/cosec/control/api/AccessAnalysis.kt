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
import me.ahoo.cosec.control.authz.ControlActions
import me.ahoo.cosec.control.authz.Operator
import me.ahoo.cosec.control.change.ChangeRejectedException
import me.ahoo.cosec.control.lifecycle.ChangeAuthority
import me.ahoo.cosec.engine.pattern.ResourceTarget
import me.ahoo.cosec.model.Action
import me.ahoo.cosec.model.Binding
import me.ahoo.cosec.model.Effect
import me.ahoo.cosec.model.Match
import me.ahoo.cosec.model.Resource
import me.ahoo.cosec.model.appliesIn
import me.ahoo.cosec.runtime.http.Enforceable
import me.ahoo.cosec.runtime.http.PathNormalizer
import me.ahoo.cosec.spi.snapshot.SnapshotKey

/** Who may do what to a resource, binding by binding, in the documents the caller may read. */
internal class AccessAnalysis(
    private val authority: ChangeAuthority,
    private val retained: RetainedDocuments,
) {
    suspend fun analyze(operator: Operator, query: AccessQuery): AccessReport {
        val tenant = SnapshotKey.Tenant(query.tenant.value)
        authority.require(operator, tenant, ControlActions.ANALYZE)
        val resource = enforceable(query.resource)
        // The query's action as enforcement reads it (PIP-18): who may HEAD is who may GET.
        val asked = query.copy(action = enforceable(query.action))
        val app = queriedApp(query)
        val keys = listOfNotNull(SnapshotKey.Platform, tenant, app)
        val readable = keys.filter { it == tenant || authority.holds(operator, it, ControlActions.READ) }
        val platform = SnapshotKey.Platform.takeIf { it in readable }?.let { retained.entry(it) }
        val application = app?.takeIf { it in readable }?.let { retained.entry(it) }
        val found = AccessFindings(asked, ResourceTarget(resource))
        for (key in readable) {
            // The documents and their parsed statements come from the cache simulate and explain read (RC1-41).
            val entry = when (key) {
                SnapshotKey.Platform -> platform
                app -> application
                else -> retained.entry(key)
            } ?: continue
            for (binding in entry.effective.bindings) {
                if (!applies(binding, asked)) {
                    continue
                }
                found.add(key, binding, analyzed(binding, entry, platform, application))
            }
        }
        return AccessReport(found.allows, found.denies, keys.size - readable.size, found.opaque)
    }

    /** The app document [query] names, if any; one no key can name is refused (SCP-10), not echoed: it may hold control characters. */
    private fun queriedApp(query: AccessQuery): SnapshotKey.App? = query.app?.takeIf { it.isNotEmpty() }?.let { app ->
        if (!SnapshotKey.App.isValidAppId(app)) {
            throw ChangeRejectedException(BAD_REQUEST, "The query's app cannot name an app document (SCP-10).")
        }
        SnapshotKey.App(app)
    }

    /** The policy [binding] of [entry] names, as the engine resolves it: a built-in one, its own document, the platform, the app it is scoped to. */
    private fun analyzed(
        binding: Binding,
        entry: DocumentCache.Entry,
        platform: DocumentCache.Entry?,
        app: DocumentCache.Entry?,
    ): DocumentCache.AnalyzedPolicy? = DocumentCache.BUILTIN[binding.policy.value] ?: entry.analyzed[binding.policy.value]
        ?: platform?.analyzed?.get(binding.policy.value)
        ?: app?.takeIf { binding.scope.app.exact { id -> id.value } == it.document.owner }?.analyzed?.get(binding.policy.value)

    /** What [analyze] finds, binding by binding: a policy that is not readable counts as opaque. */
    private inner class AccessFindings(private val query: AccessQuery, private val target: ResourceTarget) {
        val allows = ArrayList<AccessEntry>()
        val denies = ArrayList<AccessEntry>()
        var opaque = 0

        fun add(key: SnapshotKey, binding: Binding, policy: DocumentCache.AnalyzedPolicy?) {
            if (policy == null) {
                opaque++
                return
            }
            for (statement in policy.statements) {
                if (!statement.matches(query.action, target)) continue
                val entry = AccessEntry.of(
                    key,
                    binding,
                    policy.definition,
                    statement.index,
                    statement.source.condition != null
                )
                if (statement.source.effect == Effect.DENY) denies += entry else allows += entry
            }
        }
    }

    /**
     * Whether [binding] can apply to what [query] asks about: in its tenant, as the engine looks a subject up (#1206), and
     * in its app and space.
     */
    private fun applies(binding: Binding, query: AccessQuery): Boolean =
        binding.subject.appliesIn(query.tenant) &&
            inScope(binding.scope.app.exact { it.value }, query.app) &&
            inScope(binding.scope.space.exact { it.value }, query.space)

    /** A binding of [exact] (null: every value) applies to a query of [value] (null: any). */
    private fun inScope(exact: String?, value: String?): Boolean = exact == null || value == null || exact == value

    private fun <T> Match<T>.exact(text: (T) -> String): String? = (this as? Match.Exactly<T>)?.value?.let(text)

    /** [action] as enforcement would decide it (`PIP-18`): [Enforceable]. */
    private fun enforceable(action: Action): Action = Enforceable.action(action)
        ?: throw ChangeRejectedException(
            BAD_REQUEST,
            "The action is not one enforcement decides: an http method with a lower-case letter is refused as a " +
                "request's is; http:HEAD is decided as http:GET."
        )

    /** [resource] as enforcement would decide it (RC1-41, review L5 of #1200): [Enforceable]. */
    private fun enforceable(resource: Resource): Resource = Enforceable.resource(resource)
        ?: throw ChangeRejectedException(
            BAD_REQUEST,
            "The resource is not one enforcement decides: an http path is refused as a request's is (a dot segment, " +
                "an empty segment, an encoded separator, a backslash, a malformed escape or its length), and any " +
                "resource is at most ${PathNormalizer.MAX_PATH_LENGTH} characters, " +
                "${PathNormalizer.MAX_SEGMENT_LENGTH} per segment."
        )

    private companion object {
        const val BAD_REQUEST = 400
    }
}
