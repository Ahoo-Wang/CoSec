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

// The one-time 7.x to 8.0 data converter (RFC 0004 §8, RFC 0008 §4.1): `convert` and `publish`, as a library and as a
// jar with a main class. No application depends on it; the starter never does.
plugins {
    application
}

dependencies {
    // 7.x data is read through 7.0's caches (CoSec7xReader); 8.0 documents are written through the snapshot cache.
    api(project(":cosec-cocache"))
    implementation("io.lettuce:lettuce-core")
    // convert decodes 7.x path actions with Spring's PathPatternParser, which cosec-core leaves to the application
    // (approved by the maintainer, 2026-10-26).
    runtimeOnly("org.springframework:spring-web")
    // Converted documents are checked by deciding requests with them through the 8.0 pipeline.
    testImplementation(project(":cosec-runtime"))
}

application {
    mainClass.set("me.ahoo.cosec.migrate.MigrateMain")
    applicationName = "cosec-migrate"
}

tasks.jar {
    manifest {
        attributes("Main-Class" to "me.ahoo.cosec.migrate.MigrateMain")
    }
}
