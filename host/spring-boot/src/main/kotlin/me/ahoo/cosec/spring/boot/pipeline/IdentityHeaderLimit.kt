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

@file:OptIn(InternalCoSecApi::class)

package me.ahoo.cosec.spring.boot.pipeline

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.document.identity.IdentityCodec
import me.ahoo.cosec.spring.boot.ConditionalOnCoSecEnabled
import org.springframework.boot.EnvironmentPostProcessor
import org.springframework.boot.SpringApplication
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver
import org.springframework.boot.context.properties.source.ConfigurationPropertySources
import org.springframework.core.Ordered
import org.springframework.core.env.ConfigurableEnvironment
import org.springframework.core.env.Environment
import org.springframework.core.env.MapPropertySource
import org.springframework.util.unit.DataSize

/**
 * The web server's request-header limit on a host that reads `CoSec-Identity` (`IDN-43`): a service in `gateway` mode
 * or a host whose caller is the forwarded identity. Spring Boot's servers count every request header against one
 * limit, `server.max-http-request-header-size`, 8 KB by default, so an identity header near the 8 KB a reader accepts
 * (`cosec.pipeline.identity.max-size`, R) would be refused by Tomcat or Netty before the gate read it. When the
 * operator has not set the limit, it is derived: R, and the 8 KB the server gave the request's other headers before.
 * An operator's value is kept, and named at startup when it is below that ([check]). The control API's service port
 * is the application's server on another port, configured by the same customizers (`CTL-71`), so it gets the same.
 *
 * An environment post-processor, after the configuration files are read: the limit is a property the server's
 * customizers read when they configure it, before any bean of CoSec exists.
 */
internal class IdentityHeaderLimit : EnvironmentPostProcessor, Ordered {
    override fun getOrder(): Int = ConfigDataEnvironmentPostProcessor.ORDER + 1

    override fun postProcessEnvironment(environment: ConfigurableEnvironment, application: SpringApplication) {
        if (!readsIdentity(environment) || operatorLimit(environment) != null) {
            return
        }
        val derived = derived(environment)
        val limit = mapOf(SERVER_LIMIT_KEY to "${derived.toBytes()}B")
        environment.propertySources.addLast(MapPropertySource(SOURCE, limit))
    }

    @InternalCoSecApi
    companion object {
        /** Spring Boot's request-header limit, which its Tomcat, Netty and Jetty customizers apply. */
        const val SERVER_LIMIT_KEY = "server.max-http-request-header-size"

        /** The property source the derived limit is in: the last, so any value an operator writes comes first. */
        const val SOURCE = "cosecIdentityHeaderLimit"

        /** What the server gave a request's headers by default, before CoSec added its own: the rest of them. */
        val OTHER_HEADERS: DataSize = DataSize.ofKilobytes(8)

        private const val R_KEY = "${PipelineProperties.PREFIX}.identity.max-size"

        /** Whether the host reads `CoSec-Identity`: CoSec on, and `gateway` mode or the forwarded identity. */
        fun readsIdentity(environment: Environment): Boolean {
            val binder = Binder.get(environment)
            val enabled = binder.bind(ConditionalOnCoSecEnabled.ENABLED_KEY, Boolean::class.javaObjectType)
                .orElse(null) != false
            val mode = binder.bind(PipelineProperties.MODE_KEY, PipelineProperties.Mode::class.java).orElse(null)
            val caller = binder.bind(PipelineProperties.CALLER_KEY, PipelineProperties.Caller::class.java).orElse(null)
            return enabled &&
                (mode == PipelineProperties.Mode.GATEWAY || caller == PipelineProperties.Caller.FORWARDED_IDENTITY)
        }

        /** R: the largest identity header the host reads. */
        fun reads(environment: Environment): DataSize =
            Binder.get(environment).bind(R_KEY, DataSize::class.java).orElse(null)
                ?: DataSize.ofBytes(IdentityCodec.READ_MAX_BYTES.toLong())

        /** The limit derived from R: R and the other headers' 8 KB. */
        fun derived(environment: Environment): DataSize =
            DataSize.ofBytes(reads(environment).toBytes() + OTHER_HEADERS.toBytes())

        /** The limit an operator set, from any source but the derived one; null when none did. */
        fun operatorLimit(environment: ConfigurableEnvironment): DataSize? {
            val sources = environment.propertySources.filter { it.name != SOURCE }
            return Binder(ConfigurationPropertySources.from(sources), PropertySourcesPlaceholdersResolver(environment))
                .bind(SERVER_LIMIT_KEY, DataSize::class.java).orElse(null)
        }

        /**
         * `IDN-43`, in a process that holds both limits: a host that reads the identity and relays its callers with
         * `HttpControlService`, whose writer limit is W, does not start when what it reads, R, is below W. It would
         * relay headers larger than its own gate admits, which the hosts it calls, configured alike, refuse.
         */
        fun requireRelayWithinReads(environment: Environment) {
            if (!readsIdentity(environment)) return
            val reads = reads(environment)
            check(reads.toBytes() >= IdentityCodec.WRITE_MAX_BYTES) {
                "$R_KEY is ${reads.toBytes()} bytes, below the ${IdentityCodec.WRITE_MAX_BYTES} bytes HttpControlService " +
                    "relays this host's callers within: it would relay identity headers larger than its own gate admits " +
                    "(IDN-43). Set $R_KEY to at least ${IdentityCodec.WRITE_MAX_BYTES} bytes."
            }
        }

        /**
         * `IDN-43`, at a gateway, which holds its writer limit and knows the reader limit every 8.x service has by
         * default: a writer limit above it, named at startup at WARN with both numbers; null within it.
         */
        fun gatewayWriterLine(writes: DataSize): String? {
            if (writes.toBytes() <= IdentityCodec.READ_MAX_BYTES) return null
            return "cosec.gateway.identity.max-size is ${writes.toBytes()} bytes, above the " +
                "${IdentityCodec.READ_MAX_BYTES} bytes an 8.x service reads by default ($R_KEY): every service behind " +
                "this gateway must set $R_KEY to at least ${writes.toBytes()} bytes, or it refuses the larger identity " +
                "headers with 400 (IDN-43)."
        }

        /**
         * The startup line (`IDN-43`): the derived limit and what it is derived from, at INFO; an operator's value
         * below it, with both numbers, at WARN, since the server would refuse an identity header the gate accepts.
         * Null for an operator's value that leaves room.
         */
        fun check(environment: ConfigurableEnvironment): Pair<Boolean, String>? {
            val reads = reads(environment)
            val derived = derived(environment)
            val operator = operatorLimit(environment)
            return when {
                operator == null -> false to "$SERVER_LIMIT_KEY is ${derived.toBytes()} bytes, derived from $R_KEY " +
                    "(${reads.toBytes()} bytes) and ${OTHER_HEADERS.toBytes()} bytes for the request's other headers " +
                    "(IDN-43)."
                operator < derived -> true to "$SERVER_LIMIT_KEY is ${operator.toBytes()} bytes, below $R_KEY " +
                    "(${reads.toBytes()} bytes) and ${OTHER_HEADERS.toBytes()} bytes for the request's other headers " +
                    "(${derived.toBytes()} bytes): the web server refuses a request whose identity header is near " +
                    "$R_KEY before CoSec reads it (IDN-43). Raise it, or leave it unset to have it derived."
                else -> null
            }
        }
    }
}
