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

package me.ahoo.cosec.spring.boot.starter.pipeline

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.beans.factory.ListableBeanFactory
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory
import org.springframework.boot.cloud.CloudPlatform
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.core.env.Environment
import org.springframework.util.ClassUtils
import org.springframework.util.ReflectionUtils

private val log = KotlinLogging.logger {}

/**
 * How this host reads the client address (`PIP-16`): exactly once, from one party it trusts. Where the server has
 * already resolved it, CoSec takes the server's answer and never reads `X-Forwarded-For` again; where the server took
 * it from a header a client writes, CoSec reads the header and never the server's answer.
 *
 * - [maxTrustedIndex] `0`: the host's remote address, as the server gives it.
 * - [peerIsConnection] false: the host's remote address is a client's claim, so the address comes from
 *   `X-Forwarded-For` alone ([me.ahoo.cosec.runtime.http.XForwardedRemoteIpResolver]).
 */
internal class ClientIpReading(val maxTrustedIndex: Int, val peerIsConnection: Boolean, val why: String) {
    override fun toString(): String = why
}

/** A forward-headers setup under which CoSec cannot read the client address safely: startup fails, saying why. */
internal class ClientIpConfigurationException(message: String) : IllegalStateException(message)

/**
 * Decides [ClientIpReading] from the server Spring Boot builds and its effective forward-headers strategy: the one
 * configured (`server.forward-headers-strategy`, or on Tomcat `server.tomcat.remoteip.*`), or the one Spring Boot
 * infers from the cloud platform it detects (`native` on Kubernetes, Cloud Foundry, Heroku and the others).
 */
internal object ClientIpReadings {
    const val MAX_TRUSTED_INDEX = RuntimeAuthorizationProperties.PREFIX + ".remote-ip.max-trusted-index"
    private const val STRATEGY = "server.forward-headers-strategy"
    private const val TOMCAT_REMOTE_IP_HEADER = "server.tomcat.remoteip.remote-ip-header"
    private const val TOMCAT_PROTOCOL_HEADER = "server.tomcat.remoteip.protocol-header"
    private const val TROUBLESHOOTING = "https://cosec.ahoo.me/operations/troubleshooting#startup-fails-on-the-client-address"

    /** The strategies of `server.forward-headers-strategy`. */
    enum class Strategy { NONE, NATIVE, FRAMEWORK }

    /** The embedded servers whose forward-headers handling is known; [NONE] when Spring Boot builds none here. */
    enum class Server(val factory: String?) {
        TOMCAT("org.springframework.boot.tomcat.TomcatWebServerFactory"),
        JETTY("org.springframework.boot.jetty.JettyWebServerFactory"),
        NETTY("org.springframework.boot.reactor.netty.NettyReactiveWebServerFactory"),

        /** A server factory of another kind. */
        OTHER(null),

        /** No server factory: a war in an external container, or a context without a web server. */
        NONE(null),
    }

    /** What [environment] and the beans of [beans] say. */
    fun of(
        environment: Environment,
        beans: ListableBeanFactory,
        properties: RuntimeAuthorizationProperties
    ): ClientIpReading {
        val binder = Binder.get(environment)
        val explicit = binder.bind(MAX_TRUSTED_INDEX, Int::class.java).isBound
        val server = serverOf(beans)
        val reading = decide(
            strategyOf(environment, binder, server),
            server,
            properties.remoteIp.maxTrustedIndex,
            explicit
        )
        rewritingFilter(beans)?.let { throw framework(it) }
        log.info { "The client address of requests: $reading (PIP-16)." }
        return reading
    }

    /** The reading for [strategy] on [server]; throws [ClientIpConfigurationException] where none is safe. */
    fun decide(strategy: Strategy, server: Server, maxTrustedIndex: Int, explicit: Boolean): ClientIpReading =
        when (strategy) {
            Strategy.NONE -> ClientIpReading(
                maxTrustedIndex,
                true,
                if (maxTrustedIndex == 0) {
                    "the connection's peer ($MAX_TRUSTED_INDEX=0)"
                } else {
                    "the X-Forwarded-For entry $maxTrustedIndex from the right, or the connection's peer without one"
                },
            )
            Strategy.FRAMEWORK -> throw framework("$STRATEGY=framework")
            Strategy.NATIVE -> native(server, maxTrustedIndex, explicit)
        }

    private fun native(server: Server, maxTrustedIndex: Int, explicit: Boolean): ClientIpReading = when (server) {
        // RemoteIpValve has taken the first untrusted address from the right as remoteAddr and left the rest in the
        // header: reading the header again would read one hop further left, an entry the client wrote.
        Server.TOMCAT -> {
            if (explicit) {
                throw ClientIpConfigurationException(
                    "$MAX_TRUSTED_INDEX is set, but Tomcat's RemoteIpValve resolves the client address on this host " +
                        "(the native forward-headers strategy, configured or inferred from the cloud platform): CoSec " +
                        "takes the address the valve resolved, and the proxies it trusts are configured in " +
                        "server.tomcat.remoteip.internal-proxies and server.tomcat.remoteip.trusted-proxies. Remove " +
                        "$MAX_TRUSTED_INDEX, or set $STRATEGY=none to have CoSec read X-Forwarded-For itself " +
                        "($TROUBLESHOOTING).",
                )
            }
            ClientIpReading(0, true, "the address Tomcat's RemoteIpValve resolved (server.tomcat.remoteip.*)")
        }
        // Reactor Netty and Jetty make the leftmost X-Forwarded-For entry, or a client's Forwarded header, the remote
        // address and keep the header: CoSec reads the header and never that address.
        Server.NETTY, Server.JETTY -> {
            if (maxTrustedIndex == 0) {
                throw ClientIpConfigurationException(
                    "$MAX_TRUSTED_INDEX=0 reads the connection's peer, but with the native forward-headers strategy " +
                        "(configured or inferred from the cloud platform) ${server.name.lowercase()} reports as the " +
                        "remote address what a client wrote in X-Forwarded-For or Forwarded, and the peer cannot be " +
                        "read. Set $MAX_TRUSTED_INDEX to the number of proxies in front of the host, or $STRATEGY=none " +
                        "for a host that clients reach directly ($TROUBLESHOOTING).",
                )
            }
            ClientIpReading(
                maxTrustedIndex,
                false,
                "the X-Forwarded-For entry $maxTrustedIndex from the right, never the address " +
                    "${server.name.lowercase()} forwarded",
            )
        }
        Server.OTHER, Server.NONE -> throw ClientIpConfigurationException(
            "The native forward-headers strategy is on (configured or inferred from the cloud platform), but CoSec " +
                "cannot tell how this host's server resolves the client address (${server.name.lowercase()} server " +
                "factory), so it cannot tell which address to trust. Set $STRATEGY=none and $MAX_TRUSTED_INDEX to the " +
                "number of proxies in front of the host ($TROUBLESHOOTING).",
        )
    }

    private fun framework(what: String) = ClientIpConfigurationException(
        "$what: Spring's ForwardedHeaderFilter and ForwardedHeaderTransformer make the leftmost X-Forwarded-For " +
            "entry, which the client writes, the remote address and remove the header, so the client address cannot be " +
            "read safely. Use $STRATEGY=native on Tomcat, Jetty or Reactor Netty with the proxies configured there, or " +
            "$STRATEGY=none and $MAX_TRUSTED_INDEX ($TROUBLESHOOTING).",
    )

    private fun strategyOf(environment: Environment, binder: Binder, server: Server): Strategy {
        val configured = binder.bind(STRATEGY, String::class.java).orElse(null)?.trim()?.uppercase()
        if (configured != null) {
            val strategy = Strategy.entries.firstOrNull { it.name == configured } ?: Strategy.NATIVE
            // Tomcat adds its valve for these alone, whatever the strategy says (TomcatWebServerFactoryCustomizer).
            return if (strategy == Strategy.NONE && server == Server.TOMCAT && tomcatValve(binder)) Strategy.NATIVE else strategy
        }
        if (CloudPlatform.getActive(environment)?.isUsingForwardHeaders == true) {
            return Strategy.NATIVE
        }
        return if (server == Server.TOMCAT && tomcatValve(binder)) Strategy.NATIVE else Strategy.NONE
    }

    private fun tomcatValve(binder: Binder): Boolean =
        listOf(TOMCAT_REMOTE_IP_HEADER, TOMCAT_PROTOCOL_HEADER).any {
            !binder.bind(it, String::class.java).orElse(null).isNullOrBlank()
        }

    private fun serverOf(beans: ListableBeanFactory): Server {
        val loader = beans.javaClass.classLoader ?: ClientIpReadings::class.java.classLoader
        for (server in listOf(Server.TOMCAT, Server.JETTY, Server.NETTY)) {
            if (namesOf(beans, server.factory!!, loader).isNotEmpty()) {
                return server
            }
        }
        return if (namesOf(beans, WEB_SERVER_FACTORY, loader).isEmpty()) Server.NONE else Server.OTHER
    }

    /** Spring's forwarded-header rewriting registered as a bean, by Boot's framework strategy or by the application. */
    private fun rewritingFilter(beans: ListableBeanFactory): String? {
        val loader = ClientIpReadings::class.java.classLoader
        return rewritingTransformer(beans, loader) ?: rewritingServletFilter(beans, loader)
    }

    private fun rewritingTransformer(beans: ListableBeanFactory, loader: ClassLoader): String? {
        if (!ClassUtils.isPresent(TRANSFORMER, loader)) return null
        val type = ClassUtils.forName(TRANSFORMER, loader)
        val names = beans.getBeanNamesForType(type, true, false)
        if (names.isEmpty()) return null
        val removeOnly = reflected(
            ReflectionUtils.findMethod(type, TRANSFORMER_REMOVE_ONLY),
            "ForwardedHeaderTransformer",
            names.first(),
            "method $TRANSFORMER_REMOVE_ONLY()",
        )
        return names.firstOrNull { !(removeOnly.invoke(beans.getBean(it)) as Boolean) }
            ?.let { "The ForwardedHeaderTransformer bean [$it]" }
    }

    private fun rewritingServletFilter(beans: ListableBeanFactory, loader: ClassLoader): String? {
        if (!ClassUtils.isPresent(FILTER, loader)) return null
        val type = ClassUtils.forName(FILTER, loader)
        val filters = LinkedHashMap<String, Any>()
        beans.getBeanNamesForType(type, true, false).forEach { filters[it] = beans.getBean(it) }
        if (ClassUtils.isPresent(REGISTRATION, loader)) {
            val registration = ClassUtils.forName(REGISTRATION, loader)
            val filterOf = ReflectionUtils.findMethod(registration, REGISTRATION_FILTER)
            // Every registration but one being created, such as CoSec's own filter's while this runs.
            val creating = (beans as? ConfigurableListableBeanFactory)
                ?: (beans as? ConfigurableApplicationContext)?.beanFactory
            for (name in beans.getBeanNamesForType(registration, true, false)) {
                if (creating?.isCurrentlyInCreation(name) == true) continue
                reflected(filterOf, "FilterRegistrationBean", name, "method $REGISTRATION_FILTER()")
                    .invoke(beans.getBean(name))?.let { filters[name] = it }
            }
        }
        val forwarded = filters.entries.filter { (_, filter) -> type.isInstance(filter) }
        if (forwarded.isEmpty()) return null
        val removeOnly = reflected(
            ReflectionUtils.findField(type, FILTER_REMOVE_ONLY),
            "ForwardedHeaderFilter",
            forwarded.first().key,
            "field $FILTER_REMOVE_ONLY",
        ).also { ReflectionUtils.makeAccessible(it) }
        return forwarded.firstOrNull { (_, filter) -> !(removeOnly.get(filter) as Boolean) }
            ?.let { "The ForwardedHeaderFilter bean [${it.key}]" }
    }

    /**
     * [found], the [member] of [type] CoSec reads to tell whether bean [name] only removes forwarded headers. Without
     * it, on a Spring version that renamed it, whether the bean rewrites the request is unknown: startup fails, naming
     * why (the names are pinned by `ClientIpReadingsTest`).
     */
    internal fun <T : Any> reflected(
        found: T?,
        type: String,
        name: String,
        member: String
    ): T = found ?: throw ClientIpConfigurationException(
        "CoSec cannot tell whether the $type bean [$name] rewrites the request on this Spring version: it has no $member, " +
            "which CoSec reads to tell one that only removes forwarded headers. Remove the bean, or use a CoSec release " +
            "for this Spring version ($TROUBLESHOOTING).",
    )

    private fun namesOf(beans: ListableBeanFactory, type: String, loader: ClassLoader): Array<String> =
        if (ClassUtils.isPresent(type, loader)) {
            beans.getBeanNamesForType(ClassUtils.forName(type, loader), true, false)
        } else {
            emptyArray()
        }

    private const val WEB_SERVER_FACTORY = "org.springframework.boot.web.server.WebServerFactory"
    private const val TRANSFORMER = "org.springframework.web.server.adapter.ForwardedHeaderTransformer"
    private const val FILTER = "org.springframework.web.filter.ForwardedHeaderFilter"

    /** The members of Spring read by reflection, so the starter needs no compile-time Spring Web types. */
    internal const val TRANSFORMER_REMOVE_ONLY = "isRemoveOnly"
    internal const val FILTER_REMOVE_ONLY = "removeOnly"
    internal const val REGISTRATION_FILTER = "getFilter"
    private const val REGISTRATION = "org.springframework.boot.web.servlet.FilterRegistrationBean"
}
