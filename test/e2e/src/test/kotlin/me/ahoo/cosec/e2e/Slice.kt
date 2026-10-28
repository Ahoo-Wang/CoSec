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

package me.ahoo.cosec.e2e

import me.ahoo.cocache.spring.boot.CoCacheAutoConfiguration
import me.ahoo.cosec.cache.snapshot.PolicySnapshotCache
import me.ahoo.cosec.cache.snapshot.RedisSnapshotDocumentStore
import me.ahoo.cosec.engine.flat.CompiledDocument
import me.ahoo.cosec.model.Memberships
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.RoleId
import me.ahoo.cosec.model.SpaceId
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.model.scope.HostScope
import me.ahoo.cosec.runtime.Outcome
import me.ahoo.cosec.runtime.Pipeline
import me.ahoo.cosec.runtime.attribute.AttributeProviders
import me.ahoo.cosec.runtime.event.DecisionListeners
import me.ahoo.cosec.runtime.http.CoSecParameters
import me.ahoo.cosec.runtime.http.Cookies
import me.ahoo.cosec.runtime.http.Headers
import me.ahoo.cosec.runtime.http.HttpFacts
import me.ahoo.cosec.runtime.snapshot.SnapshotOptions
import me.ahoo.cosec.runtime.snapshot.SnapshotSource
import me.ahoo.cosec.spi.authn.AuthenticatedPrincipal
import me.ahoo.cosec.spi.authn.Authenticator
import me.ahoo.cosec.spi.authn.AuthnResult
import me.ahoo.cosec.spi.snapshot.SnapshotDocument
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.cosec.spring.boot.starter.pipeline.CoSecPipelineAutoConfiguration
import me.ahoo.cosec.spring.boot.starter.pipeline.CoSecPipelineIntegrationAutoConfiguration
import me.ahoo.cosec.spring.boot.starter.pipeline.CoSecSnapshotAutoConfiguration
import org.springframework.boot.Banner
import org.springframework.boot.WebApplicationType
import org.springframework.boot.autoconfigure.ImportAutoConfiguration
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration
import org.springframework.context.ApplicationContextInitializer
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.Configuration
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * The 8.0 path as the starter wires it (RFC 0008 §4.4.3, S1): the starter's snapshot and pipeline auto-configuration
 * over a CoCache runtime on Redis ([redisUri]), with this slice's test authenticator, attribute providers and
 * listeners in place of the starter's. Each slice has its own key prefix, so slices never see each other's documents.
 */
class Slice(
    redisUri: String = REDIS,
    options: SnapshotOptions = SnapshotOptions(revalidateInterval = Duration.ofMillis(200)),
    val keyPrefix: String = "e2e:${UUID.randomUUID()}",
    /** The external attribute providers: the documents compile against their catalog, the pipeline fetches them. */
    attributes: AttributeProviders = AttributeProviders.NONE,
    /** The decision listeners; the slice closes them. */
    private val listeners: DecisionListeners = DecisionListeners.NONE,
) : AutoCloseable {
    private val context: ConfigurableApplicationContext = SpringApplicationBuilder(SliceConfiguration::class.java)
        .web(WebApplicationType.NONE)
        .bannerMode(Banner.Mode.OFF)
        .logStartupInfo(false)
        .initializers(
            ApplicationContextInitializer<ConfigurableApplicationContext> {
                it.beanFactory.registerSingleton("cosecAuthenticator", TokenAuthenticator)
                it.beanFactory.registerSingleton("cosecAttributeProviders", attributes)
                it.beanFactory.registerSingleton("cosecDecisionListeners", listeners)
            },
        )
        .properties(
            "spring.data.redis.url=$redisUri",
            "cosec.authorization.snapshot.key-prefix=$keyPrefix",
            "cosec.authorization.snapshot.revalidate-interval=${options.revalidateInterval.toMillis()}ms",
            "cosec.authorization.snapshot.cold-load-timeout=${options.coldLoadTimeout.toMillis()}ms",
            "cosec.authorization.routes.location=",
            "cosec.jwt.enabled=false",
        )
        .run()
    val cache: PolicySnapshotCache = context.getBean(PolicySnapshotCache::class.java)
    val store: RedisSnapshotDocumentStore = context.getBean(RedisSnapshotDocumentStore::class.java)

    @Suppress("UNCHECKED_CAST")
    val source: SnapshotSource<CompiledDocument, CompiledDocument> =
        context.getBean(SnapshotSource::class.java) as SnapshotSource<CompiledDocument, CompiledDocument>
    val pipeline: Pipeline = context.getBean(Pipeline::class.java)
    private val revisions = ConcurrentHashMap<SnapshotKey, Long>()

    /**
     * Publishes the next revision of [key]: [body] is the document's sections after the header (RFC 0004 §7.1). It
     * returns once this slice's source has read it: the starter loads the platform at startup, so without the wait a
     * request right after could still see it absent.
     */
    fun publish(key: SnapshotKey, body: String) {
        val revision = revisions.merge(key, 1, Long::plus)!!
        val publishedAt = Instant.now()
        val text = """{"schema":2,"revision":$revision,"publishedAt":"$publishedAt"$body}"""
        cache.put(key, SnapshotDocument(2, revision, publishedAt, text.encodeToByteArray()))
        source.reload(key).join()
    }

    suspend fun evaluate(facts: HttpFacts): Outcome = pipeline.evaluate(facts)

    override fun close() {
        listeners.cancel()
        context.close()
    }

    /** The starter's 8.0 auto-configuration, and no other: not the 7.x path the starter still registers until S1b. */
    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration(
        JacksonAutoConfiguration::class,
        DataRedisAutoConfiguration::class,
        CoCacheAutoConfiguration::class,
        CoSecPipelineIntegrationAutoConfiguration::class,
        CoSecSnapshotAutoConfiguration::class,
        CoSecPipelineAutoConfiguration::class,
    )
    class SliceConfiguration

    companion object {
        const val REDIS = "redis://localhost:6379"
    }
}

/**
 * Tokens of the form `<principal>@<tenant>:<role>,<role>|<attached policy>,<attached policy>`, roles in the default
 * space; `invalid` is a rejected token. The slice is about the snapshot path; real JWT authentication joins with
 * RFC 0006 PR-3.
 */
object TokenAuthenticator : Authenticator {
    override suspend fun authenticate(credentials: me.ahoo.cosec.spi.authn.Credentials): AuthnResult {
        val token = credentials.token?.removePrefix("Bearer ") ?: return AuthnResult.Anonymous
        if (token == "invalid") {
            return AuthnResult.Rejected(AuthnResult.RejectReason.TOKEN_INVALID)
        }
        val (claims, attached) = token.split('|', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
        val (who, roles) = claims.split(':', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
        val (principal, tenant) = who.split('@', limit = 2).let { it[0] to it[1] }
        val memberships = Memberships(
            Memberships.Source.CLAIMS,
            mapOf(SpaceId.DEFAULT to roles.split(',').filter { it.isNotEmpty() }.mapTo(LinkedHashSet(), ::RoleId)),
            attached.split(',').filter { it.isNotEmpty() }.map(::PolicyId),
        )
        return AuthnResult.Authenticated(
            AuthenticatedPrincipal(PrincipalId(principal), TenantId(tenant), memberships),
            "test"
        )
    }
}

/** A request as the bridge will hand it to the pipeline. */
fun request(
    method: String,
    path: String,
    token: String? = null,
    app: String? = null,
    space: String? = null,
    query: String? = null,
): HttpFacts {
    val headers = buildMap<String, List<String>> {
        token?.let { put("Authorization", listOf("Bearer $it")) }
        app?.let { put(CoSecParameters.APP_ID, listOf(it)) }
        space?.let { put(CoSecParameters.SPACE_ID, listOf(it)) }
    }
    return HttpFacts(method, path, query, Headers.of(headers), Cookies.EMPTY, "10.0.0.1", HostScope(null))
}
