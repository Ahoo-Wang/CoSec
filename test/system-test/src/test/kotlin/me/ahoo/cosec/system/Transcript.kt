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

package me.ahoo.cosec.system

import org.junit.jupiter.api.extension.AfterEachCallback
import org.junit.jupiter.api.extension.BeforeEachCallback
import org.junit.jupiter.api.extension.ExtensionContext
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/**
 * What a scenario sent and received, one file per test under `build/system/transcripts/<class>/`, which the workflow
 * uploads: a failure, or a wrong answer kept as evidence, is read there. Scenarios run one at a time.
 */
object Transcript {
    private var file: Path? = null

    fun start(file: Path) {
        Files.createDirectories(file.parent)
        Files.writeString(file, "")
        this.file = file
    }

    fun stop() {
        file = null
    }

    fun note(line: String) {
        val target = file ?: return
        Files.writeString(target, "${Instant.now()} $line\n", Charsets.UTF_8, java.nio.file.StandardOpenOption.APPEND)
    }
}

/** Opens each test's transcript; registered on every scenario class with `@ExtendWith`. */
class TranscriptExtension : BeforeEachCallback, AfterEachCallback {
    override fun beforeEach(context: ExtensionContext) {
        val name = context.requiredTestMethod.name.replace(Regex("[^A-Za-z0-9._-]+"), "-").trim('-')
        Transcript.start(SystemTopology.transcripts.resolve(context.requiredTestClass.simpleName).resolve("$name.log"))
    }

    override fun afterEach(context: ExtensionContext) {
        context.executionException.ifPresent { Transcript.note("FAILED: $it") }
        Transcript.stop()
    }
}
