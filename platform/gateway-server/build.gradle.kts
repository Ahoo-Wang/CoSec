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
    application
    alias(libs.plugins.kotlin)
    alias(libs.plugins.kotlin.spring)
}

kotlin {
    jvmToolchain(21)
}
// The image's defaults are src/main/resources/application.yaml, in the jar. A ./config/application.yaml the operator
// mounts (Spring Boot's default locations include file:./config/, the start script's working directory) is read in
// addition and wins key by key: replacing the file never drops a default (UB-21).
tasks.jar.configure {
    manifest {
        attributes(
            "Implementation-Title" to application.applicationName,
            "Implementation-Version" to archiveVersion,
        )
    }
}
application {
    mainClass.set("me.ahoo.cosec.gateway.server.GatewayServerKt")
    // The project is :gateway-server (RFC 0011 D1); the distribution, its script and the image paths keep the old name.
    applicationName = "cosec-gateway-server"
    applicationDefaultJvmArgs = listOf(
        "-Xms2048M",
        "-Xmx2048M",
        "-XX:MaxMetaspaceSize=256M",
        "-XX:MaxDirectMemorySize=512M",
        "-Xss1m",
        "-server",
        "-XX:+UseZGC",
        "-Xlog:gc*:file=logs/$applicationName-gc.log:time,tags:filecount=10,filesize=32M",
        "-XX:+HeapDumpOnOutOfMemoryError",
        "-XX:HeapDumpPath=data",
        // No JMX agent (UB-2): whoever wants one sets it in JAVA_OPTS, bound to loopback (operations/deployment).
    )
}

// `run` reads what the image reads: from src/dist, as the distribution's home, its ./config/ and ./logs/ are the image's.
tasks.named<JavaExec>("run") {
    workingDir = file("src/dist")
}

dependencies {
    implementation(platform(project(":dependencies")))
    implementation("io.netty:netty-all")
    implementation(project(":cocache"))
    implementation(project(":webflux"))
    implementation(project(":gateway"))
    implementation(project(":opentelemetry"))
    implementation(project(":spring-boot"))
    implementation(project(":kafka"))
    implementation("org.springframework.boot:spring-boot-starter-kafka")
    implementation(project(":ip2region"))
    // cosec.authorization, cosec.snapshot.* and the listener meters, scraped from the management port (UB-8, UB-2).
    implementation(project(":micrometer"))
    implementation(libs.micrometer.registry.prometheus)
    implementation("me.ahoo.cocache:cocache-spring-boot-starter")
    implementation("com.github.ben-manes.caffeine:caffeine")
    implementation("org.springframework.boot:spring-boot-starter-cache")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.cloud:spring-cloud-starter-gateway-server-webflux")
    implementation("org.springframework.cloud:spring-cloud-starter-loadbalancer")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    testImplementation("io.projectreactor:reactor-test")
}
