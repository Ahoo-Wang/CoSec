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

package me.ahoo.cosec.spring.boot.starter.audit

import me.ahoo.cosec.spring.boot.starter.ConditionalOnCoSecEnabled
import me.ahoo.cosec.spring.boot.starter.opentelemetry.CoSecOpenTelemetryAutoConfiguration
import org.springframework.beans.factory.config.BeanFactoryPostProcessor
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean

@AutoConfiguration(
    after = [CoSecAuditAutoConfiguration::class],
    afterName = ["me.ahoo.cosec.spring.boot.starter.opentelemetry.CoSecOpenTelemetryAutoConfiguration"],
)
@ConditionalOnCoSecEnabled
@ConditionalOnAuditEnabled
@ConditionalOnBean(name = [CoSecAuditAutoConfiguration.AUDITING_AUTHORIZATION_BEAN_NAME])
@ConditionalOnMissingBean(
    name = [
        CoSecOpenTelemetryAutoConfiguration.TRACING_AUTHORIZATION_BEAN_NAME,
        CoSecOpenTelemetryAutoConfiguration.AUDITED_TRACING_AUTHORIZATION_BEAN_NAME,
    ],
)
class CoSecAuditFallbackAutoConfiguration : BeanFactoryPostProcessor {
    override fun postProcessBeanFactory(beanFactory: ConfigurableListableBeanFactory) {
        beanFactory.getBeanDefinition(
            CoSecAuditAutoConfiguration.AUDITING_AUTHORIZATION_BEAN_NAME
        ).isPrimary = true
    }
}
