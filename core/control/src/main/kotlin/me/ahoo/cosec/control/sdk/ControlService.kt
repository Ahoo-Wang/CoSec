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
import me.ahoo.cosec.control.change.ChangeState
import me.ahoo.cosec.spi.snapshot.SnapshotKey

/**
 * The control plane as a service calls it (`CTL-65`): the narrow first set of the control API (reads of a tenant's or an
 * app's document, and the change flow of a tenant's document, its shadow window included), with the bodies and answers of its routes. A request is
 * the JSON a route takes (`POST /changes`, `:apply`, `:publish`), a document the document format
 * (`schema/8.0/cosec-snapshot-document.schema.json`), and an answer the JSON the route answers, pinned by `contract/control/`.
 * Nothing here is about the platform document, boundaries, approvals, rollback or break-glass: those stay a
 * person's, on the operators' routes.
 *
 * The same calls, the same bodies and the same answers whichever implementation serves them, so an application moves
 * between embedding the control plane and calling a control-plane host by changing the implementation alone. Every call
 * is decided for the principal its [ControlCaller] states, by the policies, and recorded as that principal's step; a
 * refusal is a [ControlRefusedException] with the route's problem.
 */
@ExperimentalCoSecApi
interface ControlService {
    /** `GET /documents/{key}`: the head of a tenant's or an app's document, in the document format. */
    suspend fun document(caller: ControlCaller, key: SnapshotKey): String

    /** `GET /documents/{key}/revisions?before=&limit=`: the document's revisions, newest first. */
    suspend fun revisions(caller: ControlCaller, key: SnapshotKey, before: Long? = null, limit: Int? = null): String

    /** `GET /documents/{key}/revisions/{revision}`: one kept revision, in the document format. */
    suspend fun revision(caller: ControlCaller, key: SnapshotKey, revision: Long): String

    /** `GET /documents/{key}/diff?from=&to=`: what changed between two revisions. */
    suspend fun diff(caller: ControlCaller, key: SnapshotKey, from: Long, to: Long): String

    /** `POST /documents/{key}:apply` of a tenant's document: [request] is the route's body; answers the change. */
    suspend fun apply(caller: ControlCaller, key: SnapshotKey, request: String): String

    /** `POST /changes` of a tenant's document: [request] is the route's body; answers the change. */
    suspend fun propose(caller: ControlCaller, request: String): String

    /** `GET /changes/{id}`. */
    suspend fun change(caller: ControlCaller, id: String): String

    /** `GET /changes?document=&state=&after=&limit=`: the open changes of a tenant's or an app's document. */
    suspend fun changes(
        caller: ControlCaller,
        document: SnapshotKey,
        state: ChangeState? = null,
        after: String? = null,
        limit: Int? = null,
    ): String

    /** `POST /changes/{id}:rebase` of a change of a tenant's document. */
    suspend fun rebase(caller: ControlCaller, id: String): String

    /**
     * `POST /changes/{id}:shadow` of a change of a tenant's document: starts its shadow window. Read the change until the
     * window closes, then [publish] it, acknowledging the summary's counts when the window saw differences.
     */
    suspend fun startShadow(caller: ControlCaller, id: String): String

    /** `POST /changes/{id}:skip-shadow` of a change of a tenant's document, for [reason]. */
    suspend fun skipShadow(caller: ControlCaller, id: String, reason: String): String

    /** `POST /changes/{id}:abandon` of a change of a tenant's document. */
    suspend fun abandon(caller: ControlCaller, id: String): String

    /** `POST /changes/{id}:publish` of a change of a tenant's document; [request] is the route's body (`ack`), if any. */
    suspend fun publish(caller: ControlCaller, id: String, request: String? = null): String
}
