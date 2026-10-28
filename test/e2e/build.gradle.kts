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

// The end-to-end slice of the 8.0 path (RFC 0008 §4.4.3): snapshot documents in real Redis, read through CoCache by the
// snapshot source, compiled, decided by the flat engine, through the coroutine pipeline. Test and benchmark sources
// only, never published (verifyUnpublished). Needs Redis on localhost:6379, as cosec-cocache's tests do.
dependencies {
    testImplementation(project(":runtime"))
    testImplementation(project(":cocache"))
    // The slice is the starter's 8.0 wiring (RFC 0008 S1): its snapshot and pipeline auto-configuration over CoCache.
    testImplementation(project(":spring-boot"))
    testImplementation("org.springframework.boot:spring-boot-data-redis")
    testImplementation("me.ahoo.cocache:cocache-spring-boot-starter")
    testImplementation("io.lettuce:lettuce-core")
    // The adapter through the slice (RFC 0006 PR-7): the WebFlux filter, driven by WebTestClient.
    testImplementation(project(":webflux"))
    testImplementation("org.springframework:spring-test")
    // No blocking on the event loop (AGENTS.md): the warm path is checked on a watched thread.
    testImplementation("io.projectreactor.tools:blockhound")
}

tasks.withType<Test>().configureEach {
    // BlockHound instruments JDK methods, which JDK 13+ allows only with this flag.
    jvmArgs("-XX:+AllowRedefinitionToAddDeleteMethods")
}

// The benchmarks drive the same slice the tests assemble.
configure<me.champeau.jmh.JmhParameters> {
    includeTests.set(true)
}
