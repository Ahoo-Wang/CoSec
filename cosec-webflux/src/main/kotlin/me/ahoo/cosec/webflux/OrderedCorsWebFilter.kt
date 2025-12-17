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

package me.ahoo.cosec.webflux

import me.ahoo.cosec.webflux.ReactiveSecurityFilter.Companion.SECURITY_FILTER_ORDER
import org.springframework.core.Ordered
import org.springframework.web.cors.reactive.CorsConfigurationSource
import org.springframework.web.cors.reactive.CorsProcessor
import org.springframework.web.cors.reactive.CorsWebFilter
import org.springframework.web.cors.reactive.DefaultCorsProcessor

class OrderedCorsWebFilter(
    configSource: CorsConfigurationSource,
    processor: CorsProcessor = DefaultCorsProcessor(),
    private val order: Int = SECURITY_FILTER_ORDER - 1
) :
    Ordered, CorsWebFilter(configSource, processor) {
    override fun getOrder(): Int {
        return this.order
    }
}
