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

package me.ahoo.cosec.snapshot.document

import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.TenantId

/**
 * Where a published document came from (RFC 0007 §5.3): its parent revision, the change that published it, how, and
 * by whom. The control plane writes it on every publish; the compiler only checks its structure and decides nothing
 * with it, so the same body with another provenance compiles to the same snapshot.
 *
 * ```jsonc
 * "provenance": {
 *   "parent": { "revision": 42, "digest": "9f2c…" },     // absent for the first revision
 *   "change": "0QH8K3…",
 *   "kind": "PUBLISH",
 *   "publishedBy": { "tenant": "acme", "principal": "alice" },
 *   "approvedBy": [ { "tenant": "(platform)", "principal": "ops-1" } ],
 *   "shadow": { "window": "0QH8M1…", "skipped": false }
 * }
 * ```
 */
data class Provenance(
    /** The head this revision replaced; `null` for the first revision of a document. */
    val parent: ParentRevision?,
    /** The id of the change request that published it. */
    val change: String,
    val kind: ProvenanceKind,
    val publishedBy: Actor,
    val approvedBy: List<Actor> = emptyList(),
    /** The shadow window the change went through, or that it skipped one; `null` when none applies. */
    val shadow: ShadowWindow? = null,
) {
    init {
        require(change.isNotEmpty()) { "A provenance names its change." }
    }
}

/**
 * The previous head: its revision and its digest, which is also the address of its revision record (RFC 0007 §5.2).
 * The digest is the store's, written as 40 lowercase hex digits.
 */
data class ParentRevision(val revision: Long, val digest: String) {
    init {
        require(revision > 0) { "A parent revision is positive, was $revision." }
        require(DIGEST.matches(digest)) { "A parent digest is 40 lowercase hex digits, was [$digest]." }
    }

    companion object {
        val DIGEST: Regex = Regex("^[0-9a-f]{40}$")
    }
}

/** How a revision came to be (RFC 0007 §5.3). */
enum class ProvenanceKind {
    PUBLISH,
    ROLLBACK,
    BREAK_GLASS,
    EXPIRE,
    MIGRATE,
    BOOTSTRAP,
}

/** A principal that published or approved a revision. */
data class Actor(val tenant: TenantId, val principal: PrincipalId) {
    init {
        require(
            tenant.value.isNotEmpty() && principal.value.isNotEmpty()
        ) { "An actor names its tenant and principal." }
    }
}

/** The shadow window of the change: its id, or that it was skipped (`cosec:skip-shadow`, RFC 0007 §6.4). */
data class ShadowWindow(val window: String?, val skipped: Boolean) {
    init {
        require(window == null || window.isNotEmpty()) { "A shadow window id is not empty." }
    }
}
