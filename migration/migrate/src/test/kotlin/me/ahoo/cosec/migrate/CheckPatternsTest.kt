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
import me.ahoo.cosec.control.change.ChangeRequest
import me.ahoo.cosec.control.change.ChangeRequestCodec
import me.ahoo.cosec.control.change.ChangeState
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

    /**
     * DOC-15's bridge for the window of an upgrade: what both versions read alike and what matches more, a `?` for a
     * path's reserved character, `*` for an action's name or a typed resource's segment that holds one; none for a type.
     */
    @Test
    fun `each pattern names the bridge both versions read alike, a wildcard that matches more`() {
        val findings = scan(
            body(
                """[{"id":"p","statements":[
                  {"effect":"deny","action":["odata:meta!","bad!:read"],"resource":"http:path:/odata/${'$'}metadata/{id}"},
                  {"effect":"deny","action":"*","resource":["file/a(1)/{id}/**","t(x/*"]}]}]"""
            )
        )
        findings.map { it.location to it.bridge }.assert().containsExactly(
            "$.policies[0].statements[0].action[0]" to "odata:*",
            "$.policies[0].statements[0].action[1]" to "*",
            "$.policies[0].statements[0].resource" to "http:path:/odata/?metadata/{id}",
            "$.policies[0].statements[1].resource[0]" to "file/*/{id}/**",
            "$.policies[0].statements[1].resource[1]" to null,
        )
        val (_, output) = report(
            listOf(CheckPatternsCommand.Checked(SnapshotKey.Tenant("acme"), listOf(findings.last())))
        )
        // B-11: a DENY without a bridge is kept as it is, never narrowed or removed for the window.
        output.assert().contains("keep it as it is: an instance of this version fails its document closed (503)")
            .doesNotContain("remove the statement")
    }

    /**
     * DOC-15, review B-10: a pattern 8.0.0-beta.8 stored with a backslash compiles here with another meaning, without an
     * error. In a typed resource or an action beta.8 matched the text with the backslash, so it is written with the
     * backslash escaped to keep that; in a path it matched nothing. Each is a finding, with what keeps its meaning.
     */
    @Test
    fun `a backslash beta8 read as a character is a finding, though this version compiles the pattern`() {
        val findings = scan(
            body(
                """[{"id":"p","statements":[
                  {"effect":"deny","action":"svc:a\\${'$'}b","resource":["doc/a\\(b","http:path:/files/\\(y"]},
                  {"effect":"allow","action":"svc:c\\!d","resource":["doc/x\\${'$'}y","http:path:/files/\\${'$'}x"]}]}]"""
            )
        )
        findings.map { listOf(it.location, it.written, it.bridge, it.moved, it.effect) }.assert().containsExactly(
            listOf("$.policies[0].statements[0].action", "svc:a\\\\\\${'$'}b", null, true, PatternScan.Effect.DENY),
            listOf("$.policies[0].statements[0].resource[0]", "doc/a\\\\\\(b", null, true, PatternScan.Effect.DENY),
            listOf("$.policies[0].statements[0].resource[1]", null, null, true, PatternScan.Effect.DENY),
            listOf("$.policies[0].statements[1].action", "svc:c\\\\\\!d", null, true, PatternScan.Effect.GRANT),
            listOf(
                "$.policies[0].statements[1].resource[0]",
                "doc/x\\\\\\${'$'}y",
                null,
                true,
                PatternScan.Effect.GRANT
            ),
            listOf("$.policies[0].statements[1].resource[1]", null, null, true, PatternScan.Effect.GRANT),
        )
        val (code, output) = report(listOf(CheckPatternsCommand.Checked(SnapshotKey.Tenant("acme"), findings)))
        code.assert().isEqualTo(MigrateMain.FAILED)
        val lines = output.lines().filter { it.startsWith("PATTERN") }
        lines[1].assert().contains("read each backslash as a character and matched the text as written")
            .contains("until then keep this pattern and add the exact form beside it in the same entry")
        findings.map { it.beside }.assert().containsExactly(true, true, false, false, false, false)
        lines[2].assert().contains(
            "matched no request path"
        ).contains("which only restricts more: keep it, or remove it")
        lines[4].assert().contains("narrow or remove this grant for the window")
        lines[5].assert().contains("it granted nothing on 8.0.0-beta.8 and grants that path on this version: remove it")
        output.assert().doesNotContain("ok tenant:acme")
    }

    /**
     * DOC-15, reviews B-14 and F-1: a backslash in a typed resource's type compiles here with another meaning, and no
     * wildcard stands in for a type; the stored pattern kept and its exact form added beside it does, for a DENY or a rate
     * limit, as for an id or an action. A grant gets none, and an unescaped reserved character in a type is kept as it is
     * (it fails closed).
     */
    @Test
    fun `a DENY or a rate limit whose type holds a backslash keeps it beside its exact form`() {
        val findings = scan(
            body(
                """[{"id":"p","statements":[
                  {"effect":"deny","action":"doc:read","resource":"do\\(c/x"},
                  {"effect":"allow","action":"doc:read","resource":"do\\(c/z"},
                  {"effect":"deny","action":"doc:read","resource":"t(x/*"}]}]""",
                ""","rateLimits":[{"id":"r","action":"doc:read","resource":"do\\(c/y","permitsPerSecond":1}]""",
            )
        )
        findings.map { listOf(it.location, it.bridge, it.beside, it.moved) }.assert().containsExactly(
            listOf("$.policies[0].statements[0].resource", null, true, true),
            listOf("$.policies[0].statements[1].resource", null, false, true),
            listOf("$.policies[0].statements[2].resource", null, false, false),
            listOf("$.rateLimits[0].resource", null, true, true),
        )
        val (_, output) = report(listOf(CheckPatternsCommand.Checked(SnapshotKey.Tenant("acme"), findings)))
        val lines = output.lines().filter { it.startsWith("PATTERN") }
        lines[0].assert().contains("until then keep this pattern and add the exact form beside it")
            .contains("write do\\\\\\(c/x")
        lines[1].assert().contains("narrow or remove this grant for the window")
        lines[2].assert().contains("keep it as it is: an instance of this version fails its document closed (503)")
        lines[3].assert().contains("until then keep this pattern and add the exact form beside it")
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
                "write http:path:/odata/\\${'$'}metadata, in JSON \"http:path:/odata/\\\\${'$'}metadata\", once every instance runs " +
                "this version; until then write http:path:/odata/?metadata, in JSON \"http:path:/odata/?metadata\", which " +
                "both versions read alike and which matches more",
            "Checked 2 document(s) and 0 open change(s): 1 pattern(s) to rewrite in 1 of them, 0 could not be read.",
        )
        report(CheckPatternsCommand.check(ReadOnly(memory), only = SnapshotKey.App("order"))).let { (ok, out) ->
            ok.assert().describedAs(out).isEqualTo(MigrateMain.OK)
            out.assert().contains(
                "Checked 1 document(s) and 0 open change(s): 0 pattern(s) to rewrite in 0 of them, 0 could not be read."
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
            .contains(
                "Checked 2 document(s) and 0 open change(s): 0 pattern(s) to rewrite in 0 of them, 1 could not be read."
            )
    }

    /**
     * DOC-15: an open change written before the upgrade holds its operations as written; it fails after the upgrade
     * (its publish sends it back to DRAFT, its shadow window closes blocked), so the command names it with its own.
     */
    @Test
    fun `an open change's operations are read too, and named with the change`() {
        stored(SnapshotKey.Tenant("acme"), body("[]"))
        fun change(id: String, state: String, policy: String) = ChangeRequestCodec.decode(
            (
                """{"format":1,"id":"$id","key":"tenant:acme","base":{"revision":0},"operations":[""" +
                    """{"op":"putPolicy","policy":$policy},{"op":"removePolicy","id":"old"}],""" +
                    """"author":{"tenant":"acme","principal":"alice"},"note":"n","kind":"PUBLISH","state":"$state",""" +
                    """"findings":[],"checkedWith":{},"createdAt":"2026-11-01T00:00:00Z","updatedAt":"2026-11-01T00:00:00Z"}"""
                )
                .encodeToByteArray()
        )
        runBlocking {
            memory.putChange(
                null,
                change(
                    "c1",
                    "DRAFT",
                    """{"id":"p","statements":[{"effect":"deny","action":"order:a(b","resource":"order/**"}]}"""
                ),
            )
            memory.putChange(
                null,
                change(
                    "c2",
                    "CHECKED",
                    """{"id":"q","statements":[{"effect":"allow","action":"order:read","resource":"order/**"}]}"""
                ),
            )
        }
        val (code, output) = report(CheckPatternsCommand.check(ReadOnly(memory)))
        code.assert().describedAs(output).isEqualTo(MigrateMain.FAILED)
        val lines = output.lines().filter { it.isNotEmpty() }
        lines[0].assert().isEqualTo("ok tenant:acme")
        lines[1].assert().startsWith(
            "PATTERN tenant:acme change c1 $.operations[0].policy.statements[0].action[0]: Invalid pattern [order:a(b]"
        ).contains("until then write order:*")
        lines.drop(2).assert().containsExactly(
            "ok tenant:acme change c2",
            "Checked 1 document(s) and 2 open change(s): 1 pattern(s) to rewrite in 1 of them, 0 could not be read.",
        )
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

        override suspend fun putChange(expected: SnapshotDigest?, change: ChangeRequest, previous: ChangeState?) =
            throw AssertionError("check-patterns wrote to the store")
    }
}
