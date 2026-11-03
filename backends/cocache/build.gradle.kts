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

dependencies {
    api(project(":spi"))
    api("me.ahoo.cocache:cocache-core")
    api("me.ahoo.cocache:cocache-codec-jackson")
    api("me.ahoo.cocache:cocache-redis")
    api("org.springframework.data:spring-data-redis")
    // The non-blocking reads behind the suspend SPIs of RFC 0006 (RevocationReader, MembershipProvider) await
    // CoCache's async view.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core")
    // CoCacheControlStore implements the control plane's ControlStore (RFC 0007 §5.1). Compile-only, so an application
    // that reads snapshots without the control plane does not pull it in; the control plane brings it with it.
    compileOnly(project(":control"))
    // The shadow stores implement runtime's shadow ports (RFC 0007 §6.4), and the stored formats are written with
    // runtime's one mapper (CoSecJson, RFC 0011 D3), which cocache calls at run time: implementation, not compile-only.
    implementation(project(":runtime"))
    testImplementation(project(":control"))
    testImplementation("me.ahoo.cocache:cocache-test")
    // MembershipCacheFailureTest resolves through the pipeline's MembershipResolver.
    testImplementation("io.lettuce:lettuce-core")
    testImplementation("io.projectreactor:reactor-test")
    // RFC 0003 §6: the snapshot store must not block a non-blocking caller.
    testImplementation("io.projectreactor.tools:blockhound")
}

tasks.withType<Test>().configureEach {
    // BlockHound instruments JDK methods, which JDK 13+ allows only with this flag.
    jvmArgs("-XX:+AllowRedefinitionToAddDeleteMethods")
    // Opt-in Redis targets of the snapshot tests (RedisTarget): a change reruns the tests instead of reusing results.
    listOf("COSEC_TEST_VALKEY_PORT", "COSEC_TEST_REDIS_CLUSTER_NODES").forEach { name ->
        inputs.property(name, providers.environmentVariable(name).orElse(""))
    }
}
