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

package me.ahoo.cosec.spring.boot.starter

import org.springframework.core.env.PropertySource
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.env.SystemEnvironmentPropertySource

/**
 * The environment variables Kubernetes injects for a Service in the pod's namespace (service links), as far as they
 * start with `COSEC`: those of a Service named `cosec` or `cosec-*` (`cosec-gateway` becomes `COSEC_GATEWAY_...`).
 * Spring reads `COSEC_GATEWAY_SERVICE_HOST` as `cosec.gateway.service.host`, which [UnknownProperties] would refuse, so
 * the check leaves them out. Only these shapes, and only from the process environment: `COSEC_JWT_SECRT` in it, and
 * `cosec.gateway.service.host` anywhere else, are still unknown keys.
 *
 * Matching is on the raw variable names, as `pkg/kubelet/envvars` writes them (upper case, `-` as `_`), never on the
 * relaxed property names. No CoSec property has such a name, and a known key is never unknown whatever its source, so
 * a real property cannot be hidden by this; the variables stay in the environment and bind as before.
 */
internal object KubernetesServiceLinks {
    /** The variable prefix of a Service named `cosec` or `cosec-*`. */
    private const val SERVICE = "COSEC(?:_[A-Z0-9_]+)?"
    private const val PROTOCOL = "(?:TCP|UDP|SCTP)"

    /** `<SVC>_SERVICE_HOST`, `<SVC>_SERVICE_PORT` and `<SVC>_SERVICE_PORT_<NAME>` (a named port). */
    private val SERVICE_VARIABLES = Regex("$SERVICE(?:_SERVICE_HOST|_SERVICE_PORT(?:_[A-Z0-9_]+)?)")

    /** `<SVC>_PORT`: Docker's link variable for the first port, whose value is `tcp://<ip>:<port>`. */
    private val LINK_VARIABLE = Regex("${SERVICE}_PORT")
    private val LINK_URL = Regex("(?:tcp|udp|sctp)://.+")

    /** `<SVC>_PORT_<port>_<PROTOCOL>` and its `_PROTO`, `_PORT` and `_ADDR`. */
    private val PORT_VARIABLES = Regex("${SERVICE}_PORT_[0-9]+_$PROTOCOL(?:_(?:PROTO|PORT|ADDR))?")

    /** Whether the environment variable [name] with [value] is one Kubernetes injects for a Service. */
    fun isServiceLink(name: String, value: Any?): Boolean = when {
        SERVICE_VARIABLES.matches(name) || PORT_VARIABLES.matches(name) -> true
        // COSEC_<X>_PORT could just as well be a mistyped cosec.<x>.port: only the URL Kubernetes writes is skipped.
        LINK_VARIABLE.matches(name) -> value is String && LINK_URL.matches(value)
        else -> false
    }

    /**
     * [source] without its service links when it is the process environment; any other source as it is. That is
     * Spring Boot's own test for reading a source's names as variable names: a [SystemEnvironmentPropertySource]
     * (`SpringApplication` replaces the environment's by an origin-tracking subclass) named `systemEnvironment`, or
     * ending in `-systemEnvironment`. Any other source never exposes `COSEC_GATEWAY_PORT` as `cosec.gateway.port`.
     */
    fun withoutServiceLinks(source: PropertySource<*>): PropertySource<*> {
        if (source !is SystemEnvironmentPropertySource || !isEnvironmentName(source.name)) {
            return source
        }
        val kept = source.source.filterNot { (name, value) -> isServiceLink(name, value) }
        return SystemEnvironmentPropertySource(source.name, kept)
    }

    private fun isEnvironmentName(name: String): Boolean =
        name == StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME ||
            name.endsWith("-${StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME}")
}
