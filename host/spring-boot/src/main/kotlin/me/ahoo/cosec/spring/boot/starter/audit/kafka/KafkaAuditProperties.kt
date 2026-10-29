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

package me.ahoo.cosec.spring.boot.starter.audit.kafka

import me.ahoo.cosec.runtime.event.Delivery
import me.ahoo.cosec.spring.boot.starter.EnabledCapable
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.bind.DefaultValue
import org.springframework.core.env.Environment

@ConfigurationProperties(KafkaAuditProperties.PREFIX)
data class KafkaAuditProperties(
    @DefaultValue("true") override var enabled: Boolean = true,
    @DefaultValue(DEFAULT_TOPIC) var topic: String = DEFAULT_TOPIC,
    /** `required` makes a permit wait for the broker's acknowledgement; best effort by default (RFC 0006 §9.2). */
    @DefaultValue("best-effort") var delivery: Delivery = Delivery.BEST_EFFORT,
) : EnabledCapable {
    companion object {
        const val PREFIX: String = "cosec.audit.kafka"
        const val DEFAULT_TOPIC: String = "cosec-audit"
        const val DELIVERY_KEY: String = "$PREFIX.delivery"
    }
}

/**
 * `cosec.audit.kafka.delivery` as every reader of it parses it (the listener, its REQUIRED check, the module check), so
 * they never disagree: case, surrounding spaces and `-` for `_` do not matter, and an unknown value fails startup.
 */
internal fun kafkaAuditDelivery(environment: Environment): Delivery {
    val value = environment.getProperty(KafkaAuditProperties.DELIVERY_KEY) ?: return Delivery.BEST_EFFORT
    val name = value.trim().uppercase().replace('-', '_')
    return requireNotNull(Delivery.entries.firstOrNull { it.name == name }) {
        "${KafkaAuditProperties.DELIVERY_KEY}=[$value] is neither required nor best-effort."
    }
}
