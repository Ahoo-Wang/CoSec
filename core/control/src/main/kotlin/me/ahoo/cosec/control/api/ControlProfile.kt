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

package me.ahoo.cosec.control.api

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.api.ControlHttpSupport.verbOf
import me.ahoo.cosec.control.change.InvalidControlRequestException
import me.ahoo.cosec.spi.snapshot.SnapshotKey

/**
 * Which of the control API's routes a listener serves (`CTL-58`). [OPERATORS] serves all of them: the port the gateway
 * routes to, for people. [SERVICE] is the narrow set a service that calls the control plane directly may ask for, on a
 * port of its own, so that the network, not a filter, says who may ask for what: the tenant and app documents (their
 * reads, and `:apply` on a tenant's), and the change flow that needs no person (propose, read, rebase, abandon, run or
 * skip the shadow window, publish). Whatever is outside it is a `404`, as if it did not exist, before the control plane is
 * asked: the platform document, approvals and rejections, rollback, break-glass, and the queries
 * (`simulate`, `explain`, `analyze`, propagation) are people's, or the platform's.
 *
 * The profile narrows what is served; it never widens what the caller may do. Every route it serves is authorized for
 * the principal the caller states, by the policies, as on the operators' port.
 */
@InternalCoSecApi
enum class ControlProfile {
    OPERATORS,
    SERVICE,
    ;

    /** Whether a request of [method] to the path [segments] (its first segment the resource) is served at all. */
    internal fun admits(method: String, segments: List<String>): Boolean {
        if (this == OPERATORS) {
            return true
        }
        return when (segments.first()) {
            "documents" -> documents(method, segments.drop(1))
            "changes" -> changes(method, segments.drop(1))
            else -> false
        }
    }

    /** Whether this profile serves a write to [key]: a service writes a tenant's document, never the platform's or an app's. */
    internal fun admitsWrite(key: SnapshotKey): Boolean = this == OPERATORS || key is SnapshotKey.Tenant

    /**
     * Whether this profile serves a route of a change of [key], read or step (`CTL-58`, `CTL-65`): on the service
     * profile, only a tenant document's change. A change named by its id is checked here, once its record is read, so a
     * change of the platform document is no more reachable by its id than by its document.
     */
    internal fun admitsChange(key: SnapshotKey): Boolean = this == OPERATORS || key is SnapshotKey.Tenant

    /**
     * Whether this profile serves a listing of the open changes of [document] (of a state, across documents, when null):
     * on the service profile only a tenant's document, as it serves a change by id; a listing by state shows only the
     * changes it serves by id ([admitsChange]), the others left out before the page is cut.
     */
    internal fun admitsListing(document: SnapshotKey?): Boolean =
        this == OPERATORS || document == null || document is SnapshotKey.Tenant

    private fun documents(method: String, rest: List<String>): Boolean {
        // `GET /documents?kind=`: which tenant and app documents there are (`CTL-68`).
        val first = rest.firstOrNull() ?: return method == "GET"
        val (keyText, verb) = verbOf(first)
        val key = try {
            ControlRequestJson.documentKey(unescaped(keyText))
        } catch (@Suppress("SwallowedException") e: InvalidControlRequestException) {
            // Not a document the route can name: the route answers its own 400 for it, as on the operators' port.
            return true
        }
        if (key == SnapshotKey.Platform) {
            return false
        }
        return when (verb) {
            null -> method == "GET" && reads(rest)
            "apply" -> method == "POST" && rest.size == 1 && key is SnapshotKey.Tenant
            else -> false
        }
    }

    /** The reads of a document the service profile serves: its head, its history, a revision and a diff; not propagation. */
    private fun reads(rest: List<String>): Boolean = rest.size == 1 ||
        (rest.size == 2 && rest[1] in setOf("diff", "revisions")) ||
        (rest.size == 3 && rest[1] == "revisions")

    private fun changes(method: String, rest: List<String>): Boolean {
        if (rest.isEmpty()) {
            return method == "GET" || method == "POST"
        }
        if (rest.size != 1) {
            return false
        }
        val (_, verb) = verbOf(rest.single())
        return when (verb) {
            null -> method == "GET"
            "rebase", "shadow", "abandon", "skip-shadow", "publish" -> method == "POST"
            else -> false
        }
    }
}
