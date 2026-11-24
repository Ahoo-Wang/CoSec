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
import me.ahoo.cosec.control.api.DocumentCache
import me.ahoo.cosec.control.lifecycle.ControlDerived
import me.ahoo.cosec.control.lifecycle.WorkBudget
import me.ahoo.cosec.spring.boot.pipeline.DerivedTimeouts
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import kotlin.time.toJavaDuration

/**
 * The control plane's work memory, both computed from the heap, nothing configured. The document cache (DR1-F-5): one
 * per host, shared by simulate, explain, analyze and the lock-out check. The work budget (`CTL-85`): one per host, which the operators' and the service profile's control API,
 * the in-process facade, the control plane's reads and the lock-out check all hold their calls' work in. An eighth of
 * the heap, a call waiting at most `StageBudgets.workWait` (half of `cosec.pipeline.timeout`): nothing is configured.
 */
@Configuration(proxyBeanMethods = false)
internal class ControlWorkConfiguration {
    /** A third of the documents' share of the heap ([ControlDerived.DOCUMENT_HOLDERS]). */
    @Bean
    fun cosecControlDocumentCache(): DocumentCache = DocumentCache(ControlDerived.documentCacheBytes())

    @Bean
    fun cosecControlWork(environment: Environment): WorkBudget =
        ControlDerived.workBudget(DerivedTimeouts.budgets(environment).workWait.toJavaDuration())
}
