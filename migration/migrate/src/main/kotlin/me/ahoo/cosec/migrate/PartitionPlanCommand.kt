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
import me.ahoo.cosec.cocache.DEFAULT_KEY_PREFIX
import me.ahoo.cosec.migrate.ConvertCommand.summarize
import me.ahoo.cosec.migrate.MigrateMain.FAILED
import me.ahoo.cosec.migrate.MigrateMain.OK
import me.ahoo.cosec.migrate.RedisAccess.readRedis
import java.io.PrintStream
import java.nio.file.Path

/** `partition plan`. */
internal object PartitionPlanCommand {
    fun partitionPlan(options: Options, out: PrintStream): Int {
        val fromRedis = options.value("from-redis")
        val fromFiles = options.value("from-files")
        if ((fromRedis == null) == (fromFiles == null)) {
            throw UsageException("Give exactly one of --from-redis and --from-files.")
        }
        val source = if (fromRedis != null) {
            readRedis(
                fromRedis,
                options.value("key-prefix") ?: DEFAULT_KEY_PREFIX
            )
        } else {
            ExportDirectory.read(Path.of(fromFiles!!))
        }
        val platform = Converter().convert(source, ConvertOptions(Partition.PLATFORM))
        val byOwner = Converter().convert(source, ConvertOptions(Partition.BY_OWNER))
        if (!platform.report.accepted || !byOwner.report.accepted) {
            summarize(if (platform.report.accepted) byOwner.report else platform.report, out)
            return FAILED
        }
        val plan = PartitionPlan.of(platform, byOwner)
        val written = PartitionPlan.write(plan, Path.of(options.required("out")))
        plan.keys.zip(written).forEach { (key, path) -> out.println("Planned $key: $path") }
        out.println("Planned ${written.size} tenant(s): submit each with apply (merge). Nothing was written to Redis.")
        return OK
    }
}
