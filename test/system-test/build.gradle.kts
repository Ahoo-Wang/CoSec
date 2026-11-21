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

// The system test (RFC 0008 §3.9, before RC1): the gateway image built from the commit, the services of test/system-*,
// and Redis, run by compose.yaml, driven over the network only. `check` runs only the harness's own unit tests, which need
// no Docker; the scenarios, tagged `system`, run in `systemTest` against a running stack, as the system-test workflow
// does. Test sources only, never published (verifyUnpublished).
dependencies {
    testImplementation("tools.jackson.core:jackson-databind")
}

val contract = rootProject.layout.projectDirectory.dir("contract").asFile.absolutePath

tasks.test {
    useJUnitPlatform {
        excludeTags("system")
    }
    systemProperty("cosec.system.contract", contract)
    inputs.dir(contract)
}
val documents = layout.projectDirectory.dir("documents").asFile.absolutePath
val transcripts = layout.buildDirectory.dir("system/transcripts").get().asFile.absolutePath
val testSourceSet = the<SourceSetContainer>()["test"]

tasks.register<Test>("systemTest") {
    group = "verification"
    description = "Runs the system scenarios against the stack of compose.yaml and the file of the leg (see test/system-test/README.md)."
    testClassesDirs = testSourceSet.output.classesDirs
    classpath = testSourceSet.runtimeClasspath
    // Leg 1 (-PsystemLeg=control-host, the default) runs every scenario but the embedded-only ones; leg 2 (embedded) the shared
    // control scenarios, and the ones that only the embedded deployment has (README.md, "Two deployments").
    val embedded = providers.gradleProperty("systemLeg").orNull == "embedded"
    useJUnitPlatform {
        if (embedded) {
            includeTags("control", "embedded")
        } else {
            includeTags("system")
            excludeTags("embedded")
        }
    }
    // The cold start runs first, on an empty Redis; every other scenario after it, in any order (each its own tenant).
    systemProperty("junit.jupiter.testclass.order.default", "org.junit.jupiter.api.ClassOrderer\$OrderAnnotation")
    systemProperty("cosec.system.contract", contract)
    systemProperty("cosec.system.documents", documents)
    systemProperty("cosec.system.transcripts", transcripts)
    // A run is a run of the stack: never up to date, never from the cache, never retried.
    outputs.upToDateWhen { false }
    outputs.cacheIf { false }
}
