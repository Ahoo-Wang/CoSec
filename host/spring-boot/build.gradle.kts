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
    testImplementation(project(":webflux"))
    compileOnly("org.jetbrains.kotlinx:kotlinx-coroutines-reactor")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor")
    compileOnly(project(":gateway"))
    testImplementation(project(":gateway"))
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
    compileOnly(project(":control"))
    testImplementation(project(":control"))
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
}
