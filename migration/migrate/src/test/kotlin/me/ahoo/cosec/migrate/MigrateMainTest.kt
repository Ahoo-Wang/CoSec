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

import me.ahoo.cosec.migrate.Fixtures7x.policy
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path

class MigrateMainTest {
    @TempDir
    lateinit var temp: Path

    private fun run(args: String): Pair<Int, String> {
        val out = ByteArrayOutputStream()
        val code = PrintStream(
            out,
            true,
            Charsets.UTF_8
        ).use { MigrateMain.run(args.split(' ').filter { it.isNotEmpty() }, it) }
        return code to out.toString(Charsets.UTF_8)
    }

    private fun export(policies: String = "[]"): Path {
        val directory = Files.createDirectories(temp.resolve("export"))
        Files.writeString(directory.resolve(ExportDirectory.POLICIES_FILE), policies)
        Files.writeString(directory.resolve(ExportDirectory.GLOBAL_POLICIES_FILE), "[]")
        Files.writeString(directory.resolve(ExportDirectory.APP_ROLE_PERMISSIONS_FILE), "[]")
        return directory
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "",
            "verify --in x",
            "convert --out x",
            "convert --from-files a --from-redis b --out x",
            "convert --from-files a",
            "convert --from-files a --out x --partition tenant",
            "convert --from-files a --out x --root-expires-at tomorrow",
            "convert --from-files a --out x --unknown y",
            "convert --from-files a --out x --root-enabled --root-enabled",
            "convert --from-files a --out x --out y",
            "convert --from-files",
            "convert from-files",
            "publish --in x",
        ],
    )
    fun `usage errors exit with 2`(args: String) {
        val (code, output) = run(args)
        code.assert().describedAs(output).isEqualTo(MigrateMain.USAGE)
        output.assert().contains("Usage:")
    }

    @Test
    fun `convert from files writes the documents`() {
        val out = temp.resolve("out")
        val (code, output) = run("convert --from-files ${export("[${policy("c")}]")} --out $out")
        code.assert().describedAs(output).isEqualTo(MigrateMain.OK)
        output.assert().contains("attachablePolicies: 1")
        Files.isRegularFile(out.resolve(ConvertedDirectory.PLATFORM_FILE)).assert().isTrue()
    }

    @Test
    fun `a root without an expiry is rejected, and the console says how to fix it`() {
        val out = temp.resolve("out")
        val (code, output) = run("convert --from-files ${export()} --out $out --root-enabled --root-id admin")
        code.assert().isEqualTo(MigrateMain.FAILED)
        output.assert().contains("REJECT ROOT_WITHOUT_EXPIRY").contains("pass --root-expires-at")
    }

    @Test
    fun `a root with an expiry and by-owner partition convert`() {
        val out = temp.resolve("out")
        val (code, output) = run(
            "convert --from-files ${export("[${policy("c", tenantId = "acme")}]")} --out $out " +
                "--root-expires-at 2026-12-31T00:00:00Z --partition by-owner",
        )
        code.assert().describedAs(output).isEqualTo(MigrateMain.OK)
        output.assert().contains("semantic change C1d").contains("semantic change C5")
        Files.isRegularFile(out.resolve("tenants/acme.json")).assert().isTrue()
    }

    @Test
    fun `a failure that is not the data's exits with 1`() {
        val (code, output) = run("convert --from-files ${temp.resolve("absent")} --out ${temp.resolve("out")}")
        code.assert().isEqualTo(MigrateMain.FAILED)
        output.assert().contains("Failed:")
        run(
            "publish --to-redis redis://localhost:6379 --in ${temp.resolve("absent")}"
        ).first.assert().isEqualTo(MigrateMain.FAILED)
    }
}
