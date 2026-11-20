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

package me.ahoo.cosec.runtime.snapshot

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import java.time.Duration

/** What [KeyTransitions] knows of the source's options, and of its key when it serves one; fixed for its life. */
internal data class KeyConfig(
    /**
     * The key of the steps [KeyTransitions.next] makes alone. Null for transitions a source's runners share, whose
     * steps each carry their runner's key (RFC 0013 B2b step 3).
     */
    val key: SnapshotKey?,
    /** The document every request needs: never recompiled as a family entry, never removed. */
    val shared: Boolean,
    val storeReadTimeout: Duration,
    val rejectedReloadRetry: Duration,
    /** How long a cold load the compile queue refuses keeps trying to compile, from its start (R2D-4). */
    val coldLoadTimeout: Duration,
    /** How long such a cold load waits, at most, before it compiles again. */
    val coldCompileRetry: Duration,
    val revalidateInterval: Duration,
    /** How old the store's local copy may be: a read from it is confirmed only as of that long before it started. */
    val localCopyMaxAge: Duration,
)
