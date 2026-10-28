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

package me.ahoo.cosec.snapshot

import me.ahoo.cosec.CoSecException
import java.time.Duration

/**
 * A stored snapshot document exists but cannot be decoded. [SnapshotDocumentStore] implementations put it in the cause
 * chain of a failed read so that [SnapshotSource] can tell an unusable document from an unreachable store.
 */
class SnapshotFormatException(message: String, cause: Throwable? = null) : CoSecException(message, cause)

/** A snapshot document cannot be used; the layers it feeds fail closed. [key] names the document. */
class SnapshotUnavailableException(val key: SnapshotKey, message: String, cause: Throwable? = null) :
    CoSecException("Snapshot document [$key] is unavailable: $message", cause)

/** The snapshot an evaluation would use is older than the fail-closed bound configured for the request's tenant. */
class SnapshotStaleException(val tenantId: String, val lag: Duration, val maxLag: Duration) :
    CoSecException("Snapshot lag [$lag] exceeds the fail-closed bound [$maxLag] of tenant [$tenantId].")
