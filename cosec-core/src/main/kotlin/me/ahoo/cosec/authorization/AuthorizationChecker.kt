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

package me.ahoo.cosec.authorization

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.cosec.api.authorization.Authorization
import me.ahoo.cosec.api.context.SecurityContext
import me.ahoo.cosec.api.context.request.AppIdCapable
import me.ahoo.cosec.api.context.request.Request
import me.ahoo.cosec.api.context.request.SpaceIdCapable
import me.ahoo.cosec.context.RequestSecurityContexts.getRequest
import me.ahoo.cosec.context.SimpleSecurityContext
import me.ahoo.cosec.policy.EvaluateRequest
import me.ahoo.cosec.policy.condition.part.ResourceParts
import me.ahoo.cosec.policy.markPolicyEvaluation
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

/**
 * One action to check: a path and method, as an HTTP request to it would carry them.
 *
 * @param appId the app whose role permissions apply; null takes the app of the current request
 * @param spaceId the space whose role permissions apply; null takes the space of the current request
 * @param resource attributes of the object the action targets (owner, tenant, status, ...), loaded by the caller;
 * conditions read them as the parts `resource.<name>`
 */
data class CheckRequest(
    val path: String,
    val method: String = "GET",
    val appId: String? = null,
    val spaceId: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val attributes: Map<String, String> = emptyMap(),
    val resource: Map<String, String> = emptyMap()
)

/**
 * Answers "may the current principal do this?" in code: in services, message consumers, or to decide which
 * actions a UI shows.
 *
 * A check is a query, not a request. It uses [authorization] directly (the starter passes a plain
 * [SimpleAuthorization]), so it writes no audit events and spends no rate limit, and every check runs on its own
 * copy of the security context, marked as a policy evaluation, so path variables never leak into the caller's
 * context. Errors deny.
 */
class AuthorizationChecker(private val authorization: Authorization) {
    companion object {
        private val log = KotlinLogging.logger {}
    }

    fun check(context: SecurityContext, request: CheckRequest): Mono<Boolean> =
        Mono.defer { authorization.authorize(request.toRequest(context), isolated(context)) }
            .map { it.authorized }
            .defaultIfEmpty(false)
            .onErrorResume { cause ->
                log.warn(cause) { "Check of [${request.method} ${request.path}] failed - denied." }
                Mono.just(false)
            }

    fun check(context: SecurityContext, path: String, method: String = "GET"): Mono<Boolean> =
        check(context, CheckRequest(path, method))

    /**
     * Checks every request and answers with the same keys, e.g. one per button of a page.
     */
    fun <K : Any> checkAll(context: SecurityContext, requests: Map<K, CheckRequest>): Mono<Map<K, Boolean>> =
        Flux.fromIterable(requests.entries)
            .concatMap { (key, request) -> check(context, request).map { key to it } }
            .collectMap({ it.first }, { it.second })

    private fun isolated(context: SecurityContext): SecurityContext =
        SimpleSecurityContext(context.principal, context.tenant).markPolicyEvaluation()

    private fun CheckRequest.toRequest(context: SecurityContext): Request {
        val current = context.getRequest<Request>()
        val routing = mapOf(
            AppIdCapable.APP_ID_KEY to (appId ?: current?.appId.orEmpty()),
            SpaceIdCapable.SPACE_ID_KEY to (spaceId ?: current?.spaceId.orEmpty()),
        ).filterValues { it.isNotEmpty() }
        return EvaluateRequest(
            path = path,
            method = method,
            remoteIp = current?.remoteIp ?: EvaluateRequest().remoteIp,
            attributes = attributes + resource.mapKeys { ResourceParts.PREFIX + it.key },
            headers = headers + routing,
        )
    }
}
