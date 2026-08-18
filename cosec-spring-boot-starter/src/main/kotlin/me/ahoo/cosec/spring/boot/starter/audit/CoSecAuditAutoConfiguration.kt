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

import me.ahoo.cosec.api.audit.AuditEventSink
import me.ahoo.cosec.api.authorization.Authorization
import me.ahoo.cosec.audit.AuditingAuthorization
import me.ahoo.cosec.audit.LoggingAuditEventSink
import me.ahoo.cosec.spring.boot.starter.ConditionalOnCoSecEnabled
import me.ahoo.cosec.spring.boot.starter.authorization.CoSecAuthorizationAutoConfiguration
import me.ahoo.cosid.IdGenerator
import me.ahoo.cosid.jvm.UuidGenerator
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean

/**
 * CoSec Audit AutoConfiguration.
 *
 * @author ahoo wang
 */
@AutoConfiguration(after = [CoSecAuthorizationAutoConfiguration::class])
@ConditionalOnCoSecEnabled
@ConditionalOnAuditEnabled
@EnableConfigurationProperties(AuditProperties::class)
class CoSecAuditAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(AuditEventSink::class)
    fun auditEventSink(): AuditEventSink {
        return LoggingAuditEventSink()
    }

    @Bean(AUDITING_AUTHORIZATION_BEAN_NAME)
    @ConditionalOnBean(
        name = [CoSecAuthorizationAutoConfiguration.BEAN_NAME],
        annotation = [CoSecAuthorizationAutoConfiguration.AutoConfigured::class],
    )
    fun cosecAuditingAuthorization(
        @Qualifier(CoSecAuthorizationAutoConfiguration.BEAN_NAME) authorization: Authorization,
        auditEventSink: AuditEventSink,
        idGeneratorProvider: ObjectProvider<IdGenerator>,
    ): AuditingAuthorization {
        return AuditingAuthorization(
            authorization,
            auditEventSink,
            idGeneratorProvider.getIfAvailable { UuidGenerator.INSTANCE },
        )
    }

    companion object {
        const val AUDITING_AUTHORIZATION_BEAN_NAME = "cosecAuditingAuthorization"
    }
}
