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

package me.ahoo.cosec.control.audit

import me.ahoo.cosec.annotations.InternalCoSecApi

/**
 * What a queued change event holds in memory (`CTL-66`), measured from its fields rather than by encoding it: the
 * listener contract hands a listener the event, not bytes, so an encoding made to measure would be thrown away, and
 * each sink encodes again. A string counts two bytes a character (UTF-16, a JVM's worst case) plus its header; every
 * object a fixed overhead. An event holds no change record, only ids, revisions and what its step records, so this is
 * its heap, within a small factor.
 */
internal object ChangeEventSize {
    /** The event's own object, its id, time, kind, actor and the keys it names. */
    private const val EVENT = 512L
    private const val OBJECT = 64L
    private const val STRING = 40L

    fun of(event: ChangeEvent): Long {
        var bytes = EVENT + text(event.id) + text(event.change) + text(event.document.toString()) +
            text(event.note) + text(event.operator.principal.value) + text(event.operator.tenant.value)
        event.base?.let { bytes += OBJECT + text(it.digest) }
        event.candidate?.let { bytes += OBJECT + text(it.digest) }
        event.escalations.forEach { bytes += OBJECT + text(it.subject) + text(it.detail) }
        event.confirmations.forEach { bytes += OBJECT + text(it.policy.value) + text(it.digest) + text(it.kind) }
        event.moved.forEach { bytes += OBJECT + text(it.document) + text(it.checked) + text(it.current) }
        event.shadow?.let { shadow ->
            bytes += OBJECT + shadow.counts.keys.sumOf { OBJECT + text(it) } + shadow.vanished.sumOf { text(it) }
        }
        event.origin?.let { origin ->
            bytes += OBJECT + text(origin.peer)
            origin.via?.let { bytes += OBJECT + text(it.method) + text(it.credentialId) + text(it.requestId) }
        }
        return bytes
    }

    private fun text(value: String?): Long = if (value == null) 0 else STRING + 2L * value.length
}
