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

import io.github.oshai.kotlinlogging.KLogger
import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.cosec.api.audit.AuditDecision
import me.ahoo.cosec.api.audit.AuditEvent
import me.ahoo.cosec.api.audit.AuditEventSink
import me.ahoo.cosec.serialization.CoSecJsonSerializer

/**
 * Default [AuditEventSink] writing single-line JSON to the logger named
 * [LOGGER_NAME]. DENY/TOO_MANY_REQUESTS log at WARN, ERROR at ERROR, ALLOW at DEBUG,
 * so default INFO level only surfaces denials.
 */
class LoggingAuditEventSink(
    private val logger: KLogger = KotlinLogging.logger(LoggingAuditEventSink.LOGGER_NAME),
) : AuditEventSink {

    override fun publish(event: AuditEvent) {
        when (event.authorization.decision) {
            AuditDecision.ALLOW -> logger.debug { CoSecJsonSerializer.writeValueAsString(event) }
            AuditDecision.EXPLICIT_DENY,
            AuditDecision.IMPLICIT_DENY,
            AuditDecision.TOO_MANY_REQUESTS,
            -> logger.warn { CoSecJsonSerializer.writeValueAsString(event) }

            AuditDecision.ERROR -> logger.error { CoSecJsonSerializer.writeValueAsString(event) }
        }
    }

    companion object {
        const val LOGGER_NAME: String = "me.ahoo.cosec.audit"
    }
}
