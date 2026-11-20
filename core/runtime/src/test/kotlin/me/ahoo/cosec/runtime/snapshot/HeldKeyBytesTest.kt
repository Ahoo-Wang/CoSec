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
import me.ahoo.cosec.annotations.ExperimentalCoSecApi

/**
 * Bytes per held key (RFC 0013 §5.4). The source holds [KEYS] app documents; a class histogram of the live heap
 * (`GC.class_histogram`, which collects first) is taken before the loads and after, and the difference per class,
 * divided by [KEYS], is what one held key retains. Retained objects only, counted by class: what a heap-wide "used"
 * figure gets wrong when anything else allocates meanwhile. The table it prints names every class a held key retains.
 *
 * Two bounds. The key's own objects are [OWN], one instance each, whatever the JVM's object layout: a new object per key
 * fails here, and a new field shows in the table. The total is at most [MAX_BYTES], gate 5a of RFC 0013 §5.4 as an
 * absolute figure. It was `Slot`'s 556 bytes plus 100 on the host where #1597 measured both; on that host the runner
 * held 620. A held key keeps no caller queue (#1615).
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

    /** Per class, the instances and bytes one held key retains. */
    private fun perKey(): Map<String, Pair<Double, Double>> {
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
            listOf(SnapshotFamily(SnapshotKind.APP, compiler, options.maxApps, options.maxAbsentApps)),
            options,
            ManualClock(),
            SnapshotMetrics.NOOP,
            directExecutor,
            null,
            { 0L },
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
    fun `a held key retains its runner, drain, effects and state once each, at most 656 bytes, and no caller queue`() {
        val logger = LoggerFactory.getLogger("me.ahoo.cosec") as Logger
        val level = logger.level
        logger.level = Level.WARN
        try {
            // Once first, so the measured run finds its classes loaded and its code warm.
            perKey()
            val held = perKey()
            println(table(held))
            OWN.forEach { name ->
                (held[PACKAGE + name]?.first ?: 0.0).assert().describedAs("$name instances per held key")
                    .isBetween(1.0 - INSTANCE_TOLERANCE, 1.0 + INSTANCE_TOLERANCE)
            }
            held.keys.filter { it.startsWith(PACKAGE + "Key") }.toSet().assert()
                .describedAs("the key's own classes a held key retains").isEqualTo(OWN.map { PACKAGE + it }.toSet())
            held.values.sumOf { it.second }.assert().describedAs("bytes per held key").isLessThanOrEqualTo(MAX_BYTES)
            held.keys.none { it.startsWith("java.util.concurrent.ConcurrentLinkedQueue") }.assert()
                .describedAs("an idle key holds a caller queue").isTrue()
        } finally {
            logger.level = level
        }
    }

    private fun table(held: Map<String, Pair<Double, Double>>): String = buildString {
        appendLine("Bytes per held key ($KEYS keys), instances × and bytes per key:")
        appendLine("| class | per key |")
        appendLine("|---|---|")
        held.entries.sortedByDescending { it.value.second }.forEach { (name, value) ->
            appendLine("| $name | %.2f × → %.0f B |".format(value.first, value.second))
        }
        appendLine("| total | %.0f B |".format(held.values.sumOf { it.second }))
    }

    private companion object {
        const val KEYS = 20_000

        /** Gate 5a of RFC 0013 §5.4: `Slot`'s 556 bytes plus 100, on the host #1597 measured. */
        const val MAX_BYTES = 656.0

        const val PACKAGE = "me.ahoo.cosec.runtime.snapshot."

        /** What a held key retains of its own: the runner and what it is made of, one each. */
        val OWN = listOf("KeyRunner", "KeyDrain", "KeyEffects", "KeyState")

        /** Instances per key are 1.00 to two decimals; a class a test leaves alive moves them by less. */
        const val INSTANCE_TOLERANCE = 0.01

        /** Below this a class is noise of the run, not something each key retains. */
        const val MIN_BYTES = 4.0
    }
}
