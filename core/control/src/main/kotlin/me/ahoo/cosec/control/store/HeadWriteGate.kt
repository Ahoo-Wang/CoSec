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

package me.ahoo.cosec.control.store

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.store.RevisionLog.log
import me.ahoo.cosec.document.PolicyDocument
import me.ahoo.cosec.spi.snapshot.SnapshotKey

/**
 * What a publish asks right before its head write, the last step that may still refuse it (RFC 0007 §5.8): its caller's
 * precondition, and again whether the candidate removes a policy other documents refer to (`CTL-30`, DR1-F-9). The first
 * read of the referrers is taken before the record is written; one a referrer published meanwhile is seen here. A
 * referrer that publishes at the very moment of the head write is not: its document then fails to compile, and the
 * engine refuses what it would decide on it (fail closed).
 */
internal class HeadWriteGate(private val referrers: ReferrerIndex) {
    /** What to answer instead of writing [key]'s head over [base]; null when it may be written. */
    suspend fun refusal(
        key: SnapshotKey,
        base: Head?,
        candidate: PolicyDocument,
        revision: Long,
        precondition: suspend () -> Boolean,
    ): PublishOutcome? {
        if (!precondition()) {
            log.info { "[$key] revision $revision was not written: its precondition no longer holds." }
            return PublishOutcome.Conflicted(base?.digest)
        }
        return referrers.referenced(key, base, candidate)?.also {
            log.info { "[$key] revision $revision was not written: a document now refers to what it removes (CTL-30)." }
        }
    }
}
