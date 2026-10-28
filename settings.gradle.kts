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
include(":cosec-model")
include(":cosec-engine")
include(":cosec-runtime")
include(":cosec-control")
include(":cosec-migrate")
include(":cosec-testkit")
// Directories drop the cosec- prefix; project paths and artifact ids keep it.
project(":cosec-model").projectDir = file("model")
project(":cosec-engine").projectDir = file("engine")
project(":cosec-runtime").projectDir = file("runtime")
project(":cosec-control").projectDir = file("control")
project(":cosec-migrate").projectDir = file("migrate")
project(":cosec-testkit").projectDir = file("testkit")
include(":cosec-core")
include(":cosec-jwt")
include(":cosec-cocache")
include(":cosec-social")
include(":cosec-webmvc")
include(":cosec-webflux")
include(":cosec-spring-boot-starter")
include(":cosec-gateway")
include(":cosec-gateway-server")
include(":cosec-opentelemetry")
include(":cosec-micrometer")
include(":cosec-ip2region")
include(":code-coverage-report")
include(":cosec-openapi")
include(":cosec-kafka")
// Test-only (RFC 0008 §3.1): never published, so no cosec- prefix.
include(":conformance")
// Test-only (RFC 0008 §4.4.3): the end-to-end slice of the 8.0 path, against real Redis.
include(":e2e")

// The other modules too: the directory is the module name without cosec-.
project(":cosec-api").projectDir = file("api")
project(":cosec-bom").projectDir = file("bom")
project(":cosec-cocache").projectDir = file("cocache")
project(":cosec-core").projectDir = file("core")
project(":cosec-dependencies").projectDir = file("dependencies")
project(":cosec-gateway").projectDir = file("gateway")
project(":cosec-gateway-server").projectDir = file("gateway-server")
project(":cosec-ip2region").projectDir = file("ip2region")
project(":cosec-jwt").projectDir = file("jwt")
project(":cosec-kafka").projectDir = file("kafka")
project(":cosec-micrometer").projectDir = file("micrometer")
project(":cosec-openapi").projectDir = file("openapi")
project(":cosec-opentelemetry").projectDir = file("opentelemetry")
project(":cosec-social").projectDir = file("social")
project(":cosec-spring-boot-starter").projectDir = file("spring-boot-starter")
project(":cosec-webflux").projectDir = file("webflux")
project(":cosec-webmvc").projectDir = file("webmvc")
