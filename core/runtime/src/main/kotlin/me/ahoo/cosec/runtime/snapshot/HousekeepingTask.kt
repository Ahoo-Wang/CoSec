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

/**
 * What a key's work hands to housekeeping, the closed set [SnapshotMetrics.onHousekeepingRefused] counts and
 * `cosec.snapshot.housekeeping.refused{task}` tags (`SNP-19`). When housekeeping refuses a task, full or stopped, every
 * task is dropped and counted but [DROP], which [runsInPlace]: one offer to the key's own drain, with no I/O and no log.
 */
@InternalCoSecApi
enum class HousekeepingTask(val tag: String, val runsInPlace: Boolean = false) {
    /** A load, reload or recompile that failed, a document lost or regressed: a line with its cause. */
    LOAD_LOG("load-log"),

    /** A revision that does not compile, with its cause, or one that compiles again. */
    COMPILE_LOG("compile-log"),

    /** A review of an installed document (UC-1, [InstallReview]); refused, it is offered again on the key's next pass. */
    INSTALL_REVIEW("install-review"),

    /** An effect that failed, after its load finished or while a failed effect was handled. */
    EFFECT_LOG("effect-log"),

    /** A `VirtualMachineError` an effect threw on a thread not CoSec's, logged rather than rethrown. */
    CRASH_LOG("crash-log"),

    /** A transition that failed, or the answering of one. */
    TRANSITION_LOG("transition-log"),

    /** A prewarm that failed and retries. */
    PREWARM_LOG("prewarm-log"),

    /** A revalidation pass that stalled or failed. */
    PASS_LOG("pass-log"),

    /** A reset of the store's change notifications, which reloads every installed document. */
    RESET_LOG("reset-log"),

    /** A family's eviction pass under its cap: the next load's end of a held key asks again (`SNP-14`). */
    EVICT("evict"),

    /** An empty key's drop: run in place when refused. */
    DROP("drop", runsInPlace = true),

    /** The periodic lag report, which the next tick queues again. */
    LAG_REPORT("lag-report"),
    ;

    /** The line kept for a refused task about [key] that has no message of its own: built only when it is refused. */
    internal fun about(key: SnapshotKey): String = when (this) {
        EVICT -> "The eviction pass of the ${key.kind} family."
        DROP -> "The drop of empty key [$key]."
        else -> "$tag [$key]"
    }
}
