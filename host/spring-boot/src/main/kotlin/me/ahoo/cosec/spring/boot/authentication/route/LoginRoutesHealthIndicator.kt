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

package me.ahoo.cosec.spring.boot.authentication.route

import me.ahoo.cosec.annotations.InternalCoSecApi
import org.springframework.boot.health.contributor.Health
import org.springframework.boot.health.contributor.HealthIndicator

/**
 * `cosecLoginRoutes`: always UP, with each served login route's state as a detail: `not refused` until a document
 * refused it, then `denied since`, with when and which refusal it was ([LoginRouteDenials]). A refused route does not make the host unready: a
 * document may permit it again at any time, and readiness gates on documents being installed, not on what they say.
 */
@InternalCoSecApi
class LoginRoutesHealthIndicator(
    private val routes: Map<String, String>,
    private val denials: LoginRouteDenials,
) : HealthIndicator {
    override fun health(): Health {
        val denied = denials.denied()
        val builder = Health.up()
        routes.values.sorted().forEach { property ->
            val denial = denied[property]
            builder.withDetail(
                property,
                if (denial == null) "not refused" else "denied since ${denial.since}: ${denial.why}"
            )
        }
        return builder.build()
    }
}
