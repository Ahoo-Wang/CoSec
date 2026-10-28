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

import com.auth0.jwt.algorithms.Algorithm
import me.ahoo.cosec.apikey.ApiKeyReader
import me.ahoo.cosec.apikey.ApiKeyStore
import me.ahoo.cosec.apikey.InMemoryApiKeyStore
import me.ahoo.cosec.jwt.JwtTokenVerifier
import me.ahoo.cosec.membership.MembershipProvider
import me.ahoo.cosec.route.RouteMap
import me.ahoo.cosec.route.RouteMapCodec
import me.ahoo.cosec.runtime.AuthorizationChecker
import me.ahoo.cosec.runtime.Pipeline
import me.ahoo.cosec.runtime.PipelineComponents
import me.ahoo.cosec.runtime.PipelineOptions
import me.ahoo.cosec.runtime.attribute.AttributeProvider
import me.ahoo.cosec.runtime.attribute.AttributeProviders
import me.ahoo.cosec.runtime.authn.ApiKeyAuthenticator
import me.ahoo.cosec.runtime.authn.Authenticator
import me.ahoo.cosec.runtime.authn.AuthnResult
import me.ahoo.cosec.runtime.authn.CompositeAuthenticator
import me.ahoo.cosec.runtime.authn.TokenAuthenticator
import me.ahoo.cosec.runtime.event.DecisionListener
import me.ahoo.cosec.runtime.event.DecisionListeners
import me.ahoo.cosec.runtime.ratelimit.RateLimiter
import me.ahoo.cosec.runtime.snapshot.SuspendingSnapshotSource
import me.ahoo.cosec.spring.boot.starter.ConditionalOnCoSecEnabled
import me.ahoo.cosec.spring.boot.starter.apikey.ApiKeyProperties
import me.ahoo.cosec.spring.boot.starter.jwt.JwtProperties
import me.ahoo.cosec.token.RevocationReader
import me.ahoo.cosec.token.TokenStore
import me.ahoo.cosid.StringIdGenerator
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.core.env.Environment
import org.springframework.core.io.ResourceLoader
import org.springframework.util.ClassUtils
import kotlin.time.toKotlinDuration

/**
 * The 8.0 request pipeline (RFC 0006 §7, RFC 0008 S1): authentication (token with a real revocation reader, API key),
 * memberships, external attributes, decision listeners, rate limiting, the route map and the policy snapshot views,
 * assembled into one [Pipeline] that the web adapters bridge to.
 */
@AutoConfiguration(after = [CoSecSnapshotAutoConfiguration::class])
@ConditionalOnCoSecEnabled
@EnableConfigurationProperties(
    PipelineProperties::class,
    RuntimeAuthorizationProperties::class,
    RevocationProperties::class,
    RequiredListenerProperties::class,
)
class CoSecPipelineAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    fun cosecAttributeProviders(providers: ObjectProvider<AttributeProvider>): AttributeProviders =
        AttributeProviders(providers.orderedStream().toList())

    @Bean(destroyMethod = "cancel")
    @ConditionalOnMissingBean
    fun cosecDecisionListeners(
        listeners: ObjectProvider<DecisionListener>,
        requiredListenerProperties: RequiredListenerProperties,
    ): DecisionListeners =
        DecisionListeners(listeners.orderedStream().toList(), requiredListenerProperties.timeout.toKotlinDuration())

    /**
     * Token and API-key authentication. A token is verified by the JWT algorithm and checked against the revocation
     * reader [revocationReader] resolves; an API key, when `cosec.api-key.enabled`, by the configured key store.
     */
    @Bean
    @ConditionalOnMissingBean
    fun cosecAuthenticator(
        algorithm: ObjectProvider<Algorithm>,
        revocationReader: ObjectProvider<RevocationReader>,
        tokenStore: ObjectProvider<TokenStore>,
        apiKeyReader: ObjectProvider<ApiKeyReader>,
        apiKeyStore: ObjectProvider<ApiKeyStore>,
        revocationProperties: RevocationProperties,
        environment: Environment,
    ): Authenticator {
        val token = algorithm.ifAvailable?.let {
            TokenAuthenticator(
                JwtTokenVerifier(it),
                revocationReader(revocationReader, tokenStore),
                revocationSkew = revocationProperties.skew,
            )
        } ?: NO_TOKEN
        val apiKeys = if (environment.getProperty(ApiKeyProperties.ENABLED_KEY, Boolean::class.java, false)) {
            ApiKeyAuthenticator(apiKeyReader(apiKeyReader, apiKeyStore))
        } else {
            null
        }
        return CompositeAuthenticator(token, apiKeys)
    }

    /**
     * The route maps of `cosec.authorization.routes.location`, loaded at once (RFC 0005 §5.3): a missing or invalid one
     * fails startup, naming its location.
     */
    @Bean
    @ConditionalOnMissingBean
    fun cosecRouteMap(properties: RuntimeAuthorizationProperties, resourceLoader: ResourceLoader): RouteMap =
        // A gateway authorizes its routes' requests in the http namespace (RFC 0005 §5.3): it loads no route map.
        if (ClassUtils.isPresent(GATEWAY_GLOBAL_FILTER, null)) {
            RouteMap.EMPTY
        } else {
            RouteMaps.load(properties.routes.location, resourceLoader)
        }

    @Bean
    @ConditionalOnMissingBean
    fun cosecPipeline(
        authenticator: Authenticator,
        snapshots: SuspendingSnapshotSource,
        membershipProvider: ObjectProvider<MembershipProvider>,
        idGenerator: ObjectProvider<StringIdGenerator>,
        attributes: AttributeProviders,
        listeners: DecisionListeners,
        rateLimiter: ObjectProvider<RateLimiter>,
        routes: RouteMap,
        pipelineProperties: PipelineProperties,
        authorizationProperties: RuntimeAuthorizationProperties,
    ): Pipeline = Pipeline(
        PipelineComponents(
            authenticator = authenticator,
            snapshots = snapshots,
            membershipProvider = membershipProvider.ifAvailable,
            idGenerator = idGenerator.ifAvailable,
            options = PipelineOptions(
                timeout = pipelineProperties.timeout.toKotlinDuration(),
                authenticationTimeout = pipelineProperties.authenticationTimeout.toKotlinDuration(),
                membershipTimeout = authorizationProperties.membership.timeout.toKotlinDuration(),
                attributeTimeout = authorizationProperties.attributes.timeout.toKotlinDuration(),
                rateLimitTimeout = authorizationProperties.rateLimit.timeout.toKotlinDuration(),
                allowQueryToken = pipelineProperties.allowQueryToken,
                apiKeyHeader = pipelineProperties.apiKeyHeader,
                traceLevel = pipelineProperties.traceLevel,
            ),
            attributes = attributes,
            listeners = listeners,
            rateLimiter = rateLimiter.ifAvailable,
            routes = routes,
        ),
    )

    /** Checks further actions as the principal of a permitted request (RFC 0006 §7.6). */
    @Bean
    @ConditionalOnMissingBean
    fun cosecAuthorizationChecker(pipeline: Pipeline): AuthorizationChecker = AuthorizationChecker(pipeline)

    companion object {
        /** No token verifier: a request without a token is anonymous, one with a token is rejected. */
        private val NO_TOKEN = Authenticator { credentials ->
            if (credentials.token == null) {
                AuthnResult.Anonymous
            } else {
                AuthnResult.Rejected(AuthnResult.RejectReason.TOKEN_INVALID)
            }
        }

        /**
         * The reader of revoked tokens and subjects, always (RFC 0008 S1b): a [RevocationReader] bean, else the CoCache
         * token store's. With neither, startup fails: the starter never reads revocations from nothing.
         */
        internal fun revocationReader(
            reader: ObjectProvider<RevocationReader>,
            tokenStore: ObjectProvider<TokenStore>,
        ): RevocationReader {
            reader.ifAvailable?.let { return it }
            return checkNotNull(CoCacheRevocation.readerOf(tokenStore.ifAvailable)) {
                "Tokens are verified, but no revocation reader is available: add cosec-cocache (the starter's " +
                    "cacheSupport feature) or a RevocationReader bean, or set ${JwtProperties.PREFIX}.enabled=false."
            }
        }

        /** The API keys the pipeline reads: an [ApiKeyReader] bean, else the in-memory store's. */
        internal fun apiKeyReader(
            reader: ObjectProvider<ApiKeyReader>,
            store: ObjectProvider<ApiKeyStore>
        ): ApiKeyReader {
            reader.ifAvailable?.let { return it }
            val keys = store.ifAvailable
            check(keys is InMemoryApiKeyStore) {
                "API keys are enabled, but no ApiKeyReader is available for the store ${keys?.javaClass?.name}: " +
                    "with cosec.api-key.store=redis, a ReactiveStringRedisTemplate is needed."
            }
            return keys.reader
        }
    }
}

/** `CoCacheTokenStore.reader`, when `cosec-cocache` is on the classpath. */
internal object CoCacheRevocation {
    private val available = ClassUtils.isPresent("me.ahoo.cosec.cache.CoCacheTokenStore", null)

    fun readerOf(store: TokenStore?): RevocationReader? =
        if (available && store is me.ahoo.cosec.cache.CoCacheTokenStore) store.reader else null
}

/** Loads the route maps of a comma-separated list of resource locations, all or nothing. */
internal object RouteMaps {
    fun load(locations: String, resourceLoader: ResourceLoader): RouteMap {
        val sources = locations.split(',').map { it.trim() }.filter { it.isNotEmpty() }.map { location ->
            val resource = resourceLoader.getResource(location)
            check(resource.exists()) {
                "The route map [$location] (${RuntimeAuthorizationProperties.PREFIX}.routes.location) does not exist; " +
                    "set the property empty to run without one."
            }
            location to resource.inputStream.use { it.readBytes() }
        }
        return if (sources.isEmpty()) RouteMap.EMPTY else RouteMapCodec.load(sources)
    }
}
