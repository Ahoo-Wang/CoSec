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

package me.ahoo.cosec.spring.boot.pipeline

import com.auth0.jwt.algorithms.Algorithm
import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.document.route.RouteMap
import me.ahoo.cosec.document.route.RouteMapCodec
import me.ahoo.cosec.jwt.JwtTokenVerifier
import me.ahoo.cosec.runtime.AuthorizationChecker
import me.ahoo.cosec.runtime.Pipeline
import me.ahoo.cosec.runtime.PipelineOptions
import me.ahoo.cosec.runtime.attribute.AttributeProviders
import me.ahoo.cosec.runtime.attribute.BlacklistAttributeProvider
import me.ahoo.cosec.runtime.audit.AuditListener
import me.ahoo.cosec.runtime.authn.ApiKeyAuthenticator
import me.ahoo.cosec.runtime.authn.CompositeAuthenticator
import me.ahoo.cosec.runtime.authn.TokenAuthenticator
import me.ahoo.cosec.runtime.authn.UncheckedRevocations
import me.ahoo.cosec.runtime.event.DecisionListener
import me.ahoo.cosec.runtime.event.DecisionListeners
import me.ahoo.cosec.runtime.event.Delivery
import me.ahoo.cosec.runtime.identity.IdentityIssuer
import me.ahoo.cosec.runtime.snapshot.SuspendingSnapshotSource
import me.ahoo.cosec.spi.apikey.ApiKeyReader
import me.ahoo.cosec.spi.apikey.InMemoryApiKeyStore
import me.ahoo.cosec.spi.attribute.AttributeProvider
import me.ahoo.cosec.spi.audit.AuditEventSink
import me.ahoo.cosec.spi.authn.Authenticator
import me.ahoo.cosec.spi.authn.AuthnResult
import me.ahoo.cosec.spi.id.IdGenerator
import me.ahoo.cosec.spi.id.RandomIdGenerator
import me.ahoo.cosec.spi.membership.MembershipProvider
import me.ahoo.cosec.spi.ratelimit.RateLimiter
import me.ahoo.cosec.spi.token.RevocationReader
import me.ahoo.cosec.spi.token.TokenStore
import me.ahoo.cosec.spring.boot.ConditionalOnCoSecEnabled
import me.ahoo.cosec.spring.boot.apikey.apiKeysEnabled
import me.ahoo.cosec.spring.boot.audit.kafka.KafkaAuditProperties
import me.ahoo.cosec.spring.boot.audit.kafka.kafkaAuditDelivery
import me.ahoo.cosec.spring.boot.jwt.JwtProperties
import org.springframework.beans.factory.ListableBeanFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.annotation.AnnotationAwareOrderComparator
import org.springframework.core.env.Environment
import org.springframework.core.io.ResourceLoader
import org.springframework.util.ClassUtils
import java.util.IdentityHashMap
import kotlin.time.toKotlinDuration

/** `CoCacheTokenStore.reader`, when `cosec-cocache` is on the classpath. */
internal object CoCacheRevocation {
    private val available = ClassUtils.isPresent("me.ahoo.cosec.cocache.CoCacheTokenStore", null)

    fun readerOf(store: TokenStore?): RevocationReader? =
        if (available && store is me.ahoo.cosec.cocache.CoCacheTokenStore) store.reader else null
}
