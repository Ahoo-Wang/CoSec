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
java {
    registerFeature("webmvcSupport") {
        usingSourceSet(sourceSets[SourceSet.MAIN_SOURCE_SET_NAME])
        capability(group.toString(), "webmvc-support", version.toString())
    }
    registerFeature("webfluxSupport") {
        usingSourceSet(sourceSets[SourceSet.MAIN_SOURCE_SET_NAME])
        capability(group.toString(), "webflux-support", version.toString())
    }
    registerFeature("gatewaySupport") {
        usingSourceSet(sourceSets[SourceSet.MAIN_SOURCE_SET_NAME])
        capability(group.toString(), "gateway-support", version.toString())
    }
    registerFeature("oauthSupport") {
        usingSourceSet(sourceSets[SourceSet.MAIN_SOURCE_SET_NAME])
        capability(group.toString(), "oauth-support", version.toString())
    }
    registerFeature("cacheSupport") {
        usingSourceSet(sourceSets[SourceSet.MAIN_SOURCE_SET_NAME])
        capability(group.toString(), "cache-support", version.toString())
    }
    registerFeature("ip2regionSupport") {
        usingSourceSet(sourceSets[SourceSet.MAIN_SOURCE_SET_NAME])
        capability(group.toString(), "ip2region-support", version.toString())
    }
    registerFeature("opentelemetrySupport") {
        usingSourceSet(sourceSets[SourceSet.MAIN_SOURCE_SET_NAME])
        capability(group.toString(), "opentelemetry-support", version.toString())
    }
    registerFeature("openapiSupport") {
        usingSourceSet(sourceSets[SourceSet.MAIN_SOURCE_SET_NAME])
        capability(group.toString(), "openapi-support", version.toString())
    }
}
dependencies {
    kapt(platform(project(":cosec-dependencies")))
    api(project(":cosec-core"))
    api(project(":cosec-jwt"))
    "webmvcSupportImplementation"(project(":cosec-webmvc"))
    "webfluxSupportImplementation"(project(":cosec-webflux"))
    "gatewaySupportImplementation"(project(":cosec-gateway"))
    "oauthSupportImplementation"(project(":cosec-social"))
    "cacheSupportImplementation"(project(":cosec-cocache"))
    "cacheSupportImplementation"("me.ahoo.cocache:cocache-spring-boot-starter")
    "opentelemetrySupportImplementation"(project(":cosec-opentelemetry"))
    "ip2regionSupportImplementation"(project(":cosec-ip2region"))
    "openapiSupportImplementation"(project(":cosec-openapi"))
    "openapiSupportImplementation"("org.springframework.boot:spring-boot-starter-actuator")
    "openapiSupportImplementation"("org.springdoc:springdoc-openapi-starter-common")
    api("org.springframework.boot:spring-boot-starter")
    kapt("org.springframework.boot:spring-boot-configuration-processor")
    kapt("org.springframework.boot:spring-boot-autoconfigure-processor")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-data-redis")
    testImplementation("me.ahoo.cosid:cosid-test")
    testImplementation("me.ahoo.cocache:cocache-spring-boot-starter")
}
