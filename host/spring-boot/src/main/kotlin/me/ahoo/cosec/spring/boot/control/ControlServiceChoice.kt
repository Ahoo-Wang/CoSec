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
import org.springframework.core.env.Environment

/**
 * `STA-11`: an application has one `ControlService`. The starter's is the in-process one where this instance runs the
 * control plane, the HTTP one where [KEY] names a control-plane host, and none otherwise; an instance that runs the
 * control plane and names a host as well is a configuration mistake, which startup refuses rather than pick one.
 */
internal object ControlServiceChoice {
    /** `cosec.control.service-url`. */
    const val KEY = "${ControlProperties.PREFIX}.service-url"

    /** The class of the HTTP implementation, which the starter wires by class path. */
    const val HTTP_SERVICE = "me.ahoo.cosec.control.http.HttpControlService"

    /** Whether [environment] names a control-plane host. */
    fun named(environment: Environment): Boolean = !environment.getProperty(KEY).isNullOrBlank()

    /** Fails startup on an instance that runs the control plane ([environment] of one) and names a host as well. */
    fun check(environment: Environment) {
        check(!named(environment)) {
            "This instance runs the control plane, and $KEY names a control-plane host as well: an application has one " +
                "ControlService. Embed the control plane (remove $KEY), or call the host (set " +
                "${ControlPlaneCondition.ENABLED_KEY}=false, or leave cosec-control out) (STA-11)."
        }
    }
}
