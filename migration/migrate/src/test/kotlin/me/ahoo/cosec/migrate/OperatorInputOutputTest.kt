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

package me.ahoo.cosec.migrate

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path

/** What the tool reads of the operator and the store, and what it prints back (round 2 of the review, DR2-M-4 to M-6). */
class OperatorInputOutputTest {
    @TempDir
    lateinit var temp: Path

    private val uri = "redis://:s3cret@redis:6379"

    /** DR2-M-6: a Redis URI option may name the variable or the file that holds the URI, off the command line. */
    @Test
    fun `a Redis URI is read from the environment or a file, and the URI itself still works`() {
        val environment = mapOf("REDIS_URI" to " $uri\n")
        Options.parse(listOf("--to-redis", "env:REDIS_URI"), environment::get).value("to-redis").assert().isEqualTo(uri)
        val file = Files.writeString(temp.resolve("uri"), "$uri\n")
        Options.parse(listOf("--redis=file:$file"), environment::get).value("redis").assert().isEqualTo(uri)
        Options.parse(listOf("--from-redis", uri), environment::get).value("from-redis").assert().isEqualTo(uri)
        Options.parse(listOf("--to-redis", "env:REDIS_URI"), environment::get).redisUris.assert().containsExactly(uri)
        // Only the Redis URI options read a source: any other value is what it says.
        Options.parse(listOf("--out", "env:OUT"), environment::get).value("out").assert().isEqualTo("env:OUT")
    }

    @Test
    fun `a source that holds no URI is a usage error that shows nothing it read`() {
        val empty = Files.writeString(temp.resolve("empty"), "\n")
        listOf(
            listOf("--to-redis", "env:MISSING"),
            listOf("--to-redis", "env:BLANK"),
            listOf("--to-redis", "file:${temp.resolve("absent")}"),
            listOf("--to-redis", "file:$empty"),
        ).forEach { args ->
            assertThrows<IllegalArgumentException> { Options.parse(args, mapOf("BLANK" to " ")::get) }
                .message.assert().describedAs(args.toString()).contains("--to-redis").doesNotContain("s3cret")
        }
    }

    /** DR2-M-4: a 7.x id holding a line break is printed escaped, so it forges no line of the summary. */
    @Test
    fun `the summary prints 7x ids escaped`() {
        val report = ConversionReport(
            listOf(
                Rejection(
                    RejectionKind.DUPLICATE_ID,
                    "cosec:policy:p\nConverted. Input sha256 0.",
                    "policy [p\n] twice"
                )
            ),
            listOf(Warning("cosec:app:a\r", "app [a\u0007]")),
            emptyList(),
            emptyMap(),
            "0",
        )
        val out = ByteArrayOutputStream()
        PrintStream(out, true, Charsets.UTF_8).use { ConvertCommand.summarize(report, it) }
        val lines = out.toString(Charsets.UTF_8).lines()
        lines.none { it.startsWith("Converted.") }.assert().isTrue()
        lines.single { it.startsWith("REJECT") }.assert().contains("\\u000A")
        lines.single { it.startsWith("WARN") }.assert().contains("\\u000D").contains("\\u0007")
    }

    /** DR2-M-5: a key the scan found that holds no value is said, with why, never dropped without a word. */
    @Test
    fun `a value the reader skips is told with why`() {
        val told = ArrayList<String>()
        CoCache6Format.scalar("k", "string", told::add) { CoCache6.value(ByteArray(0), CoCache6Format.STATE_MISSING) }
        CoCache6Format.scalar("k", "string", told::add) { CoCache6.value(ByteArray(0), CoCache6Format.STATE_LEASE) }
        CoCache6Format.scalar("k", "none", told::add) { error("not read") }
        told.assert().containsExactly(
            CoCache6Format.CACHED_ABSENCE,
            CoCache6Format.LOAD_IN_PROGRESS,
            CoCache6Format.GONE
        )
        val policies = sequenceOf("cosec:policy:p1", "cosec:policy:history:p1:1")
        val keys = CoSec7xReader.keys("cosec") { pattern -> policies.filter { pattern.startsWith("cosec:policy:") } }
        keys.policyIds.assert().containsExactly("p1")
        keys.history.assert().containsExactly("history:p1:1")
    }

    @Test
    fun `a conversion reports what the reader skipped as warnings, and counts them`() {
        val skipped = listOf(Warning("cosec:policy:p2", "skipped: ${CoCache6Format.LOAD_IN_PROGRESS}"))
        val source = Source7x(emptyList(), emptySet(), emptyList(), null, "index", "0", skipped = skipped)
        val report = Converter().convert(source).report
        report.warnings.assert().contains(skipped.single())
        report.counts["skipped"].assert().isEqualTo(1)
    }
}
