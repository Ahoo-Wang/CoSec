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
plugins {
    alias(libs.plugins.kotlin.spring)
    kotlin("kapt")
}
dependencies {
    kapt(platform(project(":dependencies")))
    api(project(":runtime"))
    api(project(":login"))
    api(project(":jwt"))
    // RFC 0011 D2: the technology modules are on the application's classpath when it adds them; each auto-configuration
    // is guarded by @ConditionalOnClass, and STA-1 (CoSecModuleCheck) fails startup when a feature it turns on, or the
    // web stack it runs on, needs a module that is missing.
    compileOnly(project(":webmvc"))
    testImplementation(project(":webmvc"))
    compileOnly(project(":webflux"))
    // The control plane's service port is a second Reactor Netty server (CTL-58); the application brings its own.
    compileOnly(libs.spring.boot.reactor.netty)
    testImplementation(project(":webflux"))
    compileOnly("org.jetbrains.kotlinx:kotlinx-coroutines-reactor")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor")
    compileOnly(project(":gateway"))
    testImplementation(project(":gateway"))
    // WebAdapterParityTest: the test bindings' filters (PIP-29) are the adapters'.
    testImplementation(project(":testkit"))
    compileOnly(project(":social"))
    testImplementation(project(":social"))
    compileOnly(project(":cosid"))
    testImplementation(project(":cosid"))
    // CoSecCosIdAutoConfigurationTest starts a real CosId runtime through CosId's own auto-configuration.
    testImplementation(libs.cosid.spring.boot.autoconfigure)
    compileOnly(project(":cocache"))
    testImplementation(project(":cocache"))
    compileOnly("org.springframework.boot:spring-boot-data-redis")
    testImplementation("org.springframework.boot:spring-boot-data-redis")
    compileOnly("me.ahoo.cocache:cocache-spring-boot-starter")
    testImplementation("me.ahoo.cocache:cocache-spring-boot-starter")
    compileOnly(project(":opentelemetry"))
    testImplementation(project(":opentelemetry"))
    compileOnly(project(":ip2region"))
    testImplementation(project(":ip2region"))
    compileOnly(project(":micrometer"))
    testImplementation(project(":micrometer"))
    compileOnly("io.micrometer:micrometer-core")
    testImplementation("io.micrometer:micrometer-core")
    compileOnly(project(":openapi"))
    testImplementation(project(":openapi"))
    compileOnly("org.springframework.boot:spring-boot-starter-actuator")
    testImplementation("org.springframework.boot:spring-boot-starter-actuator")
    compileOnly("org.springdoc:springdoc-openapi-starter-common")
    testImplementation("org.springdoc:springdoc-openapi-starter-common")
    testImplementation("org.springdoc:springdoc-openapi-starter-webflux-api")
    compileOnly(project(":control"))
    testImplementation(project(":control"))
    // The control plane on a control-plane host (CTL-77, STA-11): HttpControlService, where cosec.control.service-url names one.
    compileOnly(project(":control-http"))
    testImplementation(project(":control-http"))
    compileOnly(project(":kafka"))
    testImplementation(project(":kafka"))
    compileOnly("org.springframework.boot:spring-boot-starter-kafka")
    testImplementation("org.springframework.boot:spring-boot-starter-kafka")
    api("org.springframework.boot:spring-boot-starter")
    api("org.springframework.boot:spring-boot-starter-jackson")
    kapt("org.springframework.boot:spring-boot-configuration-processor")
    kapt("org.springframework.boot:spring-boot-autoconfigure-processor")
    // SnapshotHealthIndicator: only when the host has actuator.
    compileOnly("org.springframework.boot:spring-boot-health")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-health")
    testImplementation("org.springframework.boot:spring-boot-webflux")
    testImplementation("org.springframework.boot:spring-boot-starter-data-redis")
    testImplementation("me.ahoo.cocache:cocache-test")
    testImplementation("org.springframework.cloud:spring-cloud-gateway-server-webflux")
    // The real-server parity suite (PIP-1, PIP-16): Tomcat, Jetty and Reactor Netty as Spring Boot builds and customizes
    // them, requests sent as raw bytes. Test only.
    testImplementation(libs.spring.boot.tomcat)
    testImplementation(libs.spring.boot.jetty)
    testImplementation(libs.spring.boot.reactor.netty)
    testImplementation(libs.spring.boot.webmvc)
    // SuspendingHandlerContextTest: the holder under Spring's context propagation (round 2 M4).
    testImplementation("io.micrometer:context-propagation")
}

// Every kapt run here is a full one, as on CI. An incremental run hands Spring Boot's configuration processor the stubs
// of the changed files only and reads the other types from compiled classes, which hold no KDoc: a change to one
// properties file dropped the description of every key declared in another (CacheConfiguration's local-max-age), and
// ConfigurationMetadataTest failed until a clean build. Without its cache of the last run kapt cannot run incrementally,
// so the cache is deleted first. (`kapt.incremental.apt=false` would do it too, but the processor then also writes each
// key's initializer as its default, which is wrong for the keys whose default the host decides.) Nor is the output
// cached: a cache entry would keep such an output for every build with the same inputs. The processing takes under a
// second.
tasks.matching { it.name == "kaptKotlin" || it.name == "kaptTestKotlin" }.configureEach {
    outputs.cacheIf("every kapt run here is a full one, and takes under a second") { false }
    val incremental = layout.buildDirectory.dir("tmp/kapt3/incApCache/${if (name == "kaptKotlin") "main" else "test"}")
    doFirst { incremental.get().asFile.deleteRecursively() }
}
