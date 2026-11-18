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

@file:OptIn(InternalCoSecApi::class, ExperimentalCoSecApi::class)

package me.ahoo.cosec.runtime.snapshot

import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi

/** What the store answered when an entry was made; decides how the entry is revalidated. */
internal enum class EntryOrigin {
    /** A decoded, compiled document. */
    DOCUMENT,

    /** A decoded document that failed to compile: revalidated with a full read so that it is compiled again. */
    UNCOMPILABLE,

    /**
     * A document compiled against inputs that changed since, whose compile again has not run (its reload failed or was
     * rejected): failing closed until it does, revalidated with a full read as [UNCOMPILABLE] is. Not a document that
     * does not compile: the decision says the source is unavailable, not that the document is invalid.
     */
    RECOMPILE_PENDING,

    /** A stored value that cannot be decoded. */
    UNDECODABLE,

    /** No document, and none seen before. */
    ABSENT,

    /** No document, although one was seen: the store lost data. */
    LOST,

    /** The store could not be read; never installed. */
    TRANSIENT,
    ;

    /** Whether the store held no document. */
    val storedAbsent: Boolean get() = this == ABSENT || this == LOST

    /** Whether the entry was compiled from a decoded document, or tried to be: it depends on the compiler's inputs. */
    val compiled: Boolean get() = this == DOCUMENT || this == UNCOMPILABLE || this == RECOMPILE_PENDING

    /** Whether the entry is revalidated with a full read, so that the document is compiled again. */
    val compilesAgain: Boolean get() = this == UNCOMPILABLE || this == RECOMPILE_PENDING
}
