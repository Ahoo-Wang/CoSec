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

package me.ahoo.cosec.runtime.contract

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * `ContractFiles` over a temporary directory: its update mode writes and deletes files, which the contract tests
 * themselves only reach when someone runs `updateClientContract`.
 */
class ContractFilesTest {
    @TempDir
    lateinit var root: Path

    private fun writer() = ContractFiles(root, update = true, task = "update")
    private fun checker() = ContractFiles(root, update = false, task = "update")

    @Test
    fun `writing makes the directories and writes pretty JSON ending in a newline`() {
        val files = writer()
        val node = files.objectNode().put("a", 1)
        node.putArray("b").add("x")
        files.check("deep/er/file.json", node)

        val text = root.resolve("deep/er/file.json").readText()
        text.endsWith("}\n").assert().isTrue()
        text.lines().size.assert().describedAs("one member per line").isGreaterThan(2)
        files.parse(text.toByteArray()).assert().isEqualTo(node)
        files.committed("deep/er/file.json").assert().isEqualTo(node)
        checker().check("deep/er/file.json", node)
    }

    @Test
    fun `writing replaces what a file held`() {
        val files = writer()
        files.check("file.json", files.objectNode().put("a", 1))
        files.check("file.json", files.objectNode().put("a", 2))
        files.committed("file.json").get("a").asInt().assert().isEqualTo(2)
    }

    @Test
    fun `writing deletes the json files the code no longer answers, and nothing else`() {
        val files = writer()
        Files.createDirectories(root.resolve("dir"))
        for (name in listOf("keep.json", "stale.json", "notes.md", "data.txt")) {
            root.resolve("dir/$name").writeText("{}")
        }
        Files.createDirectories(root.resolve("dir/nested"))
        root.resolve("dir/nested/inner.json").writeText("{}")
        root.resolve("outside.json").writeText("{}")

        files.only("dir", setOf("keep.json"))

        root.resolve("dir/keep.json").exists().assert().isTrue()
        root.resolve("dir/stale.json").exists().assert().isFalse()
        root.resolve("dir/notes.md").exists().assert().isTrue()
        root.resolve("dir/data.txt").exists().assert().isTrue()
        root.resolve("dir/nested/inner.json").exists().assert().isTrue()
        root.resolve("outside.json").exists().assert().isTrue()
    }

    @Test
    fun `checking fails on a missing file and names the task`() {
        val files = checker()
        val error = assertThrows<AssertionError> { files.check("missing.json", files.objectNode()) }
        error.message.assert().contains("contract/missing.json is missing").contains("./gradlew update")
        root.resolve("missing.json").exists().assert().describedAs("check mode never writes").isFalse()
    }

    @Test
    fun `checking fails on a difference and names the task`() {
        root.resolve("file.json").writeText("""{"a": 1, "b": [1, 2]}""")
        val files = checker()
        files.check("file.json", files.parse("""{"b":[1,2],"a":1}""".toByteArray()))

        val error = assertThrows<AssertionError> { files.check("file.json", files.objectNode().put("a", 2)) }
        error.message.assert().contains("contract/file.json is not what the code answers").contains("./gradlew update")
        root.resolve("file.json").readText().assert().isEqualTo("""{"a": 1, "b": [1, 2]}""")
    }

    @Test
    fun `checking fails on an extra file and on a missing one, and deletes nothing`() {
        Files.createDirectories(root.resolve("dir"))
        root.resolve("dir/keep.json").writeText("{}")
        root.resolve("dir/extra.json").writeText("{}")
        val files = checker()

        val extra = assertThrows<AssertionError> { files.only("dir", setOf("keep.json")) }
        extra.message.assert().contains(
            "contract/dir holds a file the code does not answer"
        ).contains("./gradlew update")
        root.resolve("dir/extra.json").exists().assert().isTrue()

        assertThrows<AssertionError> { files.only("dir", setOf("keep.json", "extra.json", "absent.json")) }
        files.only("dir", setOf("keep.json", "extra.json"))
    }

    @Test
    fun `the default instance finds the repository's contract directory`() {
        // Not update mode (the property is set only by updateClientContract), and the committed README's neighbours.
        ContractFiles().committed("headers.json").get("requestId").assert().isNotNull()
    }
}
