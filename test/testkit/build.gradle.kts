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

// The policy test kit (RFC 0008 Q11): a user's scenarios against the user's own documents, through the engine level
// of the scenario format the conformance suite also runs. JUnit 5 is part of its API (DynamicTest).
dependencies {
    api(project(":runtime"))
    implementation("tools.jackson.core:jackson-databind")
    api("org.junit.jupiter:junit-jupiter-api")
    // MembershipResolver: memberships from claims and a provider, merged as the pipeline merges them.
    implementation(project(":runtime"))
}

// ConformanceScenariosTest reads the suite's scenarios, from wherever the conformance project sits (RFC 0011).
val conformanceScenarios = project(":conformance").layout.projectDirectory.dir("src/test/resources/scenarios")
tasks.named<Test>("test") {
    inputs.dir(conformanceScenarios)
    systemProperty("cosec.conformance.scenarios", conformanceScenarios.asFile.absolutePath)
}
