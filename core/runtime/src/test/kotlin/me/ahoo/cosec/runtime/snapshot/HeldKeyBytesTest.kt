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

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.cosec.spi.snapshot.SnapshotKind
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.lang.management.ManagementFactory
import javax.management.ObjectName

/**
 * Bytes per held key, the runner against `Slot` (RFC 0013 §5.4: at most 100 bytes more). Each side holds [KEYS] app
 * documents, the same documents compiled the same way; a class histogram of the live heap (`GC.class_histogram`, which
 * collects first) is taken before the loads and after, and the difference per class, divided by [KEYS], is what one held
 * key retains. Retained objects only, counted by class: what a heap-wide "used" figure gets wrong when anything else
 * allocates meanwhile. The table it prints names every class a held key retains, side by side.
 */
class HeldKeyBytesTest {
    /** Live instances and bytes per class, after a collection. */
    private fun histogram(): Map<String, Pair<Long, Long>> {
        val text = ManagementFactory.getPlatformMBeanServer().invoke(
            ObjectName("com.sun.management:type=DiagnosticCommand"),
            "gcClassHistogram",
            arrayOf<Any?>(null),
            arrayOf(Array<String>::class.java.name),
        ) as String
        return text.lines().mapNotNull { line ->
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size >= 4 && parts[0].endsWith(":")) parts[3] to (parts[1].toLong() to parts[2].toLong()) else null
        }.toMap()
    }

    /** Per class, the instances and bytes one held key retains under [slots]. */
    private fun perKey(slots: SlotFactory): Map<String, Pair<Double, Double>> {
        val keys = List(KEYS) { SnapshotKey.App("app-$it") }
        val store = ControllableStore().apply {
            put(SnapshotKey.Platform, document(1))
            keys.forEach { put(it, document(1)) }
        }
        val options = SnapshotOptions(revalidateInterval = QUIET_PASS, maxApps = KEYS * 2)
        val compiler = FakeCompiler()
        val source = SnapshotSource(
            store,
            SnapshotKey.Platform,
            compiler,
            listOf(SnapshotFamily(SnapshotKind.APP, compiler, options.maxApps, options.maxAbsent)),
            options,
            ManualClock(),
            SnapshotMetrics.NOOP,
            directExecutor,
            null,
            { 0L },
            null,
            slots,
        )
        source.use {
            it.load().join()
            val before = histogram()
            keys.forEach { key -> it.load(key.appId).join() }
            val after = histogram()
            it.entries().size.assert().isEqualTo(KEYS + 1)
            return after.mapValues { (name, now) ->
                val was = before[name] ?: (0L to 0L)
                (now.first - was.first).toDouble() / KEYS to (now.second - was.second).toDouble() / KEYS
            }.filterValues { it.second >= MIN_BYTES }
        }
    }

    @Test
    fun `a key the runner holds retains at most 100 bytes more than Slot's, and no caller queue`() {
        val logger = LoggerFactory.getLogger("me.ahoo.cosec") as Logger
        val level = logger.level
        logger.level = Level.WARN
        try {
            // Once each first, so both measured runs find their classes loaded and their code warm.
            perKey(SlotFactory.SLOT)
            perKey(keyRunners())
            val slot = perKey(SlotFactory.SLOT)
            val runner = perKey(keyRunners())
            println(table(slot, runner))
            val extra = runner.values.sumOf { it.second } - slot.values.sumOf { it.second }
            extra.assert().describedAs("bytes per held key, runner - Slot").isLessThanOrEqualTo(MAX_EXTRA_BYTES)
            runner.keys.none { it.startsWith("java.util.concurrent.ConcurrentLinkedQueue") }.assert()
                .describedAs("an idle key holds a caller queue").isTrue()
        } finally {
            logger.level = level
        }
    }

    private fun table(slot: Map<String, Pair<Double, Double>>, runner: Map<String, Pair<Double, Double>>): String {
        fun cell(value: Pair<Double, Double>?) = value?.let { "%.2f × → %.0f B".format(it.first, it.second) } ?: "–"
        val classes = (slot.keys + runner.keys).sortedByDescending {
            (runner[it]?.second ?: 0.0) - (slot[it]?.second ?: 0.0)
        }
        return buildString {
            appendLine("Bytes per held key ($KEYS keys), instances × and bytes per key:")
            appendLine("| class | Slot | runner |")
            appendLine("|---|---|---|")
            classes.forEach { appendLine("| $it | ${cell(slot[it])} | ${cell(runner[it])} |") }
            appendLine(
                "| total | %.0f B | %.0f B |".format(slot.values.sumOf { it.second }, runner.values.sumOf { it.second })
            )
        }
    }

    private companion object {
        const val KEYS = 20_000
        const val MAX_EXTRA_BYTES = 100.0

        /** Below this a class is noise of the run, not something each key retains. */
        const val MIN_BYTES = 4.0
    }
}
