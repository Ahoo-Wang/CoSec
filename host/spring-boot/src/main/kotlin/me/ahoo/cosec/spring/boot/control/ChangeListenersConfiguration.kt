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

package me.ahoo.cosec.spring.boot.control

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.audit.ChangeListener
import me.ahoo.cosec.control.audit.ChangeListeners
import me.ahoo.cosec.runtime.event.Delivery
import me.ahoo.cosec.spring.boot.pipeline.ListenerProperties
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import kotlin.time.toKotlinDuration

/** The change listeners (`CTL-66`), wherever the control plane is wired ([CoSecControlAutoConfiguration] imports it). */
@Configuration(proxyBeanMethods = false)
internal class ChangeListenersConfiguration {
    /**
     * Every change listener, delivering until shutdown. `cosec.control.audit.kafka.delivery=required` asks for a REQUIRED
     * Kafka record of every gated step: startup fails when that listener could not be created, rather than publishing
     * without it.
     */
    @Bean(destroyMethod = "drain")
    @ConditionalOnMissingBean
    fun cosecChangeListeners(
        listeners: ObjectProvider<ChangeListener>,
        properties: ControlProperties,
        listenerProperties: ObjectProvider<ListenerProperties>,
    ): ChangeListeners {
        val all = listeners.orderedStream().toList()
        val kafka = properties.audit.kafka
        check(kafka.delivery != Delivery.REQUIRED || all.any { it.name == KAFKA_CHANGE_LISTENER }) {
            "${ControlProperties.KAFKA_AUDIT_PREFIX}.delivery=required, but no Kafka change listener was created: it " +
                "needs cosec-kafka, a KafkaOperations bean and ${ControlProperties.KAFKA_AUDIT_PREFIX}.enabled=true."
        }
        // Drains what is queued at shutdown as the decision listeners do (cosec.observability.listener.drain-timeout).
        val drainTimeout = listenerProperties.getIfAvailable { ListenerProperties() }.drainTimeout.toKotlinDuration()
        return ChangeListeners(all, properties.audit.requiredTimeout.toKotlinDuration(), drainTimeout = drainTimeout)
    }
}
