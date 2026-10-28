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

/*
 * Modules by group (RFC 0011 §3). A project is named after its directory, without the cosec- prefix; the root build
 * gives every published one the artifact id cosec-<name>. test/architecture's ProjectList keeps this list and the
 * groups in step (G6).
 */
val groups = linkedMapOf(
    "api" to listOf("api"),
    "core" to listOf("model", "engine", "runtime", "control", "core"),
    "backends" to listOf("cocache", "kafka"),
    "edge" to listOf("webflux", "webmvc", "gateway", "openapi"),
    "extensions" to listOf("jwt", "social", "ip2region", "micrometer", "opentelemetry"),
    "host" to listOf("spring-boot"),
    "migration" to listOf("migrate"),
    "test" to listOf("testkit", "conformance", "architecture", "e2e", "code-coverage-report"),
    "bom" to listOf("bom", "dependencies"),
    "platform" to listOf("gateway-server"),
)

for ((group, names) in groups) {
    for (name in names) {
        include(":$name")
        project(":$name").projectDir = file("$group/$name")
    }
}
