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

package me.ahoo.cosec.spring.boot.realserver

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.engine.flat.CompiledDocument
import me.ahoo.cosec.engine.flat.SnapshotView
import me.ahoo.cosec.model.Memberships
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.RoleId
import me.ahoo.cosec.model.SpaceId
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.runtime.Pipeline
import me.ahoo.cosec.runtime.PipelineComponents
import me.ahoo.cosec.runtime.RequestGate
import me.ahoo.cosec.runtime.document.PolicyDocumentCompiler
import me.ahoo.cosec.runtime.event.DecisionListeners
import me.ahoo.cosec.runtime.snapshot.SnapshotViews
import me.ahoo.cosec.runtime.snapshot.SuspendingSnapshotSource
import me.ahoo.cosec.runtime.snapshot.ViewKey
import me.ahoo.cosec.spi.authn.AuthenticatedPrincipal
import me.ahoo.cosec.spi.authn.Authenticator
import me.ahoo.cosec.spi.authn.AuthnResult
import me.ahoo.cosec.spi.id.IdGenerator
import me.ahoo.cosec.spi.ratelimit.RateLimitResult
import me.ahoo.cosec.spi.ratelimit.RateLimiter
import me.ahoo.cosec.spi.snapshot.SnapshotDocument
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CompletableFuture

/**
 * The pipeline the real-server suites run, with documents written for them: public pages; orders, which `clerk` may do
 * anything to and `staff` anything anywhere, but neither may DELETE; an exhausted rate limit; a path open only to the
 * internal network (`10.0.0.0/8`). `Authorization: Bearer <who>@<tenant>:<role>,...` authenticates; every id is `parity-request`, so the stacks' answers compare byte
 * for byte.
 */
internal class ParityFixture(
    private val ids: IdGenerator = IdGenerator { REQUEST_ID },
    private val listeners: DecisionListeners = DecisionListeners(emptyList()),
) : AutoCloseable {
    private val platform: CompiledDocument = compile(
        SnapshotKey.Platform,
        ""","policies":[
            {"id":"public","statements":[{"effect":"allow","action":"http:*","resource":"http:path:/public/**"}]},
            {"id":"no-delete","statements":[{"effect":"deny","action":"http:DELETE","resource":"http:path:/orders/**"}]},
            {"id":"intranet","statements":[{"effect":"allow","action":"http:GET","resource":"http:path:/internal/**",
              "condition":{"cidr":["environment.ip",["10.0.0.0/8"]]}}]},
            {"id":"blocked","statements":[{"effect":"deny","action":"*","resource":"http:path:/guarded/**",
              "condition":{"cidr":["environment.ip",["192.0.2.0/24"]]}}]},
            {"id":"guarded","statements":[{"effect":"allow","action":"http:GET","resource":"http:path:/guarded/**"}]}],
          "bindings":[{"id":"g:public","subject":"*","app":"*","space":"*","policy":"public"},
                      {"id":"g:no-delete","subject":"*","app":"*","space":"*","policy":"no-delete"},
                      {"id":"g:intranet","subject":"*","app":"*","space":"*","policy":"intranet"},
                      {"id":"g:blocked","subject":"*","app":"*","space":"*","policy":"blocked"},
                      {"id":"g:guarded","subject":"*","app":"*","space":"*","policy":"guarded"}],
          "rateLimits":[{"id":"limited","action":"*","resource":"http:path:/public/limited/**","permitsPerSecond":10}]""",
    )

    private val tenant: CompiledDocument = compile(
        SnapshotKey.Tenant("acme"),
        ""","policies":[{"id":"orders","statements":[{"effect":"allow","action":"http:*","resource":"http:path:/orders/**"}]},
            {"id":"everything","statements":[{"effect":"allow","action":"http:*","resource":"http:path:/**"}]}],
          "bindings":[{"id":"r:clerk","subject":{"role":"clerk"},"app":"*","space":"*","policy":"orders"},
                      {"id":"r:staff","subject":{"role":"staff"},"app":"*","space":"*","policy":"everything"}]""",
    )

    private val views = object : SnapshotViews {
        override fun peek(key: ViewKey): SnapshotView = SnapshotView(
            platform,
            key.app?.let { CompiledDocument.app(it, emptyList(), emptyList()) },
            key.tenant?.let {
                if (it.value == "acme") {
                    tenant
                } else {
                    CompiledDocument.tenant(
                        it,
                        emptyList(),
                        emptyList()
                    )
                }
            },
        )

        override fun load(key: ViewKey): CompletableFuture<SnapshotView> = CompletableFuture.completedFuture(peek(key))
    }

    private val authenticator = Authenticator { credentials ->
        val token = credentials.token?.removePrefix("Bearer ") ?: return@Authenticator AuthnResult.Anonymous
        val (who, rest) = token.split('@', limit = 2)
        val (tenant, roles) = rest.split(':', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
        val memberships = Memberships(
            Memberships.Source.CLAIMS,
            mapOf(SpaceId.DEFAULT to roles.split(',').filter { it.isNotEmpty() }.mapTo(LinkedHashSet(), ::RoleId)),
            emptyList(),
        )
        AuthnResult.Authenticated(AuthenticatedPrincipal(PrincipalId(who), TenantId(tenant), memberships), "token")
    }

    /** Every rule but `limited` has permits left. */
    private val rateLimiter = RateLimiter { ruleId, _, _ ->
        val allowed = ruleId != "platform/limited"
        RateLimitResult(allowed, 10, if (allowed) 9 else 0, Duration.ofSeconds(3))
    }

    val pipeline = Pipeline(
        PipelineComponents(
            authenticator,
            SuspendingSnapshotSource(views),
            idGenerator = ids,
            rateLimiter = rateLimiter,
            listeners = listeners,
        )
    )

    /** What [stack]'s adapter calls: the pipeline, on every stack. */
    fun gate(@Suppress("UNUSED_PARAMETER") stack: Stack): RequestGate = pipeline

    override fun close() = listeners.cancel()

    private fun compile(key: SnapshotKey, body: String): CompiledDocument = PolicyDocumentCompiler().compile(
        key,
        SnapshotDocument(
            2,
            1,
            Instant.parse("2026-11-13T00:00:00Z"),
            """{"schema":2,"revision":1,"publishedAt":"2026-11-13T00:00:00Z"$body}""".encodeToByteArray(),
        ),
    )

    companion object {
        const val REQUEST_ID = "parity-request"

        /** The headers CoSec writes, and the status and body: what must be equal on every stack. */
        private val COMPARED = listOf(
            "content-type",
            "cosec-request-id",
            "ratelimit-limit",
            "ratelimit-remaining",
            "ratelimit-reset",
            "retry-after",
        )

        /** A permit's `Content-Type` and body are the handler's, which writes what its server received: not compared. */
        fun comparable(response: RawResponse): Map<String, Any?> {
            val refused = !response.body.startsWith(SERVED)
            // A refusal is CoSec's to frame: its length on every stack, never chunked.
            val framing = if (refused) listOf("content-length", "transfer-encoding") else emptyList()
            val headers = (COMPARED + framing).filter { refused || it != "content-type" }.associateWith { response.headers[it] }
            return headers + mapOf("status" to response.status, "body" to response.body.takeIf { refused })
        }
    }
}
