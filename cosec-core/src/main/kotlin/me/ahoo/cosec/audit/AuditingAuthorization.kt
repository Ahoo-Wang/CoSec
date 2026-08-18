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

package me.ahoo.cosec.audit

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.cosec.Delegated
import me.ahoo.cosec.api.audit.AuditEventSink
import me.ahoo.cosec.api.authorization.Authorization
import me.ahoo.cosec.api.authorization.AuthorizeResult
import me.ahoo.cosec.api.context.SecurityContext
import me.ahoo.cosec.api.context.request.Request
import me.ahoo.cosec.authorization.VerifyContextScope
import me.ahoo.cosec.token.TokenVerificationContexts.getTokenVerificationException
import me.ahoo.cosec.token.toAuthorizeResult
import reactor.core.publisher.Mono

/**
 * [Authorization] decorator that publishes an audit event for every decision.
 * Every audit step (event construction and publishing) is failure-isolated
 * and never alters the delegated result or error signals.
 */
class AuditingAuthorization(
    override val delegate: Authorization,
    private val auditEventSink: AuditEventSink,
) : Authorization,
    Delegated<Authorization> {

    override fun authorize(request: Request, context: SecurityContext): Mono<AuthorizeResult> {
        return Mono.defer {
            val startNanos = System.nanoTime()
            val verifyContextScope = VerifyContextScope()
            Mono.defer { delegate.authorize(request = request, context = context) }
                .doOnSuccess { result ->
                    publishSafely {
                        val effectiveResult = when {
                            result == null -> AuthorizeResult.IMPLICIT_DENY
                            result.authorized -> result
                            else -> context.getTokenVerificationException()?.toAuthorizeResult() ?: result
                        }
                        AuditEventExtractor.fromDecision(
                            request = request,
                            context = context,
                            result = effectiveResult,
                            elapsedNanos = System.nanoTime() - startNanos,
                            verifyContext = verifyContextScope.value,
                        )
                    }
                }
                .doOnError { error ->
                    publishSafely {
                        AuditEventExtractor.fromError(
                            request = request,
                            context = context,
                            error = error,
                            elapsedNanos = System.nanoTime() - startNanos,
                            verifyContext = verifyContextScope.value,
                        )
                    }
                }
                .contextWrite { it.put(VerifyContextScope::class.java, verifyContextScope) }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private inline fun publishSafely(eventSupplier: () -> me.ahoo.cosec.api.audit.AuditEvent) {
        try {
            auditEventSink.publish(eventSupplier())
        } catch (e: Exception) {
            log.warn(e) { "Failed to publish audit event." }
        }
    }

    companion object {
        private val log = KotlinLogging.logger {}
    }
}
