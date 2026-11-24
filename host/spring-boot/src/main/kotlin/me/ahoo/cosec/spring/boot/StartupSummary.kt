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
import me.ahoo.cosec.jwt.JwtSigning
import me.ahoo.cosec.jwt.JwtTokenVerifier
import me.ahoo.cosec.jwt.VerifiedTokenCache
import me.ahoo.cosec.login.CompositeAuthentication
import me.ahoo.cosec.runtime.authn.CompositeAuthenticator
import me.ahoo.cosec.runtime.event.DecisionListeners
import me.ahoo.cosec.spi.apikey.ApiKeyReader
import me.ahoo.cosec.spi.apikey.InMemoryApiKeyStore
import me.ahoo.cosec.spi.authn.Authenticator
import me.ahoo.cosec.spi.ratelimit.RateLimiter
import me.ahoo.cosec.spi.shadow.InstanceStatus
import me.ahoo.cosec.spi.token.RevocationReader
import me.ahoo.cosec.spi.token.TokenStore
import me.ahoo.cosec.spi.token.TokenVerifier
import me.ahoo.cosec.spring.boot.apikey.apiKeysEnabled
import me.ahoo.cosec.spring.boot.pipeline.CoSecPipelineAutoConfiguration
import me.ahoo.cosec.spring.boot.pipeline.InstanceReporting
import org.springframework.beans.factory.ListableBeanFactory
import org.springframework.core.env.Environment

/**
 * UB-5: one line at the end of startup that says what CoSec is configured as, so an operator reading a log can tell the
 * instance they deployed from the one they meant to: the mode, the web adapter, the token verifier, its cache and the
 * login, the revocation reader, API keys, the rate limiter, the decision listeners and the route map. When CoSec is off, one WARN
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
            val replaced = beans.getBeanProvider(Authenticator::class.java).ifAvailable
                ?.takeIf { it !is CompositeAuthenticator }
            if (replaced != null) {
                // The application's bean is the only authenticator the pipeline asks (extending.md): nothing the starter
                // would have built for tokens, revocations or API keys is in force, so none of it is listed.
                add("authenticator=${named(replaced)} (replaces CoSec's: tokens, revocation and API keys are its own)")
            } else {
                addAll(tokens(beans))
            }
            add("login=${if (names(beans, CompositeAuthentication::class.java).isEmpty()) "off" else "on"}")
            if (replaced == null) {
                add("api-keys=${apiKeys(beans, environment)}")
            }
            add("rate-limiter=${beans.getBeanProvider(RateLimiter::class.java).ifAvailable?.let(::named) ?: "none"}")
            add("listeners=${listeners(beans)}")
            add("route-map=${routes(beans)}")
            add("status=${status(beans)}")
            CoSecSwitches.switchedOff(environment).takeIf { it.isNotEmpty() }?.let {
                add("switched-off-by-configuration=$it")
            }
        }
        return Report.On("CoSec${version()}: " + parts.joinToString(", "))
    }

    /**
     * What a request meets, resolved as the authenticator resolves it ([CoSecPipelineAutoConfiguration]): tokens are on
     * when a `TokenVerifier` bean or a `JwtSigning` exists, verified by the application's `TokenVerifier` else CoSec's
     * own, against the reader [CoSecPipelineAutoConfiguration.revocationReader] picks: a `RevocationReader` bean, else
     * the CoCache token store's. Nothing about revocations when no token is verified.
     */
    private fun tokens(beans: ListableBeanFactory): List<String> {
        val own = beans.getBeanProvider(TokenVerifier::class.java).ifAvailable
        if (own == null && beans.getBeanProvider(JwtSigning::class.java).ifAvailable == null) {
            return listOf("jwt=off")
        }
        val verifier = own?.let { "bean ${named(it)}" } ?: "JwtTokenVerifier"
        val readers = beans.getBeanProvider(RevocationReader::class.java)
        val reader = CoSecPipelineAutoConfiguration.revocationReader(
            readers,
            beans.getBeanProvider(TokenStore::class.java),
            beans,
        )
        val which = when {
            reader === RevocationReader.NONE -> "RevocationReader.NONE (revoked tokens are accepted until they expire)"
            readers.ifAvailable != null -> "bean ${named(reader)}"
            else -> "the CoCache token store's (${named(reader)})"
        }
        return listOf("jwt=on ($verifier)", "token-cache=${tokenCache(beans, own)}", "revocation-reader=$which")
    }

    /**
     * Whether the authenticator answers a request's token from the verified-token cache, as
     * [CoSecPipelineAutoConfiguration.cachedVerifier] decides (R2B-22): CoSec's own verifier only.
     */
    private fun tokenCache(beans: ListableBeanFactory, verifier: TokenVerifier?): String {
        val cache = beans.getBeanProvider(VerifiedTokenCache::class.java).ifAvailable
        return when {
            cache == null -> "off"
            verifier != null && verifier !is JwtTokenVerifier -> "off (the application's own verifier)"
            else -> "on (max-bytes=${cache.maxBytes})"
        }
    }

    private fun apiKeys(beans: ListableBeanFactory, environment: Environment): String {
        if (!apiKeysEnabled(environment)) {
            return "off"
        }
        val readers = beans.getBeanProvider(ApiKeyReader::class.java)
        val reader = CoSecPipelineAutoConfiguration.apiKeyReader(
            readers,
            beans.getBeanProvider(InMemoryApiKeyStore::class.java),
        )
        return if (readers.ifAvailable != null) "on (bean ${named(reader)})" else "on (the in-memory store)"
    }

    /** The listeners the pipeline delivers to, as `DecisionListeners` holds them. */
    private fun listeners(beans: ListableBeanFactory): String =
        beans.getBeanProvider(DecisionListeners::class.java).ifAvailable
            ?.stats?.joinToString(", ", "[", "]") { it.listener } ?: "[]"

    /** The status this instance reports (`SNP-25`): its instance id and release, or none without `cosec-cocache`. */
    private fun status(beans: ListableBeanFactory): String =
        beans.getBeanProvider(InstanceReporting::class.java).ifAvailable
            ?.let { "${it.reporter.instance} (release ${InstanceStatus.RELEASE ?: "unknown"})" }
            ?: "none (no shared store: the control plane cannot see this instance)"

    private fun named(bean: Any): String = bean.javaClass.simpleName.ifEmpty { bean.javaClass.name }

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
