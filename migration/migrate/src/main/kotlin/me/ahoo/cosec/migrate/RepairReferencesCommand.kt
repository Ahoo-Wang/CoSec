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

package me.ahoo.cosec.migrate

import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.spi.snapshot.SnapshotKeyText
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.migrate.MigrateMain.FAILED
import me.ahoo.cosec.migrate.MigrateMain.OK
import me.ahoo.cosec.migrate.RedisAccess.repairAccess
import java.io.PrintStream

/** `repair-references`. */
internal object RepairReferencesCommand {
    /** `repair-references`: every document visited, every failed one listed; any failure exits with [FAILED]. */
    fun repairReferences(options: Options, out: PrintStream): Int {
        val result = repairAccess(options) { store, conversion ->
            ReferenceRepair(store, conversion, progress = { out.println(RedisUris.scrub(it, options.redisUris)) }).run()
        }
        if (result.busy) {
            out.println(
                "FAILED another repair-references holds the lease [${ReferenceRepair.LEASE}]" +
                    (result.holder?.let { ": $it" } ?: "") + ". A run renews it as it goes; one that died leaves it " +
                    "for at most ${ReferenceRepair.LEASE_DURATION.toMinutes()} minutes: run again then, or delete the " +
                    "key <key-prefix>:control:lease:${ReferenceRepair.LEASE} once you know no run is alive."
            )
            return FAILED
        }
        if (result.converting) {
            out.println("Converted ${result.converted} back-reference set(s) from the format before 8.0.0-beta.5.")
            out.println("Listed ${result.openChanges} open change(s) in the open-change sets.")
        }
        result.unreadOpenChanges.forEach {
            out.println("FAILED change [${SnapshotKeyText.printable(it)}] does not read: the open-change sets stay unkept.")
        }
        out.println("Visited ${result.documents} document(s); added ${result.added} reference(s).")
        out.println("Removed ${result.removed} reference(s) no head refers to.")
        result.apps?.let { out.println("The app list is complete: $it app(s).") }
            ?: out.println("FAILED the app list could not be completed: run again.")
        if (result.formatDone) {
            out.println("The store is in the format of 8.0.0-beta.5.")
        }
        if (result.failed.isNotEmpty() || result.apps == null || result.unreadOpenChanges.isNotEmpty()) {
            out.println("Not everything was repaired: fix what failed and run again.")
            return FAILED
        }
        return OK
    }
}
