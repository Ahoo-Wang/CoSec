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
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.migrate.MigrateMain.FAILED
import me.ahoo.cosec.migrate.MigrateMain.OK
import me.ahoo.cosec.migrate.RedisAccess.controlStore
import java.io.PrintStream

/** `repair-references`. */
internal object RepairReferencesCommand {
    /** `repair-references`: every document visited, every failed one listed; any failure exits with [FAILED]. */
    fun repairReferences(options: Options, out: PrintStream): Int {
        val result = controlStore(options) { store -> ReferenceRepair(store).run() }
        result.failed.forEach { (key, message) ->
            out.println(
                "FAILED $key: ${RedisUris.scrub(message, options.redisUris)}"
            )
        }
        out.println("Visited ${result.documents} document(s); added ${result.added} reference(s).")
        out.println("Removed ${result.removed} reference(s) no head refers to.")
        result.apps?.let { out.println("The app list is complete: $it app(s).") }
            ?: out.println("FAILED the app list could not be completed: run again.")
        if (result.failed.isNotEmpty() || result.apps == null) {
            out.println("Not everything was repaired: fix what failed and run again.")
            return FAILED
        }
        return OK
    }
}
