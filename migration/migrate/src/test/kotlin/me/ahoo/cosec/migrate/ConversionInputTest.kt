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

package me.ahoo.cosec.migrate

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.migrate.Fixtures7x.app
import me.ahoo.cosec.migrate.Fixtures7x.appPermission
import me.ahoo.cosec.migrate.Fixtures7x.permission
import me.ahoo.cosec.migrate.Fixtures7x.policy
import me.ahoo.cosec.migrate.Fixtures7x.source
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path

/**
 * What `convert` does with input it cannot carry over faithfully (audit issues #1232, #1233, #1247, #1256, #1259): it
 * refuses with the input's location and still writes `report.json`; it never reports success for data it dropped.
 */
class ConversionInputTest {
    @TempDir
    lateinit var temp: Path

    private fun appOf(id: String, vararg grants: Pair<String, String>) =
        app(id, appPermission(id, permission("read", action = "\"/orders/**\"")), *grants)

    private fun run(vararg args: String): Pair<Int, String> {
        val out = ByteArrayOutputStream()
        val code = PrintStream(out, true, Charsets.UTF_8).use { MigrateMain.run(args.toList(), it) }
        return code to out.toString(Charsets.UTF_8)
    }

    private fun export(policies: String = "[]", apps: String = "[]"): Path {
        val directory = Files.createDirectories(temp.resolve("export"))
        Files.writeString(directory.resolve(ExportDirectory.POLICIES_FILE), policies)
        Files.writeString(directory.resolve(ExportDirectory.GLOBAL_POLICIES_FILE), "[]")
        Files.writeString(directory.resolve(ExportDirectory.APP_ROLE_PERMISSIONS_FILE), apps)
        return directory
    }

    private fun exportedApp(grants: String?): String {
        val permission = """{"id":"read","name":"read","description":"","effect":"allow","action":"/orders/**"}"""
        val body = """{"id":"orders","groups":[{"name":"g","description":"","spaced":false,"permissions":[$permission]}]}"""
        return """[{"appPermission":$body${grants?.let { ",\"grants\":$it" }.orEmpty()}}]"""
    }

    // ---- #1232 ----

    @Test
    fun `an app listed twice is refused with both places, whatever its content`() {
        val first = appOf("orders", "reader" to """["read"]""")
        val same = appOf("orders", "reader" to """["read"]""").let {
            Source7x.App(it.appId, "app:second", it.appPermission, it.grants)
        }
        val empty = appOf("orders").let { Source7x.App(it.appId, "app:third", it.appPermission, it.grants) }
        for (second in listOf(same, empty)) {
            val conversion = Converter().convert(source(apps = listOf(first, second)))
            conversion.report.accepted.assert().isFalse()
            conversion.documents.assert().isEmpty()
            val rejection = conversion.report.rejections.single()
            rejection.kind.assert().isEqualTo(RejectionKind.DUPLICATE_ID)
            rejection.location.assert().isEqualTo(second.location)
            rejection.message.assert().contains("orders").contains(first.location)
        }
    }

    @Test
    fun `several policies of one tenant are not a duplicate`() {
        val conversion = Converter().convert(
            source(
                policies = listOf("a" to policy("a", tenantId = "acme"), "b" to policy("b", tenantId = "acme")),
                apps = listOf(
                    appOf("orders", "reader" to """["read"]"""),
                    appOf("billing", "reader" to """["read"]""")
                ),
            ),
            ConvertOptions(Partition.BY_OWNER),
        )
        conversion.report.accepted.assert().describedAs("${conversion.report.rejections}").isTrue()
        conversion.documents.getValue(SnapshotKey.Tenant("acme")).policies.assert().hasSize(2)
    }

    // ---- #1233 ----

    @Test
    fun `grants must be an object when the export gives them`() {
        for (grants in listOf("""[{"reader":["read"]}]""", "\"reader\"", "7", "null", "true")) {
            val directory = export(apps = exportedApp(grants))
            assertThrownBy<IllegalArgumentException> { ExportDirectory.read(directory) }
                .hasMessageContaining("${ExportDirectory.APP_ROLE_PERMISSIONS_FILE}[0].grants")
        }
    }

    @Test
    fun `grants that are absent or an empty object read as no grants, an object as its grants`() {
        ExportDirectory.read(export(apps = exportedApp(null))).apps.single().grants.assert().isEmpty()
        Files.writeString(temp.resolve("export").resolve(ExportDirectory.APP_ROLE_PERMISSIONS_FILE), exportedApp("{}"))
        ExportDirectory.read(temp.resolve("export")).apps.single().grants.assert().isEmpty()
        Files.writeString(
            temp.resolve("export").resolve(ExportDirectory.APP_ROLE_PERMISSIONS_FILE),
            exportedApp("""{"reader":["read"]}"""),
        )
        ExportDirectory.read(
            temp.resolve("export")
        ).apps.single().grants.map { it.roleId }.assert().containsExactly("reader")
    }

    // ---- #1247 ----

    @Test
    fun `a combined document that does not compile is a rejection with a report, not an exception`() {
        val statements = List(65) { Fixtures7x.statement("\"/{v}a*a*a*a*ab\"") }
        val first = policy("first", statements = statements)
        val second = policy("second", statements = statements)
        val alone = Converter().convert(source(policies = listOf("first" to first)))
        alone.report.accepted.assert().describedAs("${alone.report.rejections}").isTrue()

        val conversion = Converter().convert(source(policies = listOf("first" to first, "second" to second)))
        conversion.report.accepted.assert().isFalse()
        conversion.documents.assert().isEmpty()
        conversion.report.rejections.single().apply {
            kind.assert().isEqualTo(RejectionKind.DOES_NOT_COMPILE)
            location.assert().contains("platform")
            message.assert().contains("2048")
        }

        val out = temp.resolve("out")
        val (code, output) = run(
            "convert",
            "--from-files",
            export("[$first,$second]").toString(),
            "--out",
            out.toString()
        )
        code.assert().describedAs(output).isEqualTo(MigrateMain.FAILED)
        val report = out.resolve(ConvertedDirectory.REPORT_FILE)
        Files.isRegularFile(report).assert().describedAs(output).isTrue()
        Files.readString(report).assert().contains("\"accepted\" : false").contains("DOES_NOT_COMPILE")
    }

    // ---- #1256 ----

    @Test
    fun `an id whose encoded file name is too long is written and read back, through a bounded name`() {
        val tenant = "租".repeat(30)
        val app = "应".repeat(30)
        val conversion = Converter().convert(
            source(
                policies = listOf("p" to policy("p", tenantId = tenant)),
                apps = listOf(appOf(app, "reader" to """["read"]""")),
            ),
            ConvertOptions(Partition.BY_OWNER),
        )
        conversion.report.accepted.assert().describedAs("${conversion.report.rejections}").isTrue()
        val directory = temp.resolve("converted")
        ConvertedDirectory.write(conversion, directory)
        val names = Files.walk(directory).use { stream -> stream.map { it.fileName.toString() }.toList() }
        names.maxOf { it.length }.assert().isLessThanOrEqualTo(ConvertedDirectory.MAX_FILE_NAME)
        val read = ConvertedDirectory.read(directory)
        read.keys.assert().containsExactlyInAnyOrder(
            SnapshotKey.Platform,
            SnapshotKey.Tenant(tenant),
            SnapshotKey.App(app),
        )
    }

    @Test
    fun `a file name that is bounded is a function of the id, so two long ids never share one`() {
        val one = ConvertedDirectory.fileOf(SnapshotKey.Tenant("租".repeat(30)))
        val other = ConvertedDirectory.fileOf(SnapshotKey.Tenant("租".repeat(29) + "田"))
        one.assert().isNotEqualTo(other)
        ConvertedDirectory.fileOf(SnapshotKey.Tenant("acme")).assert().isEqualTo("tenants/acme.json")
        ConvertedDirectory.fileOf(SnapshotKey.Tenant("a/b")).assert().isEqualTo("tenants/a%2Fb.json")
    }

    @Test
    fun `a converted directory whose bounded name does not match its recorded id is refused`() {
        val tenant = "租".repeat(30)
        val conversion = Converter().convert(
            source(policies = listOf("p" to policy("p", tenantId = tenant))),
            ConvertOptions(Partition.BY_OWNER),
        )
        val directory = temp.resolve("converted")
        ConvertedDirectory.write(conversion, directory)
        val report = directory.resolve(ConvertedDirectory.REPORT_FILE)
        Files.writeString(report, Files.readString(report).replace(tenant, "租".repeat(29) + "田"))
        assertThrownBy<IllegalArgumentException> { ConvertedDirectory.read(directory) }
    }

    // ---- #1259 ----

    @Test
    fun `ids whose converted files differ are not a collision, ids that differ in case in the file name are`() {
        val apart = Converter().convert(
            source(
                apps = listOf(appOf("strasse", "reader" to """["read"]"""), appOf("straße", "reader" to """["read"]"""))
            ),
        )
        apart.report.accepted.assert().describedAs("${apart.report.rejections}").isTrue()
        val directory = temp.resolve("apart")
        ConvertedDirectory.write(apart, directory)
        ConvertedDirectory.read(directory).keys.assert().contains(SnapshotKey.App("strasse"), SnapshotKey.App("straße"))

        val collide = Converter().convert(
            source(
                apps = listOf(appOf("Order", "reader" to """["read"]"""), appOf("order", "reader" to """["read"]"""))
            ),
        )
        collide.report.accepted.assert().isFalse()
        collide.report.rejections.single().kind.assert().isEqualTo(RejectionKind.DUPLICATE_ID)
        collide.report.rejections.single().message.assert().contains("Order").contains("order")
    }

    @Test
    fun `tenants are held to the same file name rule`() {
        val conversion = Converter().convert(
            source(policies = listOf("a" to policy("a", tenantId = "Acme"), "b" to policy("b", tenantId = "acme"))),
            ConvertOptions(Partition.BY_OWNER),
        )
        conversion.report.accepted.assert().isFalse()
        conversion.report.rejections.single().kind.assert().isEqualTo(RejectionKind.DUPLICATE_ID)
    }

    // ---- #1225 ----

    @ParameterizedTest
    @ValueSource(
        strings = [
            "publish --to-redis redis://127.0.0.1:1 --in x --dry-run",
            "convert --from-files a --out x --dry-run",
            "convert --from-files a --out x --resume",
            "convert --from-files a --out x --force",
            "bootstrap --to-redis redis://127.0.0.1:1 --platform-admin a --dry-run",
            "bootstrap --to-redis redis://127.0.0.1:1 --platform-admin a --resume",
            "repair-references --to-redis redis://127.0.0.1:1 --dry-run",
            "partition plan --from-files a --out x --dry-run",
            "partition plan --from-files a --out x --partition by-owner",
            "api-keys --redis redis://127.0.0.1:1 --out x --resume",
            "api-keys --redis redis://127.0.0.1:1 --out x --platform-admin a",
            "publish --to-redis redis://127.0.0.1:1 --in x --force",
            "publish --to-redis redis://127.0.0.1:1 --in x --out y",
        ],
    )
    fun `an option the command does not take is a usage error before anything is read or written`(args: String) {
        // The Redis is one nobody listens on: a command that ran anyway would fail with 1, never touch a real one.
        val (code, output) = run(*args.split(' ').toTypedArray())
        code.assert().describedAs(output).isEqualTo(MigrateMain.USAGE)
        output.assert().contains("Usage:")
    }
}
