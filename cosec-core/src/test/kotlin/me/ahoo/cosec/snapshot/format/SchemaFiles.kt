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

package me.ahoo.cosec.snapshot.format

import java.io.File
import kotlin.system.exitProcess

/**
 * `generateSchemas` and `verifySchemas`: writes [DocumentFormats] to `schema/`, or with `--verify` fails when the
 * committed files differ from what it would write. A `*.schema.json` the descriptor no longer generates is deleted, or
 * reported.
 */
fun main(args: Array<String>) {
    val directory = File(args.first())
    val verify = "--verify" in args
    val expected = DocumentFormats.files.associate { it.fileName to JsonSchemaWriter.write(it) }
    val stale = mutableListOf<String>()
    expected.forEach { (name, content) ->
        val file = File(directory, name)
        if (!file.isFile || file.readText() != content) {
            stale += name
            if (!verify) {
                file.writeText(content)
            }
        }
    }
    directory.listFiles { file -> file.name.endsWith(".schema.json") && file.name !in expected }.orEmpty().forEach {
        stale += "${it.name} (not generated)"
        if (!verify) {
            it.delete()
        }
    }
    if (verify && stale.isNotEmpty()) {
        System.err.println(
            "Stale JSON schemas in $directory: $stale. Run ./gradlew :cosec-core:generateSchemas and commit the result."
        )
        exitProcess(1)
    }
    println(if (verify) "JSON schemas are up to date." else "Wrote ${stale.size} JSON schema(s) to $directory: $stale")
}
