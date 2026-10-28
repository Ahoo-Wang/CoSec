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

import me.ahoo.cosec.migrate.Fixtures7x.app
import me.ahoo.cosec.migrate.Fixtures7x.appPermission
import me.ahoo.cosec.migrate.Fixtures7x.permission
import me.ahoo.cosec.migrate.Fixtures7x.policy
import me.ahoo.cosec.migrate.Fixtures7x.source
import me.ahoo.cosec.runtime.json.CoSecJsonSerializer
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class ConvertedDirectoryTest {
    @TempDir
    lateinit var temp: Path

    private fun export(policies: String, global: String, apps: String): Path {
        val directory = Files.createDirectories(temp.resolve("export"))
        Files.writeString(directory.resolve(ExportDirectory.POLICIES_FILE), policies)
        Files.writeString(directory.resolve(ExportDirectory.GLOBAL_POLICIES_FILE), global)
        Files.writeString(directory.resolve(ExportDirectory.APP_ROLE_PERMISSIONS_FILE), apps)
        return directory
    }

    @Test
    fun `an export converts, and its documents read back as written`() {
        val directory = export(
            "[${policy("g", "global")},${policy("c")}]",
            """["g"]""",
            """[{"appPermission":${appPermission(
                "a/b",
                permission("p")
            )},"grants":{"r":["p"],"r@s":["*"]}},{"appId":"none","grants":{"x":["*"]}}]""",
        )
        val source = ExportDirectory.read(directory)
        source.schemaMarker.assert().isNull()
        source.apps.map { it.appId }.assert().containsExactly("a/b", "none")
        source.apps.first().grants.map { it.roleId to it.spaceId }.assert().containsExactly("r" to "", "r" to "s")
        source.inputSha256.length.assert().isEqualTo(64)

        val conversion = Converter().convert(source)
        conversion.report.accepted.assert().isTrue()
        val out = temp.resolve("out")
        ConvertedDirectory.write(conversion, out)
        // An app id never names a path outside the directory.
        Files.isRegularFile(out.resolve("apps/a%2Fb.json")).assert().isTrue()
        val read = ConvertedDirectory.read(out)
        read.keys.assert().containsExactly(SnapshotKey.Platform, SnapshotKey.App("a/b"))
        read.getValue(SnapshotKey.App("a/b")).bindings.map { it.id.value }.assert().containsExactly("r:r:0", "r:r@s:0")
        val report = CoSecJsonSerializer.readTree(Files.readAllBytes(out.resolve(ConvertedDirectory.REPORT_FILE)))
        report.get("accepted").asBoolean().assert().isTrue()
        report.get("outputSha256").propertyNames().toList().assert()
            .containsExactly("apps/a%2Fb.json", "platform.json", "provenance.json")
        report.get("warnings").single().get("location").asString().assert().isEqualTo("app-role-permissions.json[1]")
        val provenance = CoSecJsonSerializer.readTree(
            Files.readAllBytes(out.resolve(ConvertedDirectory.PROVENANCE_FILE))
        )
        provenance.get("bindings").size().assert().isEqualTo(3)
    }

    @Test
    fun `a rejected conversion writes the report only`() {
        val conversion = Converter().convert(source(listOf("perm:x" to policy("perm:x"))))
        val out = temp.resolve("rejected")
        ConvertedDirectory.write(conversion, out)
        Files.list(out).use { files -> files.map { it.fileName.toString() }.toList() }.assert()
            .containsExactly(ConvertedDirectory.REPORT_FILE)
        val rejection = CoSecJsonSerializer.readTree(Files.readAllBytes(out.resolve(ConvertedDirectory.REPORT_FILE)))
            .get("rejections").single()
        rejection.get("kind").asString().assert().isEqualTo("RESERVED_POLICY_ID")
        rejection.get("location").asString().assert().isEqualTo("policy:perm:x")
        rejection.get("advice").asString().assert().isNotBlank()
        assertThrows<IllegalArgumentException> { ConvertedDirectory.read(out) }
    }

    @Test
    fun `publish reads exactly the files convert wrote, unchanged`() {
        val conversion = Converter().convert(
            source(
                listOf("c" to policy("c")),
                apps = listOf(app("a", appPermission("a", permission("p")), "r" to """["p"]"""))
            )
        )
        val out = temp.resolve("verified")
        ConvertedDirectory.write(conversion, out)
        ConvertedDirectory.read(out).keys.assert().containsExactly(SnapshotKey.Platform, SnapshotKey.App("a"))
        // An edited document.
        val app = out.resolve("apps/a.json")
        val original = Files.readAllBytes(app)
        Files.write(app, original + " ".toByteArray())
        assertThrows<IllegalArgumentException> {
            ConvertedDirectory.read(out)
        }.message.assert().contains("changed since convert")
        Files.write(app, original)
        // A document convert did not write.
        Files.writeString(out.resolve("apps/b.json"), "{}")
        assertThrows<IllegalArgumentException> {
            ConvertedDirectory.read(out)
        }.message.assert().contains("unexpected [apps/b.json]")
        Files.delete(out.resolve("apps/b.json"))
        // A document that went missing.
        Files.delete(app)
        assertThrows<IllegalArgumentException> {
            ConvertedDirectory.read(out)
        }.message.assert().contains("missing [apps/a.json]")
    }

    @Test
    fun `convert never writes into a directory that holds something`() {
        val out = Files.createDirectories(temp.resolve("full"))
        Files.writeString(out.resolve("keep"), "x")
        assertThrows<IllegalArgumentException> { ConvertedDirectory.write(Converter().convert(source()), out) }
    }

    @Test
    fun `a malformed export fails the read`() {
        assertThrows<IllegalArgumentException> { ExportDirectory.read(export("{", "[]", "[]")) }
        assertThrows<IllegalArgumentException> { ExportDirectory.read(temp.resolve("absent")) }
        assertThrows<IllegalArgumentException> { ExportDirectory.read(export("[{}]", "[]", "[]")) }
    }

    @Test
    fun `an export entry without an app id fails the read`() {
        val directory = export("[]", "[1]", "[]")
        assertThrows<IllegalArgumentException> { ExportDirectory.read(directory) }
        Files.writeString(directory.resolve(ExportDirectory.GLOBAL_POLICIES_FILE), "[]")
        Files.writeString(directory.resolve(ExportDirectory.APP_ROLE_PERMISSIONS_FILE), """[{"grants":{}}]""")
        assertThrows<IllegalArgumentException> { ExportDirectory.read(directory) }
    }
}
