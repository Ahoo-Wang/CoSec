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

// The spec conformance suite (RFC 0008 §3): the rule registry, hand-written scenarios and their runners. Test sources
// only, never published (verifyUnpublished). The dependency on control/ comes with its runner.
dependencies {
    testImplementation(project(":engine"))
    // The grant analysis of RFC 0007 §7.4, run by GrantRunner.
    testImplementation(project(":control"))
    // MembershipResolver (RFC 0004 §4.5) and Jackson for reading scenarios.
    testImplementation(project(":core"))
    // PipelineRunner: the coroutine pipeline of RFC 0006.
    testImplementation(project(":runtime"))
    // The engine level of the scenario format, and its reading, shared with users.
    testImplementation(project(":testkit"))
    // PIP-10 through a gateway host's own filters: the identity strip, the pipeline's filter and the last hop.
    testImplementation(project(":gateway"))
    testImplementation(project(":webflux"))
    testImplementation("org.springframework.cloud:spring-cloud-gateway-server-webflux")
    testImplementation("org.springframework:spring-test")
}

val repositoryRoot = rootProject.layout.projectDirectory

/*
 * RFC 0011: the projects by name, `name=directory;…`, for the rule guards (`project:Class#test`). Computed here, so no
 * code assumes where a module sits.
 */
val projectDirectories = rootProject.subprojects.joinToString(";") { "${it.name}=${it.projectDir.absolutePath}" }
val testSources = rootProject.subprojects.filter { it != project }.map { it.layout.projectDirectory.dir("src/test") }

tasks.named<Test>("test") {
    systemProperty("cosec.projects", projectDirectories)
}

/*
 * RFC 0008 §3.4: every rule that is not pending has a positive and a negative scenario, every scenario names rules of
 * the registry, and every referenced property test exists. Prints the pending rules.
 */
val verifyRuleCoverage = tasks.register<JavaExec>("verifyRuleCoverage") {
    group = "verification"
    description = "Fails when a rule lacks a positive or negative scenario, or a referenced test does not exist."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("me.ahoo.cosec.conformance.RuleCoverageKt")
    args(layout.projectDirectory.dir("src/test/resources").asFile.absolutePath, projectDirectories)
    inputs.dir("src/test/resources")
    inputs.files(sourceSets["test"].runtimeClasspath)
    // The referenced tests live in other modules; their sources are inputs too.
    inputs.files(testSources.map { dir -> fileTree(dir) { include("**/*.kt") } })
    val marker = layout.buildDirectory.file("verifyRuleCoverage/ok")
    outputs.file(marker)
    doLast { marker.get().asFile.apply { parentFile.mkdirs() }.writeText("") }
}

/*
 * RFC 0008 §3.7: applies each patch of test/conformance/mutants to HEAD in a temporary git worktree and asserts that at
 * least one of the tests it names fails. Manual (each mutant rebuilds), run before S1: -Pmutants=a,b runs only those.
 */
tasks.register<JavaExec>("mutationCheck") {
    group = "verification"
    description = "Fails when a mutant of test/conformance/mutants survives its tests, or no longer applies or compiles."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("me.ahoo.cosec.conformance.MutationCheckKt")
    args(
        listOfNotNull(
            repositoryRoot.asFile.absolutePath,
            layout.projectDirectory.dir("mutants").asFile.absolutePath,
            providers.gradleProperty("mutants").orNull,
        )
    )
}

tasks.named("check") {
    dependsOn(verifyRuleCoverage)
}
