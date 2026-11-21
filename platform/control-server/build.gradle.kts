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

// The control plane as an image (cosec-control-server): the starter with the control plane and nothing else. It verifies
// no token and issues none: it believes the CoSec-Identity header of whoever can reach it, under the operator's statement
// of what can (cosec.pipeline.caller=forwarded-identity, STA-9). Never published; docker-deploy.yml builds its image.
plugins {
    application
    alias(libs.plugins.kotlin)
    alias(libs.plugins.kotlin.spring)
}

kotlin {
    jvmToolchain(21)
}
// The image's defaults are src/main/resources/application.yaml, in the jar. A ./config/application.yaml the operator
// mounts (Spring Boot's default locations include file:./config/, the start script's working directory) is read in
// addition and wins key by key: replacing the file never drops a default.
tasks.jar.configure {
    manifest {
        attributes(
            "Implementation-Title" to application.applicationName,
            "Implementation-Version" to archiveVersion,
        )
    }
}
application {
    mainClass.set("me.ahoo.cosec.control.server.ControlServerKt")
    applicationName = "cosec-control-server"
    // The gateway's JVM arguments with a smaller starting heap: the control server holds two snapshot sources (its
    // pipeline's and the control plane's own), so it keeps the gateway's ceiling. No JMX agent: whoever wants one sets it in
    // JAVA_OPTS, bound to loopback.
    applicationDefaultJvmArgs = listOf(
        "-Xms512M",
        "-Xmx2048M",
        "-XX:MaxMetaspaceSize=256M",
        "-XX:MaxDirectMemorySize=512M",
        "-Xss1m",
        "-server",
        "-XX:+UseZGC",
        "-Xlog:gc*:file=logs/$applicationName-gc.log:time,tags:filecount=10,filesize=32M",
        "-XX:+HeapDumpOnOutOfMemoryError",
        "-XX:HeapDumpPath=data",
    )
}

// `run` reads what the image reads: from src/dist, as the distribution's home, its ./config/ and ./logs/ are the image's.
tasks.named<JavaExec>("run") {
    workingDir = file("src/dist")
}

dependencies {
    implementation(platform(project(":dependencies")))
    implementation(project(":spring-boot"))
    implementation(project(":webflux"))
    implementation(project(":cocache"))
    implementation(project(":control"))
    // The modules the gateway image has, off by default where it is: a change must compile on every instance that will
    // install it, so what contributes to compilation there is here too (ext.* attributes: ip2region).
    implementation(project(":kafka"))
    implementation("org.springframework.boot:spring-boot-starter-kafka")
    implementation(project(":ip2region"))
    implementation(project(":opentelemetry"))
    implementation(project(":micrometer"))
    implementation(libs.micrometer.registry.prometheus)
    implementation("me.ahoo.cocache:cocache-spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-webflux")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor")
    testImplementation(project(":testkit"))
    testImplementation(project(":jwt"))
    testImplementation(project(":login"))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("io.projectreactor:reactor-test")
}
