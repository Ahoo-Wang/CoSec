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

import me.ahoo.cosec.control.audit.ChangeEvent
import me.ahoo.cosec.control.audit.MovedHead
import me.ahoo.cosec.control.audit.RevisionRef
import me.ahoo.cosec.runtime.document.Actor
import java.time.Clock

/** The event of [kind] for this change, by [operator], now (RFC 0007 §10.5); [moved] for the RC1-33 events. */
internal fun ChangeRequest.event(
    kind: ChangeEvent.Kind,
    operator: Actor,
    clock: Clock,
    moved: List<MovedHead> = emptyList(),
): ChangeEvent = ChangeEvent(
    id = "${id.value}:${kind.name}:${clock.millis()}",
    time = clock.instant(),
    kind = kind,
    operator = operator,
    change = id,
    document = key,
    base = RevisionRef(base.revision, base.digest?.toString()),
    candidate = candidate?.let { RevisionRef(publishedRevision ?: (base.revision + 1), it) },
    escalations = escalations,
    confirmations = confirmations,
    shadow = shadow?.closed,
    note = note,
    moved = moved,
)
