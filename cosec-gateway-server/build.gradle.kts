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
    kotlin("kapt")
}

kotlin {
    jvmToolchain(17)
}
tasks.jar.configure {
    exclude("application.yaml", "bootstrap.yaml")
    manifest {
        attributes(
            "Implementation-Title" to application.applicationName,
            "Implementation-Version" to archiveVersion,
        )
    }
}
application {
    mainClass.set("me.ahoo.cosec.gateway.server.GatewayServerKt")
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
        "-Dcom.sun.management.jmxremote",
        "-Dcom.sun.management.jmxremote.authenticate=false",
        "-Dcom.sun.management.jmxremote.ssl=false",
        "-Dcom.sun.management.jmxremote.port=5555",
        "-Dspring.cloud.bootstrap.enabled=true",
        "-Dspring.cloud.bootstrap.location=config/bootstrap.yaml",
        "-Dspring.config.location=file:./config/",
    )
}

dependencies {
    implementation(platform(project(":cosec-dependencies")))
    implementation("io.netty:netty-all")
    implementation(project(":cosec-redis"))
    implementation(project(":cosec-webflux"))
    implementation(project(":cosec-gateway"))
    implementation(project(":cosec-opentelemetry"))
    implementation(project(":cosec-spring-boot-starter"))
    implementation(project(":cosec-ip2region"))
    implementation("me.ahoo.cocache:cocache-spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.cloud:spring-cloud-starter-gateway")
    implementation("org.springframework.cloud:spring-cloud-starter-openfeign")
    implementation("org.springframework.cloud:spring-cloud-starter-loadbalancer")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    testImplementation("io.projectreactor:reactor-test")
}
