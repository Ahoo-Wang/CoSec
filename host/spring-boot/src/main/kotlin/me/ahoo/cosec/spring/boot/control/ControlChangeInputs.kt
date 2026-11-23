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
import me.ahoo.cosec.control.api.ControlLockout
import me.ahoo.cosec.control.api.Simulator
import me.ahoo.cosec.control.lifecycle.CandidateCompile
import me.ahoo.cosec.control.lifecycle.LockoutCheck
import me.ahoo.cosec.control.lifecycle.WorkBudget
import me.ahoo.cosec.control.store.Revisions
import me.ahoo.cosec.model.AppId
import me.ahoo.cosec.runtime.attribute.AttributeProviders
import me.ahoo.cosec.spring.boot.pipeline.RouteMaps
import me.ahoo.cosec.spring.boot.pipeline.credentialHeaders
import org.springframework.core.env.Environment
import org.springframework.core.io.ResourceLoader

/** What the change flow is built with besides its stores: how candidates compile, and the lock-out check. */
internal object ControlChangeInputs {
    /**
     * Candidates compile against the host's providers and credential headers, as enforcement compiles them, and are
     * compared with the services' route maps when `cosec.control.analysis.route-maps` names them (UC-1).
     */
    fun compile(
        attributes: AttributeProviders,
        environment: Environment,
        properties: ControlProperties,
        resourceLoader: ResourceLoader,
    ): CandidateCompile = CandidateCompile(
        attributes.catalog,
        credentialHeaders(environment),
        RouteMaps.loadAnalysis(properties.analysis.routeMaps, resourceLoader),
    )

    /** UC-6: a change that would keep its operator out of the control plane needs an acknowledgement. */
    fun lockout(
        revisions: Revisions,
        attributes: AttributeProviders,
        environment: Environment,
        properties: ControlProperties,
        work: WorkBudget,
    ): LockoutCheck = ControlLockout(
        revisions,
        Simulator(attributes = attributes.catalog, credentialHeaders = credentialHeaders(environment)),
        AppId(properties.appId),
        work = work,
    )
}
