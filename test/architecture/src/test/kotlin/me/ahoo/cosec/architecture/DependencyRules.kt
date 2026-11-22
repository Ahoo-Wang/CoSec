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

package me.ahoo.cosec.architecture

/**
 * RFC 0011 §2 and §5 as data: the project edges each project's main configurations may declare, and the libraries the
 * inner projects may have. Dependencies only point inwards; outer modules do not depend on each other. An entry of
 * [EXCEPTIONS] is a reviewed exception with its reason: adding one is a diff in review, not a quiet build change.
 */
object DependencyRules {
    private val PURE = setOf("annotations", "api")
    private val CORE = PURE + setOf("model", "engine", "spi", "document", "runtime", "control", "control-sdk")

    /** The project edges each project may declare; a project not listed (host, migration, test) may use any library project. */
    val ALLOWED: Map<String, Set<String>> = mapOf(
        "annotations" to emptySet(),
        "api" to emptySet(),
        "model" to setOf("api", "annotations"),
        "engine" to setOf("model", "api"),
        "spi" to setOf("model", "api", "annotations"),
        // The documents and their formats (RFC 0013 §3): what reads a document does not need the request path.
        "document" to setOf("engine", "spi", "model", "api", "annotations"),
        "runtime" to setOf("document", "engine", "spi", "model", "api", "annotations"),
        "control" to CORE - "control",
        // The facade an application calls (CTL-65): the documents' format and the ports, never the request path or the
        // control plane, so an application that calls a control host over HTTP brings neither.
        "control-sdk" to setOf("document", "spi", "api", "annotations"),
        // A backend implements store ports of cosec-spi and writes its formats with cosec-document (RFC 0013 §3).
        "cocache" to setOf("spi", "document", "model", "api", "annotations"),
        // The adapters translate a host's request for the pipeline and never decide: the model and the request path,
        // not the engine or the documents (the gateway reads `CoSec-Identity`'s format, so it adds `document`).
        "webflux" to PURE + setOf("model", "runtime"),
        "webmvc" to PURE + setOf("model", "runtime"),
        "gateway" to PURE + setOf("model", "document", "runtime"),
        // A generator of documents and route maps (RFC 0013 §3): it reads the format, never the request path.
        "openapi" to setOf("document", "engine", "spi", "model", "api", "annotations"),
        // What each extension imports today: a new edge is a visible edit of this table.
        "jwt" to PURE + setOf("model", "spi", "runtime"),
        "social" to PURE + setOf("model", "spi"),
        "login" to PURE + setOf("model", "spi", "document", "runtime"),
        "ip2region" to PURE + setOf("model", "spi"),
        "micrometer" to PURE + setOf("model", "spi", "runtime"),
        "opentelemetry" to PURE + setOf("model", "runtime"),
        "kafka" to PURE + setOf("model", "spi", "document", "runtime"),
        // The control plane's facade over HTTP (CTL-77). It brings the request path: a request's caller is written as the
        // gateway forwards it (IdentityIssuer), and a service caller as the in-process implementation admits it
        // (ServiceIdentity), so both implementations state a caller with one mapping, never a copy of it.
        "control-http" to setOf("control-sdk", "runtime"),
        // An implementation of the IdGenerator port, and nothing more of CoSec.
        "cosid" to setOf("spi", "model", "api", "annotations"),
    )

    class Exception(val from: String, val to: String, val reason: String)

    /** Reviewed exceptions to [ALLOWED] (RFC 0011 §2, §5 and its amendment, #1140). */
    val EXCEPTIONS: List<Exception> = listOf(
        Exception("cocache", "control", "CoCacheControlStore implements the control plane's ControlStore"),
        Exception("kafka", "control", "KafkaChangeListener is the control plane's ChangeListener"),
        Exception("jwt", "login", "jwt implements login's token issuing port (RFC 0011 §2)"),
        Exception("social", "login", "social login is a login AuthenticationProvider"),
        Exception("gateway", "webflux", "the gateway filter runs on the WebFlux bridge"),
    )

    /**
     * No backend depends on `cosec-runtime` (RFC 0013 §3): a backend implements store ports of `cosec-spi`, so a Redis
     * store does not bring the request path. Checked on the declared main dependencies and the run-time class path.
     * Not on the compile class path: `cosec-cocache` implements the control plane's `ControlStore`, a port that stays
     * in its layer, through `compileOnlyApi(:control)`, and `cosec-control` brings `cosec-runtime` with it.
     */
    const val BACKENDS = "backends"
    const val RUNTIME = "runtime"

    /**
     * The outer projects that only read or write documents, so `cosec-runtime` is not on their run-time class path
     * either (RFC 0013 §3): the OpenAPI generator, and the control plane's facade (CTL-65), which an application calls
     * without the request path.
     */
    val DOCUMENT_ONLY: Set<String> = setOf("openapi", "control-sdk")

    /**
     * The control server (`platform/control-server`) is the starter with the control plane and nothing else: it is no
     * gateway, serves one stack (WebFlux), issues no token for anybody to sign in with, and does not bring CosId, since
     * CoSec owns its ids. A module it needs beyond these is a visible edit of this list.
     */
    const val CONTROL_SERVER = "control-server"
    val CONTROL_SERVER_NEVER: Set<String> = setOf("gateway", "webmvc", "social", "cosid", "openapi")

    /** Groups whose main code never depends on these. */
    val NEVER_DEPENDED_ON: Set<String> = setOf("test", "platform")

    /** Platform BOMs every project may import. */
    val PLATFORMS: Set<String> = setOf("bom", "dependencies")

    /** The only libraries the pure projects may resolve; `spi` adds coroutines (§5). */
    val PURE_LIBRARIES: Set<String> = setOf("org.jetbrains.kotlin:kotlin-stdlib", "org.jetbrains:annotations")
    val SPI_LIBRARIES: Set<String> = PURE_LIBRARIES + setOf(
        "org.jetbrains.kotlinx:kotlinx-coroutines-core",
        "org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm",
    )

    /** What `document` may resolve besides the ports' libraries: Jackson, the one mapper's (RFC 0013 §3). No logging. */
    val DOCUMENT_LIBRARIES: Set<String> = SPI_LIBRARIES + setOf(
        "tools.jackson.core:jackson-core",
        "tools.jackson.core:jackson-databind",
        "com.fasterxml.jackson.core:jackson-annotations",
        "tools.jackson.module:jackson-module-kotlin",
        "org.jetbrains.kotlin:kotlin-reflect",
    )

    /**
     * The projects that may reference or bring CosId, with the reason. CoSec owns its ids (`IdGenerator`), so an
     * application on any CosId version can put CoSec on its class path.
     */
    val COSID_OWNERS: Map<String, String> = mapOf(
        "cosid" to "cosec-cosid adapts CosId 5 to the IdGenerator port",
        "spring-boot" to "the host wires cosec-cosid by class path, as it does every module (RFC 0011 §2)",
    )

    /** What `runtime` and `control` never have (§5): Reactor, Spring, the Servlet API, a Redis client. */
    val NEVER_IN_CORE: List<String> = listOf(
        "io.projectreactor:",
        "org.springframework",
        "jakarta.servlet:",
        "io.lettuce:",
        "redis.clients:",
    )
}
