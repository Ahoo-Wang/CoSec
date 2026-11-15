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

package me.ahoo.cosec.spring.boot.control

import kotlinx.coroutines.future.await
import me.ahoo.cocache.core.CoCacheFactory
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.cocache.control.CoCacheControlStore
import me.ahoo.cosec.cocache.shadow.CoCacheShadowReports
import me.ahoo.cosec.cocache.snapshot.PolicySnapshotCache
import me.ahoo.cosec.cocache.snapshot.RedisSnapshotDocumentStore
import me.ahoo.cosec.control.api.ControlHttp
import me.ahoo.cosec.control.api.ControlPlane
import me.ahoo.cosec.control.api.DefaultControlPlane
import me.ahoo.cosec.control.api.Simulator
import me.ahoo.cosec.control.audit.ChangeListener
import me.ahoo.cosec.control.audit.ChangeListeners
import me.ahoo.cosec.control.audit.LoggingChangeListener
import me.ahoo.cosec.control.authz.ControlAuthorizer
import me.ahoo.cosec.control.authz.DocumentConfirmation
import me.ahoo.cosec.control.authz.PipelineControlAuthorizer
import me.ahoo.cosec.control.change.ChangeAuthority
import me.ahoo.cosec.control.change.ChangeId
import me.ahoo.cosec.control.change.ChangeOptions
import me.ahoo.cosec.control.change.Changes
import me.ahoo.cosec.control.change.ShadowOptions
import me.ahoo.cosec.control.store.ControlMaintenance
import me.ahoo.cosec.control.store.ControlStore
import me.ahoo.cosec.control.store.RetentionPolicy
import me.ahoo.cosec.control.store.Revisions
import me.ahoo.cosec.engine.flat.CompiledDocument
import me.ahoo.cosec.kafka.KafkaChangeListener
import me.ahoo.cosec.model.AppId
import me.ahoo.cosec.runtime.AuthorizationChecker
import me.ahoo.cosec.runtime.Pipeline
import me.ahoo.cosec.runtime.attribute.AttributeProviders
import me.ahoo.cosec.runtime.event.Delivery
import me.ahoo.cosec.runtime.snapshot.DocumentSnapshotViews
import me.ahoo.cosec.runtime.snapshot.DocumentSnapshots
import me.ahoo.cosec.runtime.snapshot.DocumentState
import me.ahoo.cosec.runtime.snapshot.PlatformCompiler
import me.ahoo.cosec.runtime.snapshot.SnapshotEntry
import me.ahoo.cosec.runtime.snapshot.SnapshotSource
import me.ahoo.cosec.runtime.snapshot.SuspendingSnapshotSource
import me.ahoo.cosec.spi.authn.Authenticator
import me.ahoo.cosec.spi.id.IdGenerator
import me.ahoo.cosec.spi.id.RandomIdGenerator
import me.ahoo.cosec.spi.membership.MembershipProvider
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.cosec.spi.token.RevocationReader
import me.ahoo.cosec.spi.token.TokenStore
import me.ahoo.cosec.spring.boot.CoSecProperties
import me.ahoo.cosec.spring.boot.ConditionalOnCoSecEnabled
import me.ahoo.cosec.spring.boot.StartupTask
import me.ahoo.cosec.spring.boot.StartupTaskExclusion
import me.ahoo.cosec.spring.boot.jwt.JwtProperties
import me.ahoo.cosec.spring.boot.pipeline.CoCacheRevocation
import me.ahoo.cosec.spring.boot.pipeline.CoSecPipelineAutoConfiguration
import me.ahoo.cosec.spring.boot.pipeline.ListenerProperties
import me.ahoo.cosec.spring.boot.pipeline.RevocationProperties
import me.ahoo.cosec.spring.boot.pipeline.RuntimeAuthorizationProperties
import me.ahoo.cosec.spring.boot.pipeline.credentialHeaders
import me.ahoo.cosec.spring.boot.pipeline.toOptions
import org.springframework.beans.factory.ListableBeanFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.core.env.Environment
import org.springframework.kafka.core.KafkaOperations
import java.time.Clock
import java.time.Instant
import kotlin.time.toKotlinDuration

/** The control plane's own snapshot source, held apart from the data plane's (see the bean's comment). */
internal class ControlSnapshot(
    val source: SnapshotSource<CompiledDocument, CompiledDocument>,
    /** The clock [source] dates its entries with; confirmation compares against it. */
    val clock: Clock = Clock.systemUTC(),
) : AutoCloseable {
    override fun close() = source.close()
}
