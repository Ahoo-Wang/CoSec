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

// Where the plugins come from, and what they bring: Maven Central first, then the Gradle Plugin Portal. By default only
// the portal is asked, and it answers for what Central hosts (the BOMs and libraries of the Kotlin, Dokka and JMH plugins)
// by proxying Central; when that proxy answered "Could not find" for POMs that exist, the build failed on every attempt
// for minutes. Central serves them itself; the portal is asked only for what Central lacks.
//
// What Central lacks is the markers and jars of the two plugins only the portal publishes: the Nexus publish plugin
// (`io.github.gradle-nexus.publish-plugin`, jar `io.github.gradle-nexus:publish-plugin`) and the JMH plugin
// (`me.champeau.jmh`, jar `me.champeau.jmh:jmh-gradle-plugin`); each answered 404 on Central and 200 on the portal on
// 2026-11-20, for the versions in gradle/libs.versions.toml. Central is not asked for their groups at all: it answers 429 to a
// runner that asks it too much, and Gradle fails a resolution on a 429 from the first repository without going on to the
// portal, which has the plugin (mutation run 44835108385; .github/scripts/README.md). Every other plugin, and every library a
// plugin brings, is asked of Central first, as before.
//
// If one of these plugins is later published to Central too, nothing changes silently: the portal is still asked and
// answers, and the Central copy is not used. If it leaves the portal, the build fails with "not found" naming the portal.
// A new plugin that only the portal has is added to `portalOnly` with the check above.
pluginManagement {
    val portalOnly = listOf("io.github.gradle-nexus", "io.github.gradle-nexus.publish-plugin", "me.champeau.jmh")
    repositories {
        mavenCentral {
            content { portalOnly.forEach { excludeGroup(it) } }
        }
        gradlePluginPortal()
    }
}

rootProject.name = "CoSec"

/*
 * Modules by group (RFC 0011 §3). A project is named after its directory, without the cosec- prefix; the root build
 * gives every published one the artifact id cosec-<name>. test/architecture's ProjectList keeps this list and the
 * groups in step (G6).
 */
val groups = linkedMapOf(
    "api" to listOf("annotations", "api"),
    "core" to listOf("model", "engine", "spi", "document", "runtime", "control", "control-sdk"),
    "backends" to listOf("cocache"),
    "edge" to listOf("webflux", "webmvc", "gateway", "openapi"),
    "extensions" to listOf("jwt", "social", "login", "ip2region", "micrometer", "opentelemetry", "cosid", "kafka", "control-http"),
    "host" to listOf("spring-boot"),
    "migration" to listOf("migrate"),
    "test" to listOf("testkit", "conformance", "architecture", "e2e", "client-host", "system-test", "system-behind", "system-auth", "system-enforcer", "code-coverage-report"),
    "bom" to listOf("bom", "dependencies"),
    "platform" to listOf("gateway-server", "control-server"),
)

for ((group, names) in groups) {
    for (name in names) {
        include(":$name")
        project(":$name").projectDir = file("$group/$name")
    }
}
