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
import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.cosec.jwt.JwtTokenVerifier
import me.ahoo.cosec.runtime.AuthorizationChecker
import me.ahoo.cosec.runtime.Pipeline
import me.ahoo.cosec.runtime.PipelineComponents
import me.ahoo.cosec.runtime.PipelineOptions
import me.ahoo.cosec.runtime.attribute.AttributeProviders
import me.ahoo.cosec.runtime.attribute.BlacklistAttributeProvider
import me.ahoo.cosec.runtime.audit.AuditListener
import me.ahoo.cosec.runtime.authn.ApiKeyAuthenticator
import me.ahoo.cosec.runtime.authn.CompositeAuthenticator
import me.ahoo.cosec.runtime.authn.TokenAuthenticator
import me.ahoo.cosec.runtime.event.DecisionListener
import me.ahoo.cosec.runtime.event.DecisionListeners
import me.ahoo.cosec.runtime.event.Delivery
import me.ahoo.cosec.runtime.identity.IdentityIssuer
import me.ahoo.cosec.runtime.route.RouteMap
import me.ahoo.cosec.runtime.route.RouteMapCodec
import me.ahoo.cosec.runtime.snapshot.SuspendingSnapshotSource
import me.ahoo.cosec.spi.apikey.ApiKeyReader
import me.ahoo.cosec.spi.apikey.ApiKeyStore
import me.ahoo.cosec.spi.apikey.InMemoryApiKeyStore
import me.ahoo.cosec.spi.attribute.AttributeProvider
import me.ahoo.cosec.spi.audit.AuditEventSink
import me.ahoo.cosec.spi.authn.Authenticator
import me.ahoo.cosec.spi.authn.AuthnResult
import me.ahoo.cosec.spi.membership.MembershipProvider
import me.ahoo.cosec.spi.ratelimit.RateLimiter
import me.ahoo.cosec.spi.token.RevocationReader
import me.ahoo.cosec.spi.token.TokenStore
import me.ahoo.cosec.spring.boot.starter.ConditionalOnCoSecEnabled
import me.ahoo.cosec.spring.boot.starter.apikey.ApiKeyProperties
import me.ahoo.cosec.spring.boot.starter.jwt.JwtProperties
import me.ahoo.cosid.StringIdGenerator
import org.springframework.beans.factory.ListableBeanFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.annotation.AnnotationAwareOrderComparator
import org.springframework.core.env.Environment
import org.springframework.core.io.ResourceLoader
import org.springframework.util.ClassUtils
import java.util.IdentityHashMap
import kotlin.time.toKotlinDuration

private val log = KotlinLogging.logger {}

/**
 * The 8.0 request pipeline (RFC 0006 §7, RFC 0008 S1): authentication (token with a real revocation reader, API key),
 * memberships, external attributes, decision listeners, rate limiting, the route map and the policy snapshot views,
 * assembled into one [Pipeline] that the web adapters bridge to.
 */
@AutoConfiguration(after = [CoSecSnapshotAutoConfiguration::class])
@ConditionalOnCoSecEnabled
@ConditionalOnEnforceMode
@EnableConfigurationProperties(
    PipelineProperties::class,
    RuntimeAuthorizationProperties::class,
    RevocationProperties::class,
    RequiredListenerProperties::class,
    ListenerProperties::class,
)
class CoSecPipelineAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    fun cosecAttributeProviders(providers: ObjectProvider<AttributeProvider>): AttributeProviders {
        val all = providers.orderedStream().toList()
        if (all.none { it is BlacklistAttributeProvider }) {
            log.info { "No BlacklistSource bean: requests are not checked against a blacklist (ext.blacklist.*)." }
        }
        return AttributeProviders(all)
    }

    /**
     * Every [DecisionListener] bean, and each [AuditEventSink] bean that is not also one as a BEST_EFFORT [AuditListener]
     * named after the bean (RFC 0006 §9.5.3), so a sink written for 7.x keeps receiving events. All in `@Order`.
     * On shutdown, delivers what is queued for up to `cosec.observability.listener.drain-timeout`, then drops the rest.
     * `cosec.audit.kafka.delivery=required` asks for a REQUIRED Kafka record of every decision: startup fails when that
     * listener was not created, rather than deciding without it (STA-1).
     */
    @Bean(destroyMethod = "drain")
    @ConditionalOnMissingBean
    fun cosecDecisionListeners(
        listeners: ObjectProvider<DecisionListener>,
        sinks: ObjectProvider<AuditEventSink>,
        beans: ListableBeanFactory,
        requiredListenerProperties: RequiredListenerProperties,
        listenerProperties: ListenerProperties,
        environment: Environment,
    ): DecisionListeners {
        val names = IdentityHashMap<AuditEventSink, String>()
        beans.getBeansOfType(AuditEventSink::class.java).forEach { (name, sink) -> names[sink] = name }
        // Each kind in its own @Order (classes and @Bean methods alike); a stable sort then orders the two together.
        val all = listeners.orderedStream().toList().map { it to it } +
            sinks.orderedStream().toList().filter { it !is DecisionListener }
                .map { it to AuditListener.of(it, names.getValue(it)) }
        val ordered = all.sortedWith { a, b -> AnnotationAwareOrderComparator.INSTANCE.compare(a.first, b.first) }
        val requiredKafka = environment.getProperty(KAFKA_AUDIT_DELIVERY_KEY).equals("required", ignoreCase = true)
        check(
            !requiredKafka ||
                ordered.any { it.second.name == KAFKA_AUDIT_LISTENER && it.second.delivery == Delivery.REQUIRED }
        ) {
            "$KAFKA_AUDIT_DELIVERY_KEY=required, but no Kafka audit listener was created: it needs cosec-kafka, a " +
                "KafkaOperations bean (spring-boot-starter-kafka), cosec.audit.enabled=true and " +
                "cosec.audit.kafka.enabled=true (STA-1)."
        }
        return DecisionListeners(
            ordered.map { it.second },
            requiredListenerProperties.timeout.toKotlinDuration(),
            queueCapacity = listenerProperties.queueCapacity,
            drainTimeout = listenerProperties.drainTimeout.toKotlinDuration(),
        )
    }

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
        identity: ObjectProvider<IdentityIssuer>,
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
            identity = identity.ifAvailable,
        ),
    )

    /**
     * At a gateway: forwards the identity it verified to the services behind it (`CoSec-Identity`) and strips the
     * client's, unless `cosec.gateway.identity.enabled=false`.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = [GATEWAY_GLOBAL_FILTER])
    @ConditionalOnProperty(
        value = [GatewayIdentityProperties.PREFIX + ".enabled"],
        havingValue = "true",
        matchIfMissing = true
    )
    @EnableConfigurationProperties(GatewayIdentityProperties::class)
    class GatewayIdentity {
        @Bean
        @ConditionalOnMissingBean
        fun cosecIdentityIssuer(properties: GatewayIdentityProperties): IdentityIssuer =
            IdentityIssuer(properties.claims, properties.maxSize.toBytes().toInt())
    }

    /** Checks further actions as the principal of a permitted request (RFC 0006 §7.6). */
    @Bean
    @ConditionalOnMissingBean
    fun cosecAuthorizationChecker(pipeline: Pipeline): AuthorizationChecker = AuthorizationChecker(pipeline)

    companion object {
        /** `KafkaAuditListener.NAME`, as a literal: `cosec-kafka` is optional. */
        private const val KAFKA_AUDIT_LISTENER = "kafka-audit"
        private const val KAFKA_AUDIT_DELIVERY_KEY = "cosec.audit.kafka.delivery"

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
                "Tokens are verified, but no revocation reader is available: add cosec-cocache " +
                    "or a RevocationReader bean, or set ${JwtProperties.PREFIX}.enabled=false."
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
