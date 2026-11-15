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

package me.ahoo.cosec.runtime.stage

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.engine.flat.SnapshotView
import me.ahoo.cosec.model.decision.Cause
import me.ahoo.cosec.model.decision.DocumentKind
import me.ahoo.cosec.runtime.attribute.BlacklistAttributeProvider
import me.ahoo.cosec.runtime.attribute.FetchedAttributes
import me.ahoo.cosec.runtime.call.Answer
import me.ahoo.cosec.runtime.call.callPort
import me.ahoo.cosec.runtime.call.valueOr
import me.ahoo.cosec.runtime.http.HttpAttributes
import me.ahoo.cosec.runtime.http.Outcome
import me.ahoo.cosec.runtime.http.StatusMapping
import me.ahoo.cosec.runtime.snapshot.SuspendingSnapshotSource

/**
 * Stage 5: the documents the request is decided on, for its scope: the platform document only when the blacklist
 * blocks the request. An unreadable document is a 503 (PIP-4). A blacklist that could not be read is never "not
 * blocked": the evaluation is then indeterminate unless a DENY matches, as for a document that could not be read
 * (RFC 0006 §5.4.4). A header a document reads, sent with different values, has no value to decide on: 400 (PIP-12).
 */
internal class LoadView(private val snapshots: SuspendingSnapshotSource) : Stage {
    override suspend fun run(state: RequestState): Outcome? {
        val blacklist = blacklist(state.fetched)
        state.blacklist = blacklist
        state.view = view(state, blacklist).valueOr {
            return state.unavailable(Cause.SourceUnavailable(DocumentKind.PLATFORM, it))
        }
        // RC1-38 (PIP-12): refused for every such header, whichever statement reads it, so the answer does not tell a
        // DENY from an ALLOW.
        if (HttpAttributes.ambiguousHeader(state.facts.headers, state.view) != null) {
            return StatusMapping.invalidRequest(state.requestId, "header", state.traceId)
        }
        return null
    }

    private suspend fun view(state: RequestState, blacklist: Blacklist): Answer<SnapshotView> {
        val loaded = callPort(null) { snapshots.view(state.scope, blacklist.blocked) }
        val unavailable = blacklist.unavailable ?: return loaded
        return when (loaded) {
            is Answer.Value -> Answer.Value(loaded.value.withUnresolved(unavailable))
            is Answer.Failed -> loaded
        }
    }

    /** Blocked when any blacklist attribute says so; else unavailable when any of them could not be read. */
    private fun blacklist(fetched: FetchedAttributes): Blacklist {
        if (fetched === FetchedAttributes.NONE) {
            return Blacklist.NOT_BLOCKED
        }
        var unavailable: MutableSet<String>? = null
        for (path in BlacklistAttributeProvider.ATTRIBUTES) {
            if (!fetched.asked(path)) {
                continue
            }
            if (!fetched.available(path)) {
                (unavailable ?: LinkedHashSet<String>().also { unavailable = it }) += path
            } else if (fetched.value(path) == true) {
                return Blacklist.BLOCKED
            }
        }
        return unavailable?.let { Blacklist(false, Cause.AttributeUnavailable(it, null)) } ?: Blacklist.NOT_BLOCKED
    }

    private fun SnapshotView.withUnresolved(cause: Cause): SnapshotView =
        SnapshotView(platform, app, tenant, unresolved + cause, version)
}
