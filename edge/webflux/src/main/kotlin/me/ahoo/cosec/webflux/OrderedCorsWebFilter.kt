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

package me.ahoo.cosec.webflux

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.webflux.PipelineWebFilter.Companion.REACTIVE_AUTHORIZATION_FILTER_ORDER
import org.springframework.core.Ordered
import org.springframework.web.cors.reactive.CorsConfigurationSource
import org.springframework.web.cors.reactive.CorsProcessor
import org.springframework.web.cors.reactive.CorsWebFilter
import org.springframework.web.cors.reactive.DefaultCorsProcessor

/**
 * The CORS filter the starter registers right before [PipelineWebFilter], so a preflight is answered and never decided
 * (`STA-5`). The starter builds it; an application declares a `CorsConfigurationSource` and gets it.
 */
class OrderedCorsWebFilter @InternalCoSecApi constructor(
    configSource: CorsConfigurationSource,
    processor: CorsProcessor = DefaultCorsProcessor(),
    private val order: Int = DEFAULT_ORDERED_CORS_WEB_FILTER_ORDER
) :
    Ordered, CorsWebFilter(configSource, processor) {
    @InternalCoSecApi
    companion object {
        const val DEFAULT_ORDERED_CORS_WEB_FILTER_ORDER = REACTIVE_AUTHORIZATION_FILTER_ORDER - 1
    }

    override fun getOrder(): Int {
        return this.order
    }
}
