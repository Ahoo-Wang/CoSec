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
    testImplementation(project(":cosec-engine"))
    // MembershipResolver (RFC 0004 §4.5) and Jackson for reading scenarios.
    testImplementation(project(":cosec-core"))
    // PipelineRunner: the coroutine pipeline of RFC 0006.
    testImplementation(project(":cosec-runtime"))
}

val repositoryRoot = rootProject.layout.projectDirectory

/*
 * RFC 0008 §3.4: every rule that is not pending has a positive and a negative scenario, every scenario names rules of
 * the registry, and every referenced property test exists. Prints the pending rules.
 */
val verifyRuleCoverage = tasks.register<JavaExec>("verifyRuleCoverage") {
    group = "verification"
    description = "Fails when a rule lacks a positive or negative scenario, or a referenced test does not exist."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("me.ahoo.cosec.conformance.RuleCoverageKt")
    args(repositoryRoot.asFile.absolutePath)
    inputs.dir("src/test/resources")
    inputs.files(sourceSets["test"].runtimeClasspath)
    // The referenced tests live in other modules; their sources are inputs too.
    inputs.files(
        fileTree(repositoryRoot) {
            include("*/src/test/**/*.kt")
            exclude("conformance/**")
        }
    )
    val marker = layout.buildDirectory.file("verifyRuleCoverage/ok")
    outputs.file(marker)
    doLast { marker.get().asFile.apply { parentFile.mkdirs() }.writeText("") }
}

tasks.named("check") {
    dependsOn(verifyRuleCoverage)
}
