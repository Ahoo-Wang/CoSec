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
import me.ahoo.cosec.migrate.MigrateMain.FAILED
import me.ahoo.cosec.migrate.MigrateMain.OK
import me.ahoo.cosec.migrate.RedisAccess.readRedis
import java.io.PrintStream
import java.nio.file.Path

/** `convert`: 7.x data to 8.0 documents. */
internal object ConvertCommand {
    fun convert(options: Options, out: PrintStream): Int {
        val fromRedis = options.value("from-redis")
        val fromFiles = options.value("from-files")
        if ((fromRedis == null) == (fromFiles == null)) {
            throw UsageException("Give exactly one of --from-redis and --from-files.")
        }
        val partition = partitionOf(options)
        val outDirectory = Path.of(options.required("out"))
        val read = if (fromRedis != null) {
            readRedis(fromRedis, options.value("key-prefix") ?: DEFAULT_KEY_PREFIX)
        } else {
            ExportDirectory.read(Path.of(fromFiles!!))
        }
        val source = options.value("rate-limit-rules")?.let { file -> read.withRateLimitRules(ExportDirectory.rateLimitRules(Path.of(file))) } ?: read
        val conversion = Converter().convert(source, ConvertOptions(partition))
        ConvertedDirectory.write(conversion, outDirectory)
        summarize(conversion.report, out)
        out.println("Wrote ${outDirectory.resolve(ConvertedDirectory.REPORT_FILE)}")
        return if (conversion.report.accepted) OK else FAILED
    }

    private fun partitionOf(options: Options): Partition = when (val text = options.value("partition") ?: "platform") {
        "platform" -> Partition.PLATFORM
        "by-owner" -> Partition.BY_OWNER
        else -> throw UsageException("--partition is platform or by-owner, not [$text].")
    }

    fun summarize(report: ConversionReport, out: PrintStream) {
        report.counts.forEach { (name, count) -> out.println("$name: $count") }
        report.semanticChanges.groupingBy { it.change }.eachCount().toSortedMap().forEach { (change, count) ->
            out.println("semantic change $change: $count place(s)")
        }
        report.warnings.forEach { out.println("WARN   ${it.location}: ${it.message}") }
        report.rejections.forEach { out.println("REJECT ${it.kind} ${it.location}: ${it.message} (${it.advice})") }
        out.println(
            if (report.accepted) {
                "Converted. Input sha256 ${report.inputSha256}."
            } else {
                "Rejected: fix the ${report.rejections.size} rejection(s) and convert again. Nothing else was written."
            },
        )
    }
}
