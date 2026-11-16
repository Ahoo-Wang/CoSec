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

package me.ahoo.cosec.control.audit

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.cosec.annotations.InternalCoSecApi

/**
 * Logs every change event (RFC 0007 §10.5); BEST_EFFORT. The change log is a record an operator routes, as the decision
 * audit is: it goes to the logger [LOGGER_NAME], whatever class writes it. The name is a sibling of `me.ahoo.cosec.audit`:
 * no package of any class, and nothing under it, so no class logger is its child and a level set on `me.ahoo.cosec.control`
 * does not reach it.
 */
class LoggingChangeListener(override val name: String = "logging") : ChangeListener {
    override suspend fun onChange(event: ChangeEvent) {
        log.info {
            "Change ${event.change.value} ${event.kind} on ${event.document} by ${event.operator.principal.value}@" +
                "${event.operator.tenant.value}: base=${event.base}, candidate=${event.candidate}, " +
                "escalations=${event.escalations.size}, confirmations=${event.confirmations.size}"
        }
    }

    companion object {
        const val LOGGER_NAME: String = "me.ahoo.cosec.changelog"
        private val log = KotlinLogging.logger(LOGGER_NAME)
    }
}
