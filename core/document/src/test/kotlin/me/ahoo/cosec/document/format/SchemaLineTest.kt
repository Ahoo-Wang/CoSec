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

package me.ahoo.cosec.document.format

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Holds the schema files to their release line: `DocumentFormats.SCHEMA_LINE` is the major and minor of the project's
 * version, and every directory of `schema/` is the line it says, in the identity its files carry.
 */
class SchemaLineTest {
    private val schemaDirectory = File(checkNotNull(System.getProperty("cosec.repository.root")), "schema")

    @Test
    fun theLineIsTheMajorAndMinorOfTheVersion() {
        val version = checkNotNull(System.getProperty("cosec.project.version"))
        val line = version.split('.').take(2).joinToString(".")
        DocumentFormats.SCHEMA_LINE.assert().describedAs(
            "The project is at $version, so the schema line is $line, not ${DocumentFormats.SCHEMA_LINE}. At the bump " +
                "to a new line: set DocumentFormats.SCHEMA_LINE to it, run ./gradlew :document:generateSchemas, which " +
                "writes schema/$line/, and commit that directory. Leave schema/${DocumentFormats.SCHEMA_LINE}/ as it " +
                "is: generateSchemas and verifySchemas only read and write the directory of the current line, so the " +
                "old one is never rewritten, and the documentation site keeps serving it at its URL."
        ).isEqualTo(line)
    }

    @Test
    fun everyLineDirectoryNamesItselfInItsFiles() {
        val lines = schemaDirectory.listFiles { file -> file.isDirectory }.orEmpty()
        lines.map { it.name }.assert().contains(DocumentFormats.SCHEMA_LINE)
        for (line in lines) {
            val files = line.listFiles { file -> file.name.endsWith(".schema.json") }.orEmpty()
            files.assert().describedAs("schema/${line.name}/ holds schema files").isNotEmpty()
            for (file in files) {
                file.readText().assert().describedAs("$file names its own line in its \$id")
                    .contains("\"\$id\": \"https://cosec.ahoo.me/schema/${line.name}/${file.name}\"")
            }
        }
        schemaDirectory.listFiles { file -> file.isFile }.orEmpty().assert()
            .describedAs("schema/ holds line directories only: a schema file belongs to a line").isEmpty()
    }
}
