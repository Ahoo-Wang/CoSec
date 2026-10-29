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

// RFC 0011 §5: the architecture rules, in one place. Test sources only, never published. Its test classpath holds every
// module, so a rule sees all of them; the project map (`cosec.projects`) gives the rules that read sources or build files
// where each project sits, by name.
/** The library modules the rules analyze: the classpath the starter's tests had, where these rules lived before. */
val analyzed = listOf(
    "annotations", "api", "model", "engine", "spi", "runtime", "control",
    "cocache", "kafka", "webflux", "webmvc", "gateway", "openapi",
    "jwt", "social", "login", "ip2region", "micrometer", "opentelemetry", "spring-boot",
)

dependencies {
    testImplementation("com.tngtech.archunit:archunit-junit5")
    // The Jackson rule reads Kotlin visibility, which the bytecode does not carry for internal members.
    testImplementation("org.jetbrains.kotlin:kotlin-reflect")
    analyzed.forEach { testImplementation(project(":$it")) }
}

val projectDirectories = rootProject.subprojects.joinToString(";") { "${it.name}=${it.projectDir.absolutePath}" }
// Each project's declared and resolved dependencies (the root build's architectureFacts), for the dependency rules.
val projectFacts = rootProject.subprojects.joinToString(";") {
    "${it.name}=${it.layout.buildDirectory.file("architecture/facts.txt").get().asFile.absolutePath}"
}

@Suppress("UNCHECKED_CAST")
val coveredProjects = (rootProject.ext.get("libraryProjects") as Iterable<Project>).joinToString(";") { it.name }

tasks.named<Test>("test") {
    systemProperty("cosec.projects", projectDirectories)
    systemProperty("cosec.facts", projectFacts)
    dependsOn(provider { rootProject.subprojects.mapNotNull { it.tasks.findByName("architectureFacts") } })
    systemProperty("cosec.coverage.projects", coveredProjects)
    systemProperty("cosec.repository.root", rootProject.layout.projectDirectory.asFile.absolutePath)
    // The rules read build scripts, the catalog, settings, CI files and the extension points page, and run CI's plan:
    // they are inputs.
    inputs.files(rootProject.fileTree(rootProject.layout.projectDirectory) {
        include(
            "settings.gradle.kts", "**/build.gradle.kts", "gradle/libs.versions.toml", ".github/**/*.yml",
            "scripts/ci/**", "codecov.yml", "wiki/architecture/extending.md",
        )
        exclude("**/build/**", "**/node_modules/**")
    })
}
