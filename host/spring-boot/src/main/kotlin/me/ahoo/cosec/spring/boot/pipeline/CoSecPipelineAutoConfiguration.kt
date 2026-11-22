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

@file:OptIn(InternalCoSecApi::class, ExperimentalCoSecApi::class)

package me.ahoo.cosec.spring.boot.pipeline

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.document.route.RouteMap
import me.ahoo.cosec.jwt.JwtSigning
import me.ahoo.cosec.jwt.JwtTokenVerifier
import me.ahoo.cosec.jwt.VerifiedTokenCache
import me.ahoo.cosec.runtime.AuthorizationChecker
import me.ahoo.cosec.runtime.Pipeline
import me.ahoo.cosec.runtime.PipelineOptions
import me.ahoo.cosec.runtime.attribute.AttributeProviders
import me.ahoo.cosec.runtime.attribute.BlacklistAttributeProvider
import me.ahoo.cosec.runtime.audit.AuditListener
import me.ahoo.cosec.runtime.authn.ApiKeyAuthenticator
import me.ahoo.cosec.runtime.authn.CompositeAuthenticator
import me.ahoo.cosec.runtime.authn.TokenAuthenticator
import me.ahoo.cosec.runtime.authn.UncheckedRevocations
import me.ahoo.cosec.runtime.event.DecisionListener
import me.ahoo.cosec.runtime.event.DecisionListeners
import me.ahoo.cosec.runtime.event.Delivery
import me.ahoo.cosec.runtime.identity.IdentityIssuer
import me.ahoo.cosec.runtime.snapshot.SuspendingSnapshotSource
import me.ahoo.cosec.spi.apikey.ApiKeyReader
import me.ahoo.cosec.spi.apikey.InMemoryApiKeyStore
import me.ahoo.cosec.spi.attribute.AttributeProvider
import me.ahoo.cosec.spi.audit.AuditEventSink
import me.ahoo.cosec.spi.authn.Authenticator
import me.ahoo.cosec.spi.authn.AuthnResult
import me.ahoo.cosec.spi.id.IdGenerator
import me.ahoo.cosec.spi.id.RandomIdGenerator
import me.ahoo.cosec.spi.membership.MembershipProvider
import me.ahoo.cosec.spi.ratelimit.RateLimiter
import me.ahoo.cosec.spi.token.RevocationReader
import me.ahoo.cosec.spi.token.TokenStore
import me.ahoo.cosec.spi.token.TokenVerifier
import me.ahoo.cosec.spring.boot.ConditionalOnCoSecEnabled
import me.ahoo.cosec.spring.boot.apikey.apiKeysEnabled
import me.ahoo.cosec.spring.boot.audit.kafka.KafkaAuditProperties
import me.ahoo.cosec.spring.boot.audit.kafka.kafkaAuditDelivery
import me.ahoo.cosec.spring.boot.jwt.TokenStores
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
    TokenCacheProperties::class,
    ListenerProperties::class,
)
@InternalCoSecApi
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
     * named after the bean (RFC 0006 §9.5.3), so a sink needs no listener of its own. All in `@Order`.
     * On shutdown, delivers what is queued for up to a sixth of the shutdown phase (`OBS-10`), then drops the rest.
     * `cosec.audit.kafka.delivery=required` asks for a REQUIRED Kafka record of every decision: startup fails when that
     * listener was not created, rather than deciding without it (STA-1).
     */
    @Bean(destroyMethod = "drain")
    @ConditionalOnMissingBean
    fun cosecDecisionListeners(
        listeners: ObjectProvider<DecisionListener>,
        sinks: ObjectProvider<AuditEventSink>,
        beans: ListableBeanFactory,
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
        val requiredKafka = kafkaAuditDelivery(environment) == Delivery.REQUIRED
        check(
            !requiredKafka ||
                ordered.any { it.second.name == KAFKA_AUDIT_LISTENER && it.second.delivery == Delivery.REQUIRED }
        ) {
            "${KafkaAuditProperties.DELIVERY_KEY}=required, but no Kafka audit listener was created: it needs cosec-kafka, a " +
                "KafkaOperations bean (spring-boot-starter-kafka), cosec.audit.enabled=true and " +
                "cosec.audit.kafka.enabled=true (STA-1)."
        }
        return DecisionListeners(
            ordered.map { it.second },
            DerivedTimeouts.budgets(environment).requiredListener,
            queueCapacity = listenerProperties.queueCapacity,
            drainTimeout = DerivedTimeouts.drain(environment).toKotlinDuration(),
        )
    }

    /** The tokens accepted without a revocation check while the store cannot be read (`cosec.revocation.unchecked`). */
    @Bean
    @ConditionalOnMissingBean
    fun cosecUncheckedRevocations(): UncheckedRevocations = UncheckedRevocations()

    /**
     * The verified tokens of CoSec's own verifier (R2B-22, `IDN-32`), unless `cosec.authentication.token-cache.enabled`
     * is false: the authenticator verifies with it only when the verifier is CoSec's [JwtTokenVerifier].
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(value = [TokenCacheProperties.ENABLED_KEY], havingValue = "true", matchIfMissing = true)
    fun cosecVerifiedTokenCache(properties: TokenCacheProperties): VerifiedTokenCache =
        VerifiedTokenCache(properties.maxEntries)

    /**
     * Token and API-key authentication. A token is verified by the application's [TokenVerifier] bean, the one refresh
     * and logout verify with (`IDN-25`), else by CoSec's own over the [JwtSigning], and checked against the revocation
     * reader [revocationReader] resolves; an API key, when `cosec.api-key.enabled`, by the configured key store.
     * Tokens are on when either exists: an application whose tokens another issuer signs declares its verifier and no
     * key, and a key alone gives CoSec's own verifier. With neither, a request with a token is refused (`token-invalid`).
     * A verifier alone makes no login or refresh route and no token converter: those hang on the signing key, which is
     * what issues tokens. CoSec's own verifier answers from [VerifiedTokenCache] when there is one ([cachedVerifier]);
     * an application's own `TokenVerifier` never does.
     */
    @Bean
    @ConditionalOnMissingBean
    fun cosecAuthenticator(
        signing: ObjectProvider<JwtSigning>,
        verifier: ObjectProvider<TokenVerifier>,
        revocationReader: ObjectProvider<RevocationReader>,
        tokenStore: ObjectProvider<TokenStore>,
        apiKeyReader: ObjectProvider<ApiKeyReader>,
        apiKeyStore: ObjectProvider<InMemoryApiKeyStore>,
        revocationProperties: RevocationProperties,
        environment: Environment,
        unchecked: ObjectProvider<UncheckedRevocations>,
        beans: ListableBeanFactory,
        tokenCache: ObjectProvider<VerifiedTokenCache>,
    ): Authenticator {
        val tokenVerifier = verifier.ifAvailable
            ?: signing.ifAvailable?.let { JwtTokenVerifier(it, revocationProperties.skew) }
        val token = tokenVerifier?.let { own ->
            val reader =
                revocationReader(revocationReader, tokenStore, beans, issuesTokens = signing.ifAvailable != null)
            if (reader === RevocationReader.NONE) {
                log.warn { NONE_WARNING }
            }
            TokenAuthenticator(
                cachedVerifier(own, tokenCache.ifAvailable),
                reader,
                revocationSkew = revocationProperties.skew,
                acceptUnavailableRevocations = !revocationProperties.failClosed,
                unchecked = unchecked.getIfAvailable { UncheckedRevocations() },
            )
        } ?: NO_TOKEN
        val apiKeys = if (apiKeysEnabled(environment)) {
            ApiKeyAuthenticator(apiKeyReader(apiKeyReader, apiKeyStore))
        } else {
            null
        }
        return CompositeAuthenticator(token, apiKeys)
    }

    /**
     * The route maps of `cosec.authorization.routes.location`, loaded at once (RFC 0005 §5.3): a missing, invalid or
     * empty one fails startup, naming its location. Unset, no map is loaded ([RouteMaps.load]).
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
        idGenerator: ObjectProvider<IdGenerator>,
        attributes: AttributeProviders,
        listeners: DecisionListeners,
        rateLimiter: ObjectProvider<RateLimiter>,
        routes: RouteMap,
        identity: ObjectProvider<IdentityIssuer>,
        pipelineProperties: PipelineProperties,
    ): Pipeline = Pipeline.builder(authenticator, snapshots)
        .options(
            PipelineOptions(
                timeout = pipelineProperties.timeout.toKotlinDuration(),
                allowQueryToken = pipelineProperties.allowQueryToken,
                allowCookieToken = pipelineProperties.allowCookieToken,
                apiKeyHeader = pipelineProperties.apiKeyHeader,
                traceLevel = pipelineProperties.traceLevel,
            ),
        )
        .attributes(attributes)
        .listeners(listeners)
        .routes(routes)
        .ids(idGenerator.getIfAvailable { RandomIdGenerator })
        .apply {
            membershipProvider.ifAvailable?.let(::memberships)
            rateLimiter.ifAvailable?.let(::rateLimiter)
            identity.ifAvailable?.let(::identity)
        }
        .build()

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
    @InternalCoSecApi
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

    @InternalCoSecApi
    companion object {
        /**
         * The verifier the authenticator verifies a request's token with: CoSec's own with [cache] (R2B-22), whose
         * answers are a function of the token, its key, its skew and its clock alone (`IDN-32`); any other verifier as
         * it is, since nothing says what an application's own reads besides the token.
         */
        internal fun cachedVerifier(verifier: TokenVerifier, cache: VerifiedTokenCache?): TokenVerifier =
            if (cache != null && verifier is JwtTokenVerifier) verifier.cached(cache) else verifier

        /** `KafkaAuditListener.NAME`, as a literal: `cosec-kafka` is optional. */
        private const val KAFKA_AUDIT_LISTENER = "kafka-audit"

        /** Logged once, as the authenticator is created, when the tokens it verifies are checked against nothing. */
        internal const val NONE_WARNING =
            "Tokens are verified against RevocationReader.NONE: a revoked token (logout, revoked sessions or " +
                "subjects) is accepted here until it expires. Where the issuer revokes tokens, read its " +
                "revocations: cosec-cocache on the issuer's Redis with the issuer's cosec.key-prefix, or a " +
                "RevocationReader of your own."

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
         * token store's. With neither, startup fails, naming every token store missing ([TokenStores]), or, for a host
         * that only verifies another issuer's tokens ([issuesTokens] false), the one reader: the starter never reads
         * revocations from nothing.
         */
        internal fun revocationReader(
            reader: ObjectProvider<RevocationReader>,
            tokenStore: ObjectProvider<TokenStore>,
            beans: ListableBeanFactory,
            issuesTokens: Boolean = true,
        ): RevocationReader {
            reader.ifAvailable?.let { return it }
            return CoCacheRevocation.readerOf(tokenStore.ifAvailable)
                ?: throw if (issuesTokens) TokenStores.failure(beans) else TokenStores.verifierOnlyFailure()
        }

        /** The API keys the pipeline reads: an [ApiKeyReader] bean, else the in-memory store's. */
        internal fun apiKeyReader(
            reader: ObjectProvider<ApiKeyReader>,
            store: ObjectProvider<InMemoryApiKeyStore>
        ): ApiKeyReader {
            reader.ifAvailable?.let { return it }
            val keys = checkNotNull(store.ifAvailable) {
                "API keys are enabled, but no ApiKeyReader is available: with cosec.api-key.store=redis, " +
                    "a ReactiveStringRedisTemplate is needed."
            }
            return keys.reader
        }
    }
}
