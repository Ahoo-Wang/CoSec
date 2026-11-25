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
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.web.server.autoconfigure.ServerProperties
import org.springframework.core.Ordered
import org.springframework.core.PriorityOrdered
import org.springframework.core.env.Environment
import org.springframework.util.unit.DataSize

/**
 * The web server's request-header limit on a host that reads `CoSec-Identity` (`IDN-43`): a service in `gateway` mode
 * or a host whose caller is the forwarded identity. Spring Boot's servers count every request header against one
 * limit, `server.max-http-request-header-size`, 8 KB by default, so an identity header near the 8 KB a reader accepts
 * (`cosec.pipeline.identity.max-size`, R) would be refused by the server (`431`) before the gate read it. When the
 * operator has not set the limit, it is derived: R, and the 8 KB the server gave the request's other headers before.
 * An operator's value is kept, and named at startup when it is below that ([line]).
 *
 * Applied to the bound [ServerProperties], the one value every server's customizer reads it from (Tomcat's, Jetty's
 * and Reactor Netty's, for Spring MVC and WebFlux alike, and the control API's service port, the application's server
 * on another port configured by the same customizers, `CTL-71`), once Spring has bound it and before any customizer
 * reads it. By then the environment is the one the application runs with: whatever added `cosec.pipeline.mode` or
 * `cosec.pipeline.caller`, an environment post-processor of the host's own in any order included, has run. An
 * environment post-processor of CoSec's own would see the environment only as far as the post-processors before it
 * had built it, and a host whose own post-processor adds the mode later would keep Spring Boot's 8 KB, as the first
 * application upgraded to 8.0.0-beta.9 did.
 *
 * Priority-ordered, so it is registered before the ordinary post-processors, any of which could create
 * [ServerProperties] while being created; one created before it anyway is not changed, and the startup [line] says
 * the limit is not derived rather than claiming it.
 */
internal class IdentityHeaderLimit(private val environment: Environment) : BeanPostProcessor, PriorityOrdered {
    /** The limit this set on the server's properties; null when it set none. */
    @Volatile
    var applied: DataSize? = null
        private set

    override fun getOrder(): Int = Ordered.LOWEST_PRECEDENCE

    override fun postProcessAfterInitialization(bean: Any, beanName: String): Any {
        if (bean is ServerProperties && readsIdentity(environment) && operatorLimit(environment) == null) {
            val derived = derived(environment)
            bean.maxHttpRequestHeaderSize = derived
            applied = derived
        }
        return bean
    }

    @InternalCoSecApi
    companion object {
        /** Spring Boot's request-header limit, which its Tomcat, Netty and Jetty customizers apply. */
        const val SERVER_LIMIT_KEY = "server.max-http-request-header-size"

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

        /** The limit the operator set, from any property source; null when none did. */
        fun operatorLimit(environment: Environment): DataSize? =
            Binder.get(environment).bind(SERVER_LIMIT_KEY, DataSize::class.java).orElse(null)

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
         * The startup line (`IDN-43`): the limit in [effect] on the server, as its properties hold it once every
         * customizer could read them, and where it came from; true first when it is at WARN. Derived only when this set
         * it ([applied]) and it is still the value in effect; the operator's value when they set one; otherwise set by
         * something else, after or before CoSec could. At WARN when the limit in effect is below R and the other
         * headers' 8 KB, since the server would refuse an identity header the gate accepts. Null on a host that does not
         * read the identity, or has no web server.
         */
        fun line(environment: Environment, effect: DataSize?, applied: DataSize?): Pair<Boolean, String>? {
            if (!readsIdentity(environment) || effect == null) return null
            val reads = reads(environment)
            val derived = derived(environment)
            val operator = operatorLimit(environment)
            val room = "$R_KEY (${reads.toBytes()} bytes) and ${OTHER_HEADERS.toBytes()} bytes for the request's " +
                "other headers"
            val below = effect < derived
            val raise = " The server refuses a request whose identity header is near $R_KEY: set $SERVER_LIMIT_KEY " +
                "to at least ${derived.toBytes()} bytes."
            val text = when {
                operator == null && applied != null && effect == applied ->
                    "$SERVER_LIMIT_KEY is ${effect.toBytes()} bytes, derived from $room (IDN-43)."
                operator != null && below ->
                    "$SERVER_LIMIT_KEY is ${effect.toBytes()} bytes, below $room (${derived.toBytes()} bytes), as the " +
                        "operator set it: the web server refuses a request whose identity header is near $R_KEY " +
                        "before CoSec reads it (IDN-43). Raise it, or leave it unset to have it derived."
                operator != null ->
                    "$SERVER_LIMIT_KEY is ${effect.toBytes()} bytes, as the operator set it, with room for $room " +
                        "(${derived.toBytes()} bytes) (IDN-43)."
                else ->
                    "$SERVER_LIMIT_KEY is ${effect.toBytes()} bytes, not derived: the web server's properties were " +
                        "set or created by something other than CoSec, so the ${derived.toBytes()} bytes of $room " +
                        "were not applied (IDN-43)." + raise.takeIf { below }.orEmpty()
            }
            return below to text
        }
    }
}
