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
    // The documents, the compiler, the engine; MembershipResolver merges memberships as the pipeline does.
    api(project(":runtime"))
    // Scenarios are read with Jackson, which no public signature names (JacksonSignatureTest): an implementation detail.
    implementation("tools.jackson.core:jackson-databind")
    api("org.junit.jupiter:junit-jupiter-api")
    // The test bindings of the two web stacks (PIP-29), in their own packages. compileOnlyApi: their signatures name the
    // stacks' types, so a consumer compiles against them (ApiVariantTest), but nothing of a web stack is brought at run
    // time: an application that tests a WebFlux or Spring MVC handler has that adapter already.
    compileOnlyApi(project(":webflux"))
    compileOnlyApi(project(":webmvc"))
    compileOnly("org.jetbrains.kotlinx:kotlinx-coroutines-reactor")
    // PermittedRequestTest issues real tokens with CoSec's own login and verifier, and switches with them.
    testImplementation(project(":jwt"))
    // TestingYourApplicationTest: the documented way to test an application, on each stack.
    testImplementation(project(":webflux"))
    testImplementation(project(":webmvc"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor")
    testImplementation("org.springframework:spring-test")
    testImplementation("io.projectreactor:reactor-test")
}

// ConformanceScenariosTest reads the suite's scenarios, from wherever the conformance project sits (RFC 0011).
val conformanceScenarios = project(":conformance").layout.projectDirectory.dir("src/test/resources/scenarios")
tasks.named<Test>("test") {
    inputs.dir(conformanceScenarios)
    systemProperty("cosec.conformance.scenarios", conformanceScenarios.asFile.absolutePath)
}
