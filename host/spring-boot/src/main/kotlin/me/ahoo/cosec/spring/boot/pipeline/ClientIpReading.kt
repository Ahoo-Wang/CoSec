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

package me.ahoo.cosec.spring.boot.pipeline

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.cosec.annotations.InternalCoSecApi
import org.springframework.beans.factory.ListableBeanFactory
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory
import org.springframework.boot.cloud.CloudPlatform
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.core.env.Environment
import org.springframework.util.ClassUtils
import org.springframework.util.ReflectionUtils

/**
 * How this host reads the client address (`PIP-16`): exactly once, from one party it trusts. Where the server has
 * already resolved it, CoSec takes the server's answer and never reads `X-Forwarded-For` again; where the server took
 * it from a header a client writes, CoSec reads the header and never the server's answer.
 *
 * - [maxTrustedIndex] `0`: the host's remote address, as the server gives it.
 * - [peerIsConnection] false: the host's remote address is a client's claim, so the address comes from
 *   `X-Forwarded-For` alone ([me.ahoo.cosec.runtime.http.XForwardedRemoteIpResolver]).
 */
internal class ClientIpReading(val maxTrustedIndex: Int, val peerIsConnection: Boolean, val why: String) {
    override fun toString(): String = why
}
