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

// The coroutine request pipeline (RFC 0006 §5.1). cosec-core still brings Reactor onto the classpath (SnapshotPublisher
// returns Mono until phase 6), so RuntimeBoundaryTest guards what the code references, not the classpath (RFC 0006 Q6).
dependencies {
    api(project(":cosec-engine"))
    api(project(":cosec-core"))
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core")
    // MdcContextContributor (RFC 0006 §7.5): restores the bridging thread's MDC when the pipeline resumes.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-slf4j")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test")
    testImplementation("com.tngtech.archunit:archunit-junit5")
    // ProblemSchemaTest validates encoded problems against schema/problem.schema.json, as cosec-core's
    // SchemaConformanceTest does for the formats CoSec reads.
    testImplementation("com.networknt:json-schema-validator")
    // OneDecisionPathTest checks the adapters' classes: they must never see a Decision or construct an Outcome.
    testRuntimeOnly(project(":cosec-webflux"))
    testRuntimeOnly(project(":cosec-webmvc"))
    testRuntimeOnly(project(":cosec-gateway"))
}
