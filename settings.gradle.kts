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

rootProject.name = "CoSec"

include(":cosec-bom")
include(":cosec-dependencies")
include(":cosec-api")
include(":cosec-core")
include(":cosec-jwt")
include(":cosec-redis")
include(":cosec-oauth")
include(":cosec-webmvc")
include(":cosec-webflux")
include(":cosec-spring-boot-starter")
include(":cosec-gateway")
include(":cosec-gateway-server")
include(":cosec-opentelemetry")
include(":cosec-ip2region")
include(":code-coverage-report")

pluginManagement {
    plugins {
        id("io.gitlab.arturbosch.detekt") version "1.23.0" apply false
        kotlin("jvm") version "1.8.21" apply false
        kotlin("plugin.spring") version "1.8.21" apply false
        id("org.jetbrains.dokka") version "1.8.10" apply false
        id("me.champeau.jmh") version "0.7.1" apply false
        id("io.github.gradle-nexus.publish-plugin") version "1.3.0" apply false
    }
}

