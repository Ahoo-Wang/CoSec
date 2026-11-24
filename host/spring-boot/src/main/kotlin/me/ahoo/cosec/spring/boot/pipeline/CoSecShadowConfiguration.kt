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

import me.ahoo.cocache.core.CoCacheFactory
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.cocache.shadow.CoCacheCandidateStore
import me.ahoo.cosec.cocache.shadow.CoCacheShadowReports
import me.ahoo.cosec.engine.CompiledDocument
import me.ahoo.cosec.runtime.shadow.CachingCandidateSource
import me.ahoo.cosec.runtime.shadow.CandidateSource
import me.ahoo.cosec.runtime.shadow.ShadowListener
import me.ahoo.cosec.runtime.shadow.ShadowRecorder
import me.ahoo.cosec.runtime.shadow.ShadowReporter
import me.ahoo.cosec.runtime.snapshot.DocumentSnapshotViews
import me.ahoo.cosec.runtime.snapshot.DocumentState
import me.ahoo.cosec.runtime.snapshot.PlatformCompiler
import me.ahoo.cosec.runtime.snapshot.SnapshotEntry
import me.ahoo.cosec.runtime.snapshot.SnapshotSource
import me.ahoo.cosec.spi.shadow.CandidateStore
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.cosec.spring.boot.CoSecProperties
import me.ahoo.cosec.spring.boot.control.ControlPlaneCondition
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import org.springframework.core.io.ResourceLoader
import java.time.Clock

/**
 * The data plane's part of shadow windows (RFC 0007 §6.4): candidates read from Redis and compiled with this
 * instance's compiler, a [ShadowListener] among the decision listeners, and this instance's reports and status
 * written every report interval ([DerivedSnapshotSettings.reportInterval]). A candidate that does not compile here is reported as `CANDIDATE_ERROR`, which
 * blocks its window.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(name = ["me.ahoo.cosec.cocache.shadow.CoCacheCandidateStore"])
@ConditionalOnBean(CoCacheFactory::class)
@ConditionalOnProperty(
    prefix = RuntimeAuthorizationProperties.PREFIX + ".shadow",
    name = ["enabled"],
    matchIfMissing = true
)
@InternalCoSecApi
class CoSecShadowConfiguration {
    /** Under the instance id the status is written with (`SNP-25`): one instance, one set of keys. */
    @Bean
    @ConditionalOnMissingBean
    fun cosecShadowRecorder(
        reporting: ObjectProvider<InstanceReporting>,
        properties: RuntimeAuthorizationProperties,
    ): ShadowRecorder = ShadowRecorder(
        reporting.ifAvailable?.reporter?.instance ?: ShadowInstanceId.forThisStart(properties.shadow.instanceId)
    )

    /**
     * Candidates read through the control plane's store on a host that runs it (both would own the one candidate
     * cache), otherwise through a reader of their own.
     */
    @Bean
    @ConditionalOnMissingBean
    fun cosecCandidateSource(
        factory: CoCacheFactory,
        stores: ObjectProvider<CandidateStore>,
        compiler: PlatformCompiler,
        cosec: CoSecProperties,
        resources: ResourceLoader,
        environment: Environment,
    ): CandidateSource {
        val store = stores.ifUnique ?: CoCacheCandidateStore.create(
            factory,
            cosec.keyPrefix,
            readOnly = !ControlPlaneCondition.wired(resources.classLoader, environment),
        )
        return CachingCandidateSource(store, compiler::compileCandidate)
    }

    /** The status configuration's, when it runs ([CoSecInstanceStatusAutoConfiguration]); otherwise this one. */
    @Bean
    @ConditionalOnMissingBean
    fun cosecShadowReports(factory: CoCacheFactory, cosec: CoSecProperties): CoCacheShadowReports =
        CoCacheShadowReports.create(factory, cosec.keyPrefix)

    @Bean
    @ConditionalOnMissingBean
    fun cosecShadowListener(
        candidates: CandidateSource,
        recorder: ShadowRecorder,
        properties: RuntimeAuthorizationProperties,
        source: SnapshotSource<CompiledDocument, CompiledDocument>,
    ): ShadowListener = ShadowListener(
        SHADOW_LISTENER,
        properties.shadow.sampleRate,
        candidates,
        recorder,
        // The installed documents, as the pipeline reads them: a tenant candidate may need an app document (RC1-17).
        views = DocumentSnapshotViews(source, platformRequired = true),
    )

    /**
     * Reports every report interval ([DerivedSnapshotSettings.reportInterval]). A window is forgotten once its candidate is no longer staged and its last
     * report is written (#1210), as read from the store of record [candidates] reads from; with an application's own
     * [CandidateSource] that is not a [CachingCandidateSource], from the only [CandidateStore], else never.
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    fun cosecShadowReporting(
        recorder: ShadowRecorder,
        sink: CoCacheShadowReports,
        source: SnapshotSource<CompiledDocument, CompiledDocument>,
        environment: Environment,
        candidates: CandidateSource,
        stores: ObjectProvider<CandidateStore>,
        status: ObjectProvider<InstanceReporting>,
    ): ShadowReporting {
        val clock = Clock.systemUTC()
        val installed = { installedDocuments(source.entries()) }
        val interval = DerivedSnapshotSettings.reportInterval(environment)
        val caching = candidates as? CachingCandidateSource
        val store = caching?.store ?: stores.ifUnique
        // The status and the resident documents are written by the host's reporter, after this cycle's reports.
        return ShadowReporting(
            ShadowReporter(
                recorder,
                sink,
                installed,
                clock,
                interval,
                store,
                caching,
                status = status.ifAvailable?.reporter
            )
        )
    }

    @InternalCoSecApi
    companion object {
        const val SHADOW_LISTENER = "shadow"

        /**
         * What an instance's heartbeat reports as installed (RFC 0007 §6.5, Q29): the documents it can evaluate. One it
         * read but could not compile has a revision, yet only fails the requests that reach it; counting it would show
         * a rollout as complete on an instance that cannot use it. That instance still reports, so it stays among the
         * ones the propagation query counts, without the document.
         */
        internal fun installedDocuments(entries: List<SnapshotEntry<*>>): Map<SnapshotKey, Long> = entries
            .filter { it.revision > 0 && it.state is DocumentState.Present }
            .associate { it.key to it.revision }
    }
}
