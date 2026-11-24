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

import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.store.ControlStore
import me.ahoo.cosec.control.store.HeadWrite
import me.ahoo.cosec.control.store.InMemoryControlStore
import me.ahoo.cosec.control.store.RevisionIndex
import me.ahoo.cosec.document.PolicyDocumentCodec
import me.ahoo.cosec.document.SnapshotDocuments
import me.ahoo.cosec.spi.snapshot.SnapshotDigest
import me.ahoo.cosec.spi.snapshot.SnapshotDocument
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream

/**
 * DOC-15, before an upgrade: `check-patterns` names every pattern of the stored heads the new version does not compile,
 * where it is and how to write it, reading the documents as an earlier preview stored them; it writes nothing.
 */
class CheckPatternsTest {
    private val memory = InMemoryControlStore()

    private fun body(policies: String, more: String = ""): String =
        """{"schema":${PolicyDocumentCodec.SCHEMA},"revision":1,"publishedAt":"2026-11-01T00:00:00Z",""" +
            """"policies":$policies$more}"""

    private fun stored(key: SnapshotKey, body: String) = runBlocking {
        memory.putIndex(key, null, RevisionIndex()).assert().isTrue()
        memory.putHead(key, null, SnapshotDocuments.read(body.encodeToByteArray()))
            .assert().isInstanceOf(HeadWrite.Written::class.java)
    }

    private fun scan(body: String) = PatternScan.scan(body.encodeToByteArray())

    @Test
    fun `every pattern kind is read where a document holds it, and each refusal says how to write it`() {
        val findings = scan(
            body(
                """[{"id":"p","statements":[
                  {"effect":"deny","action":["http:GET","odata:meta!"],"resource":"http:path:/odata/${'$'}metadata"},
                  {"effect":"allow","action":"*","resource":[{"pattern":"http:path:/files/a(1).txt","caseSensitive":false},
                   "file/a(1)/{id}/**"]}]}]""",
                ""","rateLimits":[{"id":"r","action":"*","resource":"http:path:/x/{id}/[a]*","permitsPerSecond":1}]""" +
                    ""","catalog":{"groups":[{"name":"g","permissions":[{"id":"p","action":"wms:re|ad"}]}]}""",
            )
        )
        findings.map { it.location to it.written }.assert().containsExactly(
            "$.policies[0].statements[0].action[1]" to "odata:meta\\!",
            "$.policies[0].statements[0].resource" to "http:path:/odata/\\${'$'}metadata",
            "$.policies[0].statements[1].resource[0].pattern" to "http:path:/files/a\\(1\\).txt",
            "$.policies[0].statements[1].resource[1]" to "file/a\\(1\\)/{id}/**",
            "$.rateLimits[0].resource" to "http:path:/x/{id}/\\[a\\]*",
            "$.catalog.groups[0].permissions[0].action" to "wms:re\\|ad",
        )
        findings[1].problem.assert().contains("at 7: '${'$'}' is reserved for a future pattern syntax")
        findings[1].pattern.assert().isEqualTo("http:path:/odata/${'$'}metadata")
    }

    @Test
    fun `a pattern that matched no request path has no escape, and says so`() {
        val findings =
            scan(body("""[{"id":"p","statements":[{"effect":"deny","action":"*","resource":"http:path:/a;b"}]}]"""))
        findings.single().written.assert().isNull()
        findings.single().problem.assert().contains("hold no ';'")
        PatternScan.resourceWritten("http:path:/a\\b").assert().isEqualTo("http:path:/a\\\\b")
        scan(body("""[{"id":"p","statements":[{"effect":"deny","action":"*","resource":"http:path:/a\\\\b"}]}]"""))
            .single().written.assert().isNull()
    }

    /** Each shape a pattern takes in a document, and each part a rewrite keeps or escapes. */
    @Test
    fun `every shape is read and every part of a rewrite is kept or escaped as it was read`() {
        val findings = scan(
            body(
                """[{"id":"p","statements":[
                  {"effect":"allow","action":[1,"a(b:*"],"resource":[{"caseSensitive":false},7,"t(x/*/{id}/**"]},
                  {"effect":"allow","action":"*","resource":{"pattern":"http:path:/a!","decodeAndParseSegments":false}}]}]"""
            )
        )
        findings.map { it.location to it.written }.assert().containsExactly(
            "$.policies[0].statements[0].action[1]" to "a\\(b:*",
            "$.policies[0].statements[0].resource[2]" to "t\\(x/*/{id}/**",
            "$.policies[0].statements[1].resource.pattern" to "http:path:/a\\!",
        )
        PatternScan.actionWritten("*").assert().isEqualTo("*")
        PatternScan.actionWritten("nope").assert().isEqualTo("nope")
        PatternScan.resourceWritten("t/a**/**").assert().isEqualTo("t/a\\*\\*/**")
    }

    @Test
    fun `a document whose patterns compile has nothing to say`() {
        scan(
            body(
                """[{"id":"p","statements":[{"effect":"allow","action":"order:*","resource":["order/**","http:path:/x/*"]}]}]"""
            )
        )
            .assert().isEmpty()
        scan("""{"schema":2,"revision":1,"publishedAt":"2026-11-01T00:00:00Z"}""").assert().isEmpty()
    }

    @Test
    fun `the command names each finding and each document, fails on a finding, and writes nothing`() {
        stored(
            SnapshotKey.Tenant("acme"),
            body(
                """[{"id":"p","statements":[{"effect":"deny","action":"*","resource":"http:path:/odata/${'$'}metadata"}]}]"""
            )
        )
        stored(
            SnapshotKey.App("order"),
            body("""[{"id":"p","statements":[{"effect":"allow","action":"*","resource":"order/**"}]}]""")
        )
        val (code, output) = report(CheckPatternsCommand.check(ReadOnly(memory)))
        code.assert().describedAs(output).isEqualTo(MigrateMain.FAILED)
        output.lines().filter { it.isNotEmpty() }.assert().containsExactly(
            "ok app:order",
            "PATTERN tenant:acme $.policies[0].statements[0].resource: Invalid pattern [/odata/${'$'}metadata] at 7: " +
                "'${'$'}' is reserved for a future pattern syntax; write \\${'$'} for the character; " +
                "write http:path:/odata/\\${'$'}metadata, in JSON \"http:path:/odata/\\\\${'$'}metadata\"",
            "Checked 2 document(s): 1 pattern(s) to rewrite in 1 document(s), 0 could not be read.",
        )
        report(CheckPatternsCommand.check(ReadOnly(memory), only = SnapshotKey.App("order"))).let { (ok, out) ->
            ok.assert().describedAs(out).isEqualTo(MigrateMain.OK)
            out.assert().contains(
                "Checked 1 document(s): 0 pattern(s) to rewrite in 0 document(s), 0 could not be read."
            )
        }
        report(
            CheckPatternsCommand.check(ReadOnly(memory), prefix = "tenant:")
        ).first.assert().isEqualTo(MigrateMain.FAILED)
        report(CheckPatternsCommand.check(ReadOnly(memory), only = SnapshotKey.Tenant("nobody"))).second
            .assert().contains("ok tenant:nobody")
    }

    @Test
    fun `a head that cannot be read is named and fails the run, after the others`() {
        stored(SnapshotKey.App("order"), body("[]"))
        stored(SnapshotKey.Tenant("acme"), body("[]"))
        val failing = object : ControlStore by memory {
            override suspend fun head(key: SnapshotKey) =
                if (key == SnapshotKey.Tenant("acme")) error("redis down") else memory.head(key)
        }
        val (code, output) = report(CheckPatternsCommand.check(failing))
        code.assert().isEqualTo(MigrateMain.FAILED)
        output.assert().contains("ok app:order").contains("FAILED tenant:acme: redis down")
            .contains("Checked 2 document(s): 0 pattern(s) to rewrite in 0 document(s), 1 could not be read.")
    }

    private fun report(checked: List<CheckPatternsCommand.Checked>): Pair<Int, String> {
        val out = ByteArrayOutputStream()
        val code = PrintStream(out, true, Charsets.UTF_8).use { CheckPatternsCommand.report(checked, it) }
        return code to out.toString(Charsets.UTF_8)
    }

    /** [store] for reading: a write fails the test, since `check-patterns` writes nothing. */
    private class ReadOnly(store: ControlStore) : ControlStore by store {
        override suspend fun putHead(key: SnapshotKey, expected: SnapshotDigest?, document: SnapshotDocument) =
            throw AssertionError("check-patterns wrote to the store")

        override suspend fun putRecord(key: SnapshotKey, document: SnapshotDocument) =
            throw AssertionError("check-patterns wrote to the store")
    }
}
