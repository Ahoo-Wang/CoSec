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

package me.ahoo.cosec.spring.boot

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.document.route.RouteMap
import me.ahoo.cosec.login.CompositeAuthentication
import me.ahoo.cosec.runtime.event.DecisionListener
import me.ahoo.cosec.spi.apikey.ApiKeyReader
import me.ahoo.cosec.spi.ratelimit.RateLimiter
import me.ahoo.cosec.spi.token.RevocationReader
import me.ahoo.cosec.spi.token.TokenVerifier
import org.springframework.beans.factory.ListableBeanFactory
import org.springframework.core.env.Environment

/**
 * UB-5: one line at the end of startup that says what CoSec is configured as, so an operator reading a log can tell the
 * instance they deployed from the one they meant to: the mode, the web adapter, the token verifier and the login, the
 * revocation reader, API keys, the rate limiter, the decision listeners and the route map. When CoSec is off, one WARN
 * that nothing is protected. Reads beans that exist; creates none.
 */
internal object StartupSummary {
    private val log = KotlinLogging.logger {}

    /** The line, or the warning when [ENABLED_KEY] is `false`. */
    fun report(beans: ListableBeanFactory, environment: Environment): Report {
        if (environment.getProperty(ConditionalOnCoSecEnabled.ENABLED_KEY).equals("false", ignoreCase = true)) {
            return Report.Off(
                "CoSec is off: ${ConditionalOnCoSecEnabled.ENABLED_KEY}=false. This application authorizes no request " +
                    "and nothing is protected by CoSec."
            )
        }
        val mode = environment.getProperty(MODE_KEY).orEmpty().ifBlank { "enforce" }.lowercase()
        val parts = buildList {
            add("mode=$mode")
            add("web=${adapter(beans)}")
            add("jwt=${names(beans, TokenVerifier::class.java).ifEmpty { "off" }}")
            add("login=${if (names(beans, CompositeAuthentication::class.java).isEmpty()) "off" else "on"}")
            add("revocation-reader=${names(beans, RevocationReader::class.java).ifEmpty { "none" }}")
            add("api-keys=${names(beans, ApiKeyReader::class.java).ifEmpty { "off" }}")
            add("rate-limiter=${names(beans, RateLimiter::class.java).ifEmpty { "none" }}")
            add("listeners=[${names(beans, DecisionListener::class.java)}]")
            add("route-map=${routes(beans)}")
        }
        return Report.On("CoSec${version()}: " + parts.joinToString(", "))
    }

    fun log(report: Report) {
        when (report) {
            is Report.Off -> log.warn { report.line }
            is Report.On -> log.info { report.line }
        }
    }

    sealed interface Report {
        val line: String

        data class On(override val line: String) : Report

        data class Off(override val line: String) : Report
    }

    private const val MODE_KEY = "cosec.pipeline.mode"

    private fun version(): String = StartupSummary::class.java.`package`?.implementationVersion?.let { " $it" }.orEmpty()

    /** The simple class names of the beans of [type], in bean order, each kind once with its count. */
    private fun names(beans: ListableBeanFactory, type: Class<*>): String =
        beans.getBeansOfType(type, true, false).values
            .map { it.javaClass.simpleName.ifEmpty { it.javaClass.name } }
            .groupingBy { it }.eachCount()
            .entries.joinToString(", ") { (name, count) -> if (count > 1) "$name x$count" else name }

    private fun routes(beans: ListableBeanFactory): String {
        val map = beans.getBeansOfType(RouteMap::class.java, true, false).values.firstOrNull()
        return if (map == null || map.isEmpty) "none" else "${map.size} routes"
    }

    /** Which adapter this host runs, by the name of the bean the web auto-configuration declares. */
    private fun adapter(beans: ListableBeanFactory): String = ADAPTERS.filter { (_, bean) -> beans.containsBean(bean) }
        .joinToString("+") { it.first }.ifEmpty { "none" }

    private val ADAPTERS = listOf(
        "servlet-filter" to "cosecPipelineServletFilter",
        "webflux-filter" to "cosecPipelineWebFilter",
        "gateway-filter" to "cosecPipelineGatewayFilter",
    )
}
