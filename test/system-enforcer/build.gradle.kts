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

// A service that enforces on its own (cosec.pipeline.mode=enforce) on Spring MVC and Tomcat, for the system test
// (test/system-test): the servlet stack in a deployed topology. It decides with the documents on the shared Redis and
// verifies CoSec's tokens itself, for the app `orders`. Never published (verifyUnpublished); the system-test workflow
// builds its image from installDist.
plugins {
    application
    alias(libs.plugins.kotlin)
    alias(libs.plugins.kotlin.spring)
}

application {
    mainClass.set("me.ahoo.cosec.system.enforcer.EnforcerKt")
}

dependencies {
    implementation(platform(project(":dependencies")))
    implementation(project(":spring-boot"))
    implementation(project(":webmvc"))
    implementation(project(":cocache"))
    // The decision timer `cosec.authorization` on the actuator's metrics endpoint (scenario 10).
    implementation(project(":micrometer"))
    implementation("me.ahoo.cocache:cocache-spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
}
