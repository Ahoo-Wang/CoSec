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

package me.ahoo.cosec.control.change

import me.ahoo.cosec.annotations.InternalCoSecApi

/**
 * Which conflict a `409` of the control API is (`CTL-80`): the problem's `conflict` member, a closed set
 * (`contract/control/vocabularies.json`), so a caller tells "read again and retry" from "wait" from "stop" without
 * reading `detail`, which explains the case to a person and may change wording.
 */
@InternalCoSecApi
enum class ConflictKind {
    /** The document's head is not the change's base: read the head again, and propose again or rebase. */
    BASE_MOVED,

    /** Another change holds the document's staging slot, which the problem's `heldBy` names: wait for it, or abandon it. */
    SLOT_HELD,

    /** The change's state does not admit the step: read the change again; the step is not for it now. */
    STATE,

    /** The candidate named is not the change's: read the change again, and act on the candidate it has. */
    CANDIDATE_MOVED,

    /** The change's shadow window is still open: wait for it to close. */
    WINDOW_OPEN,

    /** The change's shadow window closed blocked: it never publishes; abandon it. */
    WINDOW_BLOCKED,

    /** Concurrent writes kept changing the record: the same call, made again, may succeed. */
    CONTENDED,

    /**
     * Something of that id already stands where the step needs something else: a change of that id, a binding that
     * break-glass would create, or a binding a break-glass revoke names that break-glass did not write. Stop: the id
     * names something else; choose another id, or act on what stands there through its own route.
     */
    EXISTS,

    /**
     * The document holds as many open changes as it may (`CTL-83`, `cosec.control.revisions.max-count`), the oldest of
     * which the problem's `heldBy` names: abandon one, or wait for it to expire.
     */
    OPEN_LIMIT,
}
