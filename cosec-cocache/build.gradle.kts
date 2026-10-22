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
    api(project(":cosec-core"))
    api("me.ahoo.cocache:cocache-core")
    api("me.ahoo.cocache:cocache-codec-jackson")
    api("me.ahoo.cocache:cocache-redis")
    api("org.springframework.data:spring-data-redis")
    testImplementation("me.ahoo.cocache:cocache-test")
    testImplementation("io.lettuce:lettuce-core")
    testImplementation("me.ahoo.cosid:cosid-core")
    testImplementation("me.ahoo.cosid:cosid-test")
    testImplementation("io.projectreactor:reactor-test")
    // P5 against the real repositories: the generator and the PolicySet comparison (RFC 0003 §8.4).
    testImplementation(testFixtures(project(":cosec-core")))
    // RFC 0003 §6: the snapshot store must not block a non-blocking caller.
    testImplementation("io.projectreactor.tools:blockhound")
    // cosec-core matches paths with Spring's PathPattern, which it leaves to the application.
    jmh("org.springframework:spring-web")
    // The snapshot side assembles cosec-core's internal snapshot authorization (RFC 0003 PR-5 wires it).
    jmh(testFixtures(project(":cosec-core")))
}

tasks.withType<Test>().configureEach {
    // BlockHound instruments JDK methods, which JDK 13+ allows only with this flag.
    jvmArgs("-XX:+AllowRedefinitionToAddDeleteMethods")
}

/*
 * RFC 0003 §4.7: converts the 7.x policy data under -PkeyPrefix in -PredisUrl into snapshot documents. For development
 * deployments, the gate and sizing; lives in the test source set and is never published. Not part of check.
 */
tasks.register<JavaExec>("snapshotConvert") {
    group = "application"
    description = "Converts the 7.x policy data in -PredisUrl (-PkeyPrefix, -PdryRun, -Pinstances, -PrevalidateInterval)."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("me.ahoo.cosec.cache.snapshot.SnapshotConvert")
    listOf("redisUrl", "keyPrefix", "dryRun", "instances", "revalidateInterval").forEach { name ->
        providers.gradleProperty(name).orNull?.let { systemProperty("cosec.snapshot.convert.$name", it) }
    }
}
