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
    implementation("tools.jackson.core:jackson-databind")
    implementation("tools.jackson.module:jackson-module-kotlin")
    // Guava: the modules whose main code uses it declare it; the root build adds it to none.
    implementation("com.google.guava:guava")
    // 7.x data is read through 7.0's caches (CoSec7xReader); 8.0 documents are written through the snapshot cache.
    api(project(":cocache"))
    // publish and bootstrap write through the control plane's store, so the revision chain starts with them (RFC 0007 §5.9).
    implementation(project(":control"))
    implementation("io.lettuce:lettuce-core")
    // convert decodes 7.x data with 7.x's own model, serializers and matchers (me.ahoo.cosec.migrate.v7, RFC 0008 §4.6
    // L8): path actions through Spring's PathPatternParser, SpEL and OGNL conditions through their engines, so a policy
    // 7.x could not load is rejected (approved by the maintainer, 2026-10-26). Template values (`#{principal.id}`)
    // decode through SpEL at convert time only: the AST holds an attribute reference.
    implementation("org.springframework:spring-web")
    implementation("org.springframework:spring-expression")
    // Compile-only, as it was in cosec-core: convert never ran OGNL, and an `ognl` condition is rejected untranslated.
    compileOnly("ognl:ognl")
    // Converted documents are checked by deciding requests with them through the 8.0 pipeline.
    testImplementation(project(":runtime"))
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
