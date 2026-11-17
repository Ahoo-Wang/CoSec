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

import org.gradle.api.plugins.JavaPluginExtension

// The host the TypeScript client is tested against (typescript/AGENTS.md): a real CoSec 8 application, the starter over
// WebFlux with in-memory CoCache, that `pnpm test:host` starts. Test sources only, never published (verifyUnpublished).
// No Redis: its own test and the client's run on the memory backend.
dependencies {
    testImplementation(project(":spring-boot"))
    testImplementation(project(":control"))
    testImplementation(project(":webflux"))
    testImplementation(project(":cocache"))
    testImplementation("me.ahoo.cocache:cocache-spring-boot-starter")
    testImplementation("org.springframework.boot:spring-boot-starter-webflux")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor")
    testImplementation("tools.jackson.core:jackson-databind")
}

// The host's class path, written for `typescript/scripts/with-host.mjs`, which starts the JVM itself: a process it owns,
// so stopping the test run stops the host (a Gradle `JavaExec` would outlive its client in the daemon).
val testClasspath = project.extensions.getByType<JavaPluginExtension>().sourceSets["test"].runtimeClasspath
val hostClasspath = tasks.register("hostClasspath") {
    val classpath = testClasspath
    val file = layout.buildDirectory.file("client-host.classpath")
    inputs.files(classpath)
    outputs.file(file)
    doLast {
        file.get().asFile.writeText(classpath.files.joinToString(File.pathSeparator) { it.absolutePath })
    }
}
