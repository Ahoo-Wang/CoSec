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
import me.ahoo.cosec.model.decision.DocumentKind
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import java.time.Duration

/**
 * The control plane's narrow set (`CTL-65`) in typed values (`CTL-76`), over either implementation of [ControlService]:
 * the requests are written and the answers read as the control API's routes take and answer them, so the same call
 * answers the same in process and over HTTP. A refusal is the service's [ControlRefusedException]; an answer that does
 * not read (a document entry with a member this version does not know, which re-sending would drop) is a
 * [me.ahoo.cosec.spi.snapshot.SnapshotFormatException].
 */
@ExperimentalCoSecApi
class ControlClient(private val service: ControlService) {
    /** `GET /documents?kind=`: which tenant or app documents there are that [caller] may see, a page at a time. */
    suspend fun documents(
        caller: ControlCaller,
        kind: DocumentKind,
        after: Cursor? = null,
        limit: Int? = null,
    ): Page<DocumentRef> = AnswerJson.documents(service.documents(caller, kind, after?.text, limit))

    /** `GET /documents/{key}`: the head of a tenant's or an app's document. */
    suspend fun document(caller: ControlCaller, key: SnapshotKey): DocumentHead =
        AnswerJson.head(key, service.document(caller, key))

    /** `POST /documents/{key}:apply`: proposes what makes the head of the request's document into its content. */
    suspend fun apply(caller: ControlCaller, request: ApplyRequest): Change =
        AnswerJson.change(service.apply(caller, request.document, OperationJson.request(request)))

    /** `POST /changes`: proposes [request]; the change answers what its check found. */
    suspend fun propose(caller: ControlCaller, request: ChangeRequest): Change =
        AnswerJson.change(service.propose(caller, OperationJson.request(request)))

    /** `GET /changes/{id}`. */
    suspend fun change(caller: ControlCaller, id: String): Change = AnswerJson.change(service.change(caller, id))

    /** `GET /changes?document=&state=`: the open changes [caller] may see, a page at a time. */
    suspend fun changes(
        caller: ControlCaller,
        document: SnapshotKey? = null,
        state: ChangeState? = null,
        after: Cursor? = null,
        limit: Int? = null,
    ): Page<ChangeSummary> = AnswerJson.changes(service.changes(caller, document, state, after?.text, limit))

    /** `POST /changes/{id}:rebase`: the change proposed again on the document's head. */
    suspend fun rebase(caller: ControlCaller, id: String): Change = AnswerJson.change(service.rebase(caller, id))

    /** `POST /changes/{id}:shadow`: starts the change's shadow window. */
    suspend fun startShadow(caller: ControlCaller, id: String): Change =
        AnswerJson.change(service.startShadow(caller, id))

    /** `POST /changes/{id}:skip-shadow`: stages the change without a window, for [reason]; needs `cosec:skip-shadow`. */
    suspend fun skipShadow(caller: ControlCaller, id: String, reason: String): Change =
        AnswerJson.change(service.skipShadow(caller, id, reason))

    /** `POST /changes/{id}:abandon`. */
    suspend fun abandon(caller: ControlCaller, id: String): Change = AnswerJson.change(service.abandon(caller, id))

    /**
     * `POST /changes/{id}:publish`, acknowledging the closed window's differences with [ack] where it saw some
     * ([Shadow.ack]).
     */
    suspend fun publish(caller: ControlCaller, id: String, ack: ShadowAck? = null): Change =
        AnswerJson.change(service.publish(caller, id, OperationJson.publish(ack)))

    /**
     * Proposes [operations] on [document]'s head, skips the shadow window for [skipReason] and publishes: what a service
     * that writes what it already decided (an IAM projecting its events into roles, say) does for each write. [caller]
     * needs `cosec:write` on the document and `cosec:skip-shadow`; without them it is the refusal of the step that
     * needs them.
     *
     * A head that moves before the change publishes (`409` [ConflictKind.BASE_MOVED]) is read again and the change
     * proposed again on it; a slot another change holds ([ConflictKind.SLOT_HELD], [ConflictKind.CONTENDED]) is waited
     * out; both within [patience]. Concurrent writers acting as one principal (an IAM's replicas, say) wait for each
     * other the same way. A writer that died leaves its change holding the slot ([ControlRefusedException.heldBy]): a
     * change of the same author, left `CHECKED`, `SHADOWING` or `READY` and not updated for at least [patience] (by the
     * control plane's times), is abandoned and the slot taken, so a dead writer holds the document for one patience and
     * the next write takes it over. Writers of one principal should use one patience.
     *
     * Answers the published change; or a change that waits for a person (what it grants escalated, `AWAITING_APPROVAL`:
     * it keeps the document's slot until it is approved, rejected or abandoned); or the abandoned change, when its check
     * found what stops it (its findings say what), or when other writes kept taking its place (`CONFLICTED`) until the
     * patience was spent.
     *
     * When it gives up (patience spent, or a refusal it does not retry) it throws that refusal, having abandoned the
     * change it proposed, if any: what it leaves behind is an abandoned change in the log, never a staged one, and
     * nothing published.
     */
    suspend fun proposeAndPublishWithoutShadow(
        caller: ControlCaller,
        document: SnapshotKey,
        operations: List<Operation>,
        note: String,
        skipReason: String,
        patience: Duration = DEFAULT_PATIENCE,
    ): Change = PublishWithoutShadow(this, caller, skipReason, Patience(patience)).run(document, operations, note)

    private companion object {
        val DEFAULT_PATIENCE: Duration = Duration.ofSeconds(30)
    }
}
