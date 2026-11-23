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

package me.ahoo.cosec.spring.boot.control

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.ahoo.cocache.spring.boot.CoCacheAutoConfiguration
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.change.ChangeId
import me.ahoo.cosec.control.change.ChangeState
import me.ahoo.cosec.control.http.HttpControlService
import me.ahoo.cosec.control.sdk.ControlCaller
import me.ahoo.cosec.control.sdk.ControlRefusedException
import me.ahoo.cosec.control.store.ControlStore
import me.ahoo.cosec.control.store.Publication
import me.ahoo.cosec.control.store.Revisions
import me.ahoo.cosec.document.Actor
import me.ahoo.cosec.document.PolicyDocumentCodec
import me.ahoo.cosec.document.ProvenanceKind
import me.ahoo.cosec.document.json.CoSecJson
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.model.decision.DocumentKind
import me.ahoo.cosec.spi.snapshot.SnapshotDigest
import me.ahoo.cosec.spi.snapshot.SnapshotDocument
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.cosec.spring.boot.jwt.CoSecJwtAutoConfiguration
import me.ahoo.cosec.spring.boot.jwt.CoSecTokenRevocationCacheAutoConfiguration
import me.ahoo.cosec.spring.boot.pipeline.CoSecGatewayModeAutoConfiguration
import me.ahoo.cosec.spring.boot.pipeline.CoSecPipelineAutoConfiguration
import me.ahoo.cosec.spring.boot.pipeline.CoSecPipelineIntegrationAutoConfiguration
import me.ahoo.cosec.spring.boot.pipeline.CoSecPipelineWebAutoConfiguration
import me.ahoo.cosec.spring.boot.pipeline.CoSecSnapshotAutoConfiguration
import me.ahoo.cosec.spring.boot.pipeline.GATEWAY_GLOBAL_FILTER
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration
import org.springframework.boot.data.redis.autoconfigure.DataRedisReactiveAutoConfiguration
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration
import org.springframework.boot.test.context.FilteredClassLoader
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner
import org.springframework.boot.webflux.autoconfigure.HttpHandlerAutoConfiguration
import org.springframework.boot.webflux.autoconfigure.WebFluxAutoConfiguration
import org.springframework.context.ApplicationContext
import java.net.URI
import java.net.http.HttpTimeoutException
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration
import kotlin.time.toKotlinDuration

/**
 * `CTL-82`: a control request has no deadline of its own. Its caller's deadline reaches the host by cancellation, over
 * the network as in process: a caller of [HttpControlService] that is cancelled, or whose call times out, closes its
 * exchange, the host's server cancels the request's coroutine, and a publish cancelled before its head write publishes
 * nothing and holds nothing it did not hold before the call (the change stays `READY` with its own slot). Publishing it
 * again publishes it. One host runs the control plane and its service port over the starter's CoCache on real Redis; its
 * store is wrapped so that a publish waits, before its head write, on the write of its record until it is cancelled.
 */
class ControlRequestCancellationTest {
    /** The host's store, holding the record write of [hold]'s document once until the request that makes it is cancelled. */
    class HoldingStore(private val delegate: ControlStore) : ControlStore by delegate {
        @Volatile
        var hold: SnapshotKey? = null

        @Volatile
        var reached = CompletableDeferred<Unit>()

        @Volatile
        var cancelled = CompletableDeferred<Unit>()

        override suspend fun putRecord(key: SnapshotKey, document: SnapshotDocument): SnapshotDigest {
            if (key == hold) {
                hold = null
                reached.complete(Unit)
                try {
                    awaitCancellation()
                } catch (e: CancellationException) {
                    cancelled.complete(Unit)
                    throw e
                }
            }
            return delegate.putRecord(key, document)
        }

        /** Holds the next record write of [key]'s document. */
        fun holdNext(key: SnapshotKey) {
            reached = CompletableDeferred()
            cancelled = CompletableDeferred()
            hold = key
        }
    }

    private val runner = ReactiveWebApplicationContextRunner()
        .withClassLoader(FilteredClassLoader(GATEWAY_GLOBAL_FILTER))
        .withConfiguration(
            AutoConfigurations.of(
                JacksonAutoConfiguration::class.java,
                DataRedisAutoConfiguration::class.java,
                DataRedisReactiveAutoConfiguration::class.java,
                CoCacheAutoConfiguration::class.java,
                HttpHandlerAutoConfiguration::class.java,
                WebFluxAutoConfiguration::class.java,
                CoSecTokenRevocationCacheAutoConfiguration::class.java,
                CoSecJwtAutoConfiguration::class.java,
                CoSecPipelineIntegrationAutoConfiguration::class.java,
                CoSecSnapshotAutoConfiguration::class.java,
                CoSecPipelineAutoConfiguration::class.java,
                CoSecPipelineWebAutoConfiguration::class.java,
                CoSecGatewayModeAutoConfiguration::class.java,
                CoSecControlAutoConfiguration::class.java,
                CoSecControlRoutesAutoConfiguration::class.java,
                CoSecControlServicePortAutoConfiguration::class.java,
            ),
        )
        .withBean("holdingControlStore", BeanPostProcessor::class.java, {
            object : BeanPostProcessor {
                override fun postProcessAfterInitialization(bean: Any, beanName: String): Any =
                    if (bean is ControlStore && bean !is HoldingStore) HoldingStore(bean) else bean
            }
        })
        .withPropertyValues(
            "cosec.key-prefix=cancel-${UUID.randomUUID()}",
            "cosec.ip2region.enabled=false",
            "cosec.authorization.snapshot.eviction-check=warn",
            "cosec.authorization.routes.location=",
            "cosec.pipeline.caller=forwarded-identity",
            "cosec.pipeline.identity.only-trusted-callers-can-reach=true",
            "cosec.jwt.enabled=false",
            "${ControlServicePort.KEY}=0",
        )

    private val iam = ControlCaller.service("svc-iam")
    private val acme = SnapshotKey.Tenant(TENANT)

    @Test
    fun `a caller that is cancelled cancels the host's request, which publishes nothing and holds nothing - CTL-82`() {
        host { store, http, _ ->
            val id = ready(http)
            store.holdNext(acme)
            val call = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) { http.publish(iam, id) }
            withTimeout(REACHED_WITHIN) { store.reached.await() }

            call.cancelAndJoin()

            // Within a third of the call's own timeout: only the cancellation can have ended the host's request so soon.
            withTimeout(CANCELLED_WITHIN) { store.cancelled.await() }
            unpublished(store, id)
            published(http, id)
        }
    }

    @Test
    fun `a call that times out cancels the host's request, which publishes nothing and holds nothing - CTL-82`() {
        host { store, http, base ->
            val id = ready(http)
            val quick = HttpControlService(base, timeout = CALL_TIMEOUT.toJavaDuration())
            store.holdNext(acme)

            assertThrownBy<HttpTimeoutException> { runBlocking { quick.publish(iam, id) } }

            withTimeout(REACHED_WITHIN) { store.reached.await() }
            withTimeout(CANCELLED_WITHIN) { store.cancelled.await() }
            unpublished(store, id)
            published(http, id)
        }
    }

    /** Nothing published, and the change as it stood before the call: `READY`, holding its document's slot. */
    private suspend fun unpublished(store: HoldingStore, id: String) {
        store.head(acme)!!.document.revision.assert().isEqualTo(1L)
        val change = store.change(ChangeId(id))!!.value
        change.state.assert().isEqualTo(ChangeState.READY)
        change.publishedRevision.assert().isNull()
        store.stage(acme)!!.value.holder.assert().isEqualTo(ChangeId(id))
    }

    /** Publishing it again publishes it, as the next revision. */
    private suspend fun published(http: HttpControlService, id: String) {
        val answer = CoSecJson.mapper.readTree(http.publish(iam, id))
        answer.get("state").asString().assert().isEqualTo("PUBLISHED")
        answer.get("publishedRevision").asLong().assert().isEqualTo(2L)
    }

    /** A change of acme's document by the service, `READY` to publish. */
    private suspend fun ready(http: HttpControlService): String {
        val proposal = """{"document":"tenant:$TENANT","base":1,"note":"n","operations":[{"op":"putPolicy",""" +
            """"policy":{"id":"added","statements":[{"effect":"allow","action":"order:read"}]}}]}"""
        val id = CoSecJson.mapper.readTree(http.propose(iam, proposal)).get("id").asString()
        http.skipShadow(iam, id, "cancellation")
        return id
    }

    /** A started host, its platform and acme's documents published and installed, and its store and HTTP facade. */
    private fun host(test: suspend CoroutineScope.(HoldingStore, HttpControlService, URI) -> Unit) {
        runner.run { context ->
            context.startupFailure.assert().isNull()
            publish(context, SnapshotKey.Platform, DocumentKind.PLATFORM, "", PLATFORM)
            publish(context, acme, DocumentKind.TENANT, TENANT, "")
            val store = context.getBean(ControlStore::class.java) as HoldingStore
            runBlocking { store.keepOpenChanges() }
            val port = context.getBean(ControlServiceListener::class.java).boundPort
            val base = URI("http://127.0.0.1:$port/cosec/control/v1")
            val http = HttpControlService(base)
            runBlocking {
                // The host decides with the documents it has installed: wait until the platform document is one of them.
                withTimeout(INSTALLED_WITHIN) {
                    while (true) {
                        try {
                            http.document(iam, acme)
                            break
                        } catch (_: ControlRefusedException) {
                            delay(POLL)
                        }
                    }
                }
                test(store, http, base)
            }
        }
    }

    private fun publish(context: ApplicationContext, key: SnapshotKey, kind: DocumentKind, owner: String, body: String) {
        val revisions = context.getBean(Revisions::class.java)
        runBlocking {
            val text = """{"schema":2,"revision":1,"publishedAt":"2026-11-01T00:00:00Z"$body}"""
            revisions.publish(
                key,
                revisions.head(key),
                PolicyDocumentCodec.decode(kind, owner, text.encodeToByteArray()),
                Publication("bootstrap", ProvenanceKind.BOOTSTRAP, Actor(TenantId.PLATFORM, PrincipalId("plato"))),
            )
        }
    }

    private companion object {
        const val TENANT = "acme"

        /** How long a call on loopback may take to reach the store's record write, a cold host's first publish included. */
        val REACHED_WITHIN = 10.seconds

        /** How long the host may take to cancel a request whose caller went away: a third of the call's default timeout. */
        val CANCELLED_WITHIN = HttpControlService.DEFAULT_TIMEOUT.toKotlinDuration() / 3

        /** The timeout of the call that times out: shorter than anything the host does before its record write. */
        val CALL_TIMEOUT = 2.seconds

        /** How long the host may take to install the documents published before it is asked. */
        val INSTALLED_WITHIN = 10.seconds

        /** How often the host is asked whether it installed them. */
        val POLL = 20.milliseconds

        /** Anyone authenticated reaches the control API; svc-iam has the service's rights over tenants' documents. */
        const val PLATFORM = ""","policies":[{"id":"control-api","statements":[{"effect":"allow",""" +
            """"action":["http:GET","http:POST"],"resource":"http:path:/cosec/control/v1/**",""" +
            """"condition":{"eq":["subject.authenticated",true]}}]},""" +
            """{"id":"control-service","statements":[{"effect":"allow","action":["cosec:read","cosec:shadow-read",""" +
            """"cosec:write","cosec:publish","cosec:skip-shadow"],"resource":["doc/tenant/**","doc/app/**"]}]}],""" +
            """"bindings":[{"id":"control-api","subject":"*","app":"*","space":"*","policy":"control-api"},""" +
            """{"id":"svc-iam","subject":{"principal":"svc-iam","tenant":"(platform)"},"app":"cosec-control",""" +
            """"space":"","policy":"control-service"}]"""
    }
}
