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

// A service behind the CoSec gateway, in cosec.pipeline.mode=gateway, for the system test (test/system-test): it echoes what
// reached it. Its classpath is what a gateway-mode service needs and nothing more: no cosec-cocache, no control plane, no
// Redis client, as the services behind a real gateway run. Never published (verifyUnpublished); the system-test
// workflow builds its image from installDist.
plugins {
    application
    alias(libs.plugins.kotlin)
    alias(libs.plugins.kotlin.spring)
}

application {
    mainClass.set("me.ahoo.cosec.system.behind.BehindKt")
}

dependencies {
    implementation(platform(project(":dependencies")))
    implementation(project(":spring-boot"))
    implementation(project(":webflux"))
    implementation("org.springframework.boot:spring-boot-starter-webflux")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
}
