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

// The coroutine request pipeline (RFC 0006 §5.1) and the snapshot source, over the policy documents of cosec-document
// (RFC 0013 §3). Jackson is an implementation detail here (RFC 0011 D3); Reactor and Spring never are (§5).
dependencies {
    api(project(":document"))
    api(project(":engine"))
    api(project(":spi"))
    api(project(":annotations"))
    api("io.github.oshai:kotlin-logging-jvm")
    implementation("tools.jackson.core:jackson-databind")
    implementation("tools.jackson.module:jackson-module-kotlin")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core")
    // MdcContextContributor (RFC 0006 §7.5): restores the bridging thread's MDC when the pipeline resumes.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-slf4j")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test")
    // SnapshotColdLoadTest runs loads on Reactor's non-blocking threads, which stand in for the Netty event loop.
    testImplementation("io.projectreactor:reactor-core")
    // HttpPathPatternParityTest compares the engine's path patterns with Spring's.
    testImplementation("org.springframework:spring-web")
    // ProblemSchemaTest validates encoded problems against the generated schema.
    testImplementation("com.networknt:json-schema-validator")
}
