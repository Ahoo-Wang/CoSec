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

import kotlinx.coroutines.runBlocking
import me.ahoo.cocache.spring.boot.CoCacheAutoConfiguration
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.api.context.SecurityContext
import me.ahoo.cosec.api.context.SecurityScope
import me.ahoo.cosec.api.principal.CoSecPrincipal
import me.ahoo.cosec.api.token.CompositeToken
import me.ahoo.cosec.control.http.HttpControlService
import me.ahoo.cosec.control.local.LocalControlService
import me.ahoo.cosec.control.sdk.ChangeState
import me.ahoo.cosec.control.sdk.ControlCaller
import me.ahoo.cosec.control.sdk.ControlRefusedException
import me.ahoo.cosec.control.sdk.ControlService
import me.ahoo.cosec.control.store.ControlStore
import me.ahoo.cosec.control.store.Publication
import me.ahoo.cosec.control.store.Revisions
import me.ahoo.cosec.document.Actor
import me.ahoo.cosec.document.PolicyDocumentCodec
import me.ahoo.cosec.document.ProvenanceKind
import me.ahoo.cosec.document.identity.IdentityHeader
import me.ahoo.cosec.document.json.CoSecJson
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.model.decision.DocumentKind
import me.ahoo.cosec.spi.identity.Identity
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.cosec.spi.token.RevocationReader
import me.ahoo.cosec.spi.token.TokenVerifier
import me.ahoo.cosec.spi.token.VerifiedToken
import me.ahoo.cosec.spring.boot.jwt.CoSecJwtAutoConfiguration
import me.ahoo.cosec.spring.boot.jwt.CoSecTokenRevocationCacheAutoConfiguration
import me.ahoo.cosec.spring.boot.pipeline.CoSecGatewayModeAutoConfiguration
import me.ahoo.cosec.spring.boot.pipeline.CoSecPipelineAutoConfiguration
import me.ahoo.cosec.spring.boot.pipeline.CoSecPipelineIntegrationAutoConfiguration
import me.ahoo.cosec.spring.boot.pipeline.CoSecPipelineWebAutoConfiguration
import me.ahoo.cosec.spring.boot.pipeline.CoSecSnapshotAutoConfiguration
import me.ahoo.cosec.spring.boot.pipeline.GATEWAY_GLOBAL_FILTER
import me.ahoo.cosec.testkit.PermittedRequest
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration
import org.springframework.boot.data.redis.autoconfigure.DataRedisReactiveAutoConfiguration
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration
import org.springframework.boot.test.context.FilteredClassLoader
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner
import org.springframework.boot.webflux.autoconfigure.HttpHandlerAutoConfiguration
import org.springframework.boot.webflux.autoconfigure.WebFluxAutoConfiguration
import org.springframework.context.ApplicationContext
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ObjectNode
import java.net.URI
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * `CTL-77`: the two implementations of `ControlService` answer alike. One host runs the control plane and its service port
 * (`cosec.pipeline.caller=forwarded-identity`, as the control-plane image does); the same script of calls runs through the
 * in-process implementation on one tenant and through [HttpControlService], over the service port, on a twin tenant
 * published alike. Every answer and every refusal is compared, less what names the call itself (a change's id and times,
 * its digests, how its author was identified, the request id): the same state, operations, findings, conflicts and statuses.
 * Both kinds of caller are run: the application's own principal (`ControlCaller.service`) and the principal of a request
 * the application serves (`ControlCaller.of`), a context CoSec made. The store is the starter's CoCache over real Redis,
 * one that keeps its open-change sets (`CTL-54`), so the listings are compared for what they answer, a cursor followed.
 */
class ControlServiceParityTest {
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
        .withPropertyValues(
            "cosec.key-prefix=parity-${UUID.randomUUID()}",
            "cosec.ip2region.enabled=false",
            "cosec.authorization.snapshot.eviction-check=warn",
            "cosec.authorization.routes.location=",
            "cosec.pipeline.caller=forwarded-identity",
            "cosec.pipeline.identity.only-trusted-callers-can-reach=true",
            "cosec.jwt.enabled=false",
            "${ControlServicePort.KEY}=0",
        )

    /** One call's answer, or its refusal, as text: what the two implementations are compared on. */
    private suspend fun answer(call: suspend () -> String): String = try {
        "200 " + normalized(CoSecJson.mapper.readTree(call()))
    } catch (refused: ControlRefusedException) {
        val problem = CoSecJson.mapper.readTree(refused.problem)
        "${refused.status} ${problem.get("code")?.asString()} conflict=${problem.get("conflict")?.asString()} " +
            "heldBy=${problem.has("heldBy")}"
    } catch (refused: IllegalArgumentException) {
        "refused before the call: ${refused.message}"
    }

    /** [node] without what names the call itself rather than what it did; a cursor is compared for being there. */
    private fun normalized(node: JsonNode): String {
        fun strip(node: JsonNode) {
            if (node is ObjectNode) {
                VOLATILE.forEach(node::remove)
                node.get("next")?.takeIf { it.isString }?.let { node.put("next", "<cursor>") }
                node.values().forEach(::strip)
            } else if (node.isArray) {
                node.values().forEach(::strip)
            }
        }
        val copy = node.deepCopy()
        strip(copy)
        // A listing is in the order of its changes' ids, which are random: compared as the set it lists.
        (copy as? ObjectNode)?.get("changes")?.takeIf { it.isArray }?.let { changes ->
            val sorted = changes.values().sortedBy { CoSecJson.mapper.writeValueAsString(it) }
            copy.putArray("changes").addAll(sorted)
        }
        return CoSecJson.mapper.writeValueAsString(copy)
    }

    /**
     * The script, run as [iam] and as [person] (a principal of [tenant] with its administrators' role) on [tenant]'s
     * document: every answer, in order, with the tenant's name taken out.
     */
    private fun script(
        service: ControlService,
        tenant: String,
        iam: ControlCaller,
        person: ControlCaller,
    ): List<String> = runBlocking {
        val key = SnapshotKey.Tenant(tenant)
        fun proposal(id: String, base: Int) = """{"document":"tenant:$tenant","base":$base,"note":"$id",""" +
            """"operations":[{"op":"putPolicy","policy":{"id":"$id","statements":[{"effect":"allow","action":"order:read"}]}}]}"""
        val answers = mutableListOf<String>()
        suspend fun record(call: suspend () -> String) = answers.add(answer(call))
        suspend fun id(call: suspend () -> String): String = call().let {
            CoSecJson.mapper.readTree(
                it
            ).get("id").asString()
        }

        record { service.document(iam, key) }
        record { service.documents(iam, DocumentKind.TENANT) }
        record { service.revisions(iam, key) }
        record { service.revision(iam, key, 1) }
        record { service.diff(iam, key, 1, 1) }
        // A check, then read and write as the service: propose, a stale base, skip the window, a held slot, publish.
        record { service.check(iam, proposal("first", 1)) }
        record { service.check(iam, proposal("stale", 0)) }
        record { service.checkApply(iam, key, APPLY.replace("\"revision\":2", "\"revision\":1")) }
        val first = id { service.propose(iam, proposal("first", 1)) }
        record { service.change(iam, first) }
        record { service.propose(iam, proposal("stale", 0)) }
        val second = id { service.propose(iam, proposal("second", 1)) }
        record { service.skipShadow(iam, first, "parity") }
        record { service.skipShadow(iam, second, "parity") }
        record { service.publish(iam, first) }
        record { service.changes(iam, key) }
        // A page of one, and the page its cursor leads to, over three open changes.
        record { service.propose(iam, proposal("page-a", 2)) }
        record { service.propose(iam, proposal("page-b", 2)) }
        // Which change a page of one shows follows the random ids: the pages are compared for what they list together.
        val pageOne = service.changes(iam, key, limit = 2)
        val next = checkNotNull(
            CoSecJson.mapper.readTree(pageOne).get("next")?.asString()
        ) { "a page of two has a next" }
        val pageTwo = service.changes(iam, key, after = next, limit = 2)
        record {
            val pages = listOf(pageOne, pageTwo).map { CoSecJson.mapper.readTree(it) }
            val merged = CoSecJson.mapper.createObjectNode()
            merged.putArray("changes").addAll(pages.flatMap { it.get("changes").values() })
            merged.putArray("sizes").apply { pages.forEach { add(it.get("changes").size()) } }
            merged.putArray("next").apply { pages.forEach { add(it.get("next")?.isString == true) } }
            CoSecJson.mapper.writeValueAsString(merged)
        }
        // By state, the listing spans every tenant, the other run's included: compared for this tenant's changes.
        record {
            val listed = CoSecJson.mapper.readTree(service.changes(iam, state = ChangeState.CHECKED))
            val own = listed.get("changes").values().filter { it.get("key").asString() == "tenant:$tenant" }
            CoSecJson.mapper.writeValueAsString(
                CoSecJson.mapper.createObjectNode().apply { putArray("changes").addAll(own) }
            )
        }
        record { service.rebase(iam, second) }
        record { service.abandon(iam, second) }
        record { service.startShadow(iam, second) }
        record { service.apply(iam, key, APPLY) }
        answers += appScript(service, tenant, iam)
        // Outside the narrow set, or outside what the platform document grants: refused alike.
        record { service.document(iam, SnapshotKey.Platform) }
        record { service.document(ControlCaller.service("svc-nobody"), key) }
        // The person whose request the application serves: read, and a proposal of their own.
        record { service.document(person, key) }
        record { service.check(person, proposal("person", 2)) }
        record { service.propose(person, proposal("person", 2)) }
        record { service.document(ControlCaller.of(ASSEMBLED), key) }
        answers.map { it.replace(LOCAL, "<tenant>").replace(REMOTE, "<tenant>") }
    }

    /**
     * An app document's change as [iam] (`CTL-58`): checked and proposed alike, a catalog waiting for a person, a role
     * removed publishing alone, and the platform document outside.
     */
    private suspend fun appScript(service: ControlService, tenant: String, iam: ControlCaller): List<String> {
        val answers = mutableListOf<String>()
        suspend fun record(call: suspend () -> String) = answers.add(answer(call))
        suspend fun id(call: suspend () -> String): String = CoSecJson.mapper.readTree(call()).get("id").asString()
        fun proposal(
            operation: String
        ) = """{"document":"app:$tenant","base":1,"note":"app","operations":[$operation]}"""
        record { service.check(iam, proposal(CATALOG)) }
        val catalog = id { service.propose(iam, proposal(CATALOG)) }
        record { service.skipShadow(iam, catalog, "parity") }
        record { service.changes(iam, SnapshotKey.App(tenant)) }
        record { service.abandon(iam, catalog) }
        val removal = id { service.propose(iam, proposal("""{"op":"removeRole","id":"$tenant-viewer"}""")) }
        record { service.skipShadow(iam, removal, "parity") }
        record { service.publish(iam, removal) }
        record { service.propose(iam, """{"document":"platform","base":1,"note":"p","operations":[]}""") }
        return answers
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

    /** A request alice of [tenant] makes as an administrator, behind a gateway that forwarded her: a context CoSec made. */
    private fun alice(tenant: String): ControlCaller {
        val now = Instant.now()
        val forwarded = Identity(
            tenant = tenant,
            subject = "alice",
            app = "cosec-control",
            space = "",
            roles = setOf("admin"),
            credential = Identity.Credential(Identity.Method.JWT, "token-$tenant", now.plusSeconds(300)),
            issuedAt = now,
            requestId = "r-$tenant",
        )
        return ControlCaller.of(PermittedRequest.withIdentity(IdentityHeader.write(forwarded)))
    }

    @Test
    fun `the in-process and the HTTP implementation answer every call alike - CTL-77`() {
        runner.run { context ->
            context.startupFailure.assert().isNull()
            publish(context, SnapshotKey.Platform, DocumentKind.PLATFORM, "", PLATFORM)
            listOf(LOCAL, REMOTE).forEach {
                publish(context, SnapshotKey.Tenant(it), DocumentKind.TENANT, it, TENANT)
                publish(context, SnapshotKey.App(it), DocumentKind.APP, it, app(it))
            }
            // What the control plane's first maintenance pass does on a new store, done now rather than waited for: the
            // open-change sets are kept from the start (CTL-54), so a listing answers instead of 503.
            val store = context.getBean(ControlStore::class.java)
            runBlocking { store.keepOpenChanges() }
            runBlocking { store.openChangesKept() }.assert().isTrue()
            val local = context.getBean(ControlService::class.java)
            local.assert().isInstanceOf(LocalControlService::class.java)
            val port = context.getBean(ControlServiceListener::class.java).boundPort
            val http = HttpControlService(URI("http://127.0.0.1:$port/cosec/control/v1"))
            val iam = ControlCaller.service("svc-iam")
            // The host decides with the documents it has installed: wait until the platform document is one of them.
            val deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos()
            var last = ""
            while (runBlocking { answer { http.document(iam, SnapshotKey.Tenant(REMOTE)) } }.also { last = it }
                    .startsWith("200").not()
            ) {
                check(System.nanoTime() < deadline) { "The platform document was not installed: $last" }
                Thread.sleep(20)
            }
            val inProcess = script(local, LOCAL, iam, alice(LOCAL))
            val overHttp = script(http, REMOTE, iam, alice(REMOTE))
            overHttp.indices.forEach { step ->
                overHttp[step].assert().describedAs("call ${step + 1}").isEqualTo(inProcess.getOrNull(step))
            }
            overHttp.size.assert().isEqualTo(inProcess.size)
            // The script is no empty agreement: it publishes, conflicts and refuses.
            inProcess.joinToString("\n").assert()
                .contains("\"state\":\"PUBLISHED\"")
                .contains("\"state\":\"AWAITING_APPROVAL\"")
                .contains("409 CONFLICT conflict=BASE_MOVED heldBy=false")
                .contains("409 CONFLICT conflict=SLOT_HELD heldBy=true")
                .contains("refused before the call")
            inProcess.count { it.startsWith("40") }.assert().isGreaterThanOrEqualTo(4)
            // The listings answer, with a cursor to follow: nothing compared as two unavailable stores.
            inProcess.filter { it.startsWith("503") }.assert().isEmpty()
            inProcess.joinToString("\n").assert().contains("\"sizes\":[2,1]").contains("\"next\":[true,false]")
                .contains("\"changes\":[{")
        }
    }

    /**
     * A request [subject] of [tenant] makes as an administrator, its token holding [attributes] of any JSON type: a context
     * CoSec's own token authenticator made, with a credential, as an application's signed-in user has.
     */
    private fun withAttributes(tenant: String, subject: String, attributes: Map<String, Any>): ControlCaller {
        val now = Instant.now()
        val token = VerifiedToken(
            "t-$subject-$tenant",
            subject,
            tenant,
            "cosec-control",
            setOf("admin"),
            attributes = attributes,
            issuedAt = now,
            expiresAt = now.plusSeconds(300),
        )
        val verifier = object : TokenVerifier {
            override fun verify(accessToken: String): VerifiedToken = token
            override fun refresh(token: CompositeToken): VerifiedToken = error("not refreshed")
        }
        return ControlCaller.of(PermittedRequest.withToken("token", verifier, RevocationReader.NONE))
    }

    @Test
    fun `a DENY on the caller's attributes decides alike through both implementations - CTL-77`() {
        // DR1-F-1: over HTTP the caller's attributes reach the host, each as the text the engine reads in process, so a
        // DENY that reads one refuses through either implementation. Before, none was forwarded: absent, the DENY lapsed.
        runner.run { context ->
            context.startupFailure.assert().isNull()
            publish(context, SnapshotKey.Platform, DocumentKind.PLATFORM, "", ATTRIBUTE_DENIES)
            listOf(LOCAL, REMOTE).forEach { publish(context, SnapshotKey.Tenant(it), DocumentKind.TENANT, it, TENANT) }
            val local = context.getBean(ControlService::class.java)
            val port = context.getBean(ControlServiceListener::class.java).boundPort
            val http = HttpControlService(URI("http://127.0.0.1:$port/cosec/control/v1"))
            val deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos()
            val ready = { runBlocking { answer { http.document(alice(REMOTE), SnapshotKey.Tenant(REMOTE)) } } }
            while (!ready().startsWith("200")) {
                check(System.nanoTime() < deadline) { "The platform document was not installed" }
                Thread.sleep(20)
            }
            fun script(service: ControlService, tenant: String): List<String> = runBlocking {
                val proposal = """{"document":"tenant:$tenant","base":1,"note":"n","operations":[{"op":"putPolicy",""" +
                    """"policy":{"id":"p","statements":[{"effect":"allow","action":"order:read"}]}}]}"""
                listOf(
                    // A boolean, read as "true"; a list, read as its text; and a caller with neither.
                    answer { service.check(withAttributes(tenant, "sam", mapOf("suspended" to true)), proposal) },
                    answer {
                        service.check(
                            withAttributes(tenant, "gus", mapOf("groups" to listOf("ops", "night"))),
                            proposal
                        )
                    },
                    answer { service.check(withAttributes(tenant, "ann", mapOf("groups" to listOf("ops"))), proposal) },
                ).map { it.replace(LOCAL, "<tenant>").replace(REMOTE, "<tenant>") }
            }
            val inProcess = script(local, LOCAL)
            script(http, REMOTE).assert().isEqualTo(inProcess)
            inProcess[0].assert().startsWith("403")
            inProcess[1].assert().startsWith("403")
            inProcess[2].assert().startsWith("200")
        }
    }

    private companion object {
        const val LOCAL = "acme"
        const val REMOTE = "globex"

        /** What names the call rather than what it did: its ids and times, digests, its author's origin, its request. */
        val VOLATILE = listOf(
            "id", "createdAt", "updatedAt", "publishedAt", "asOf", "at", "origin", "digest", "candidate", "requestId",
            "change", "publishedBy", "provenance",
        )

        /** Anyone authenticated reaches the control API; svc-iam has the service's rights over tenants' and apps' documents. */
        const val PLATFORM = ""","policies":[{"id":"control-api","statements":[{"effect":"allow",""" +
            """"action":["http:GET","http:POST"],"resource":"http:path:/cosec/control/v1/**",""" +
            """"condition":{"eq":["subject.authenticated",true]}}]},""" +
            """{"id":"control-service","statements":[{"effect":"allow","action":["cosec:read","cosec:shadow-read",""" +
            """"cosec:write","cosec:publish","cosec:skip-shadow"],"resource":["doc/tenant/**","doc/app/**"]}]}],""" +
            """"bindings":[{"id":"control-api","subject":"*","app":"*","space":"*","policy":"control-api"},""" +
            """{"id":"svc-iam","subject":{"principal":"svc-iam","tenant":"(platform)"},"app":"cosec-control",""" +
            """"space":"","policy":"control-service"}]"""

        /**
         * Anyone reaches the control API, and two DENYs of a write on the caller's attributes: a suspended caller, and a
         * caller whose groups, a list, read as `[ops, night]`.
         */
        const val ATTRIBUTE_DENIES = ""","policies":[{"id":"control-api","statements":[{"effect":"allow",""" +
            """"action":["http:GET","http:POST"],"resource":"http:path:/cosec/control/v1/**",""" +
            """"condition":{"eq":["subject.authenticated",true]}}]},""" +
            """{"id":"no-suspended","statements":[{"effect":"deny","action":"cosec:write",""" +
            """"condition":{"eq":["subject.attributes.suspended","true"]}}]},""" +
            """{"id":"no-night","statements":[{"effect":"deny","action":"cosec:write",""" +
            """"condition":{"eq":["subject.attributes.groups","[ops, night]"]}}]}],""" +
            """"bindings":[{"id":"control-api","subject":"*","app":"*","space":"*","policy":"control-api"},""" +
            """{"id":"no-suspended","subject":"*","app":"cosec-control","space":"","policy":"no-suspended"},""" +
            """{"id":"no-night","subject":"*","app":"cosec-control","space":"","policy":"no-night"}]"""

        /** The tenant's administrators, by role, and one policy. */
        const val TENANT = ""","policies":[{"id":"orders","statements":[{"effect":"allow","action":"order:*"}]}],""" +
            """"bindings":[{"id":"admins","subject":{"role":"admin"},"app":"cosec-control",""" +
            """"space":"","policy":"cosec:tenant-admin"}]"""

        /**
         * [owner]'s app document: one permission, and a role that lists it, named after the app so that the two runs' apps
         * share no system role id, which a check warns about (and the other run removes meanwhile).
         */
        fun app(owner: String) = ""","catalog":{"groups":[{"name":"orders","permissions":[""" +
            """{"id":"read","action":"order:read","resource":"order/**"}]}]},""" +
            """"roles":[{"id":"$owner-viewer","permissions":["read"]}]"""

        /** The app's catalog widened by a permission: analyzed as anyone's, it waits for a person. */
        const val CATALOG = """{"op":"putCatalog","catalog":{"groups":[{"name":"orders","permissions":[""" +
            """{"id":"read","action":"order:read","resource":"order/**"},""" +
            """{"id":"cancel","action":"order:cancel","resource":"order/**"}]}]}}"""

        const val APPLY = """{"mode":"MERGE","note":"apply","document":{"schema":2,"revision":2,""" +
            """"publishedAt":"2026-11-01T00:00:00Z","policies":[{"id":"applied","statements":""" +
            """[{"effect":"allow","action":"order:list"}]}]}}"""

        /** A context the application assembled: refused by either implementation before anything is asked. */
        val ASSEMBLED = object : SecurityContext {
            override val tenantId = "acme"
            override val principal: CoSecPrincipal = object : CoSecPrincipal {
                override val id = "alice"
                override val authenticated = true
                override val attributes: Map<String, Any> = emptyMap()
            }
            override val scope: SecurityScope get() = error("not read")
            override val forwardedIdentity = null
        }
    }
}
