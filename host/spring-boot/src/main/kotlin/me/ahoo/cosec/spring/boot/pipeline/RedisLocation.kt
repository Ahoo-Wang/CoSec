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

import me.ahoo.cosec.annotations.InternalCoSecApi

/** Where the Redis behind [factory] is, without a credential: `host:port/database`, the cluster's nodes or the sentinels. */
internal object RedisLocation {
    fun describe(factory: Any?): String {
        val unknown = "an unknown Redis (connection factory ${factory?.javaClass?.simpleName ?: "not found"})"
        // Lettuce is optional: a host with another client reaches this with the class missing.
        return try {
            lettuce(factory) ?: unknown
        } catch (_: LinkageError) {
            unknown
        }
    }

    private fun lettuce(factory: Any?): String? {
        val lettuce = factory as? org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
            ?: return null
        lettuce.clusterConfiguration?.let { cluster ->
            return "cluster " + cluster.clusterNodes.joinToString(prefix = "[", postfix = "]") { "${it.host}:${it.port}" }
        }
        lettuce.sentinelConfiguration?.let { sentinel ->
            return "sentinel master [${sentinel.master?.name}] via " +
                sentinel.sentinels.joinToString(prefix = "[", postfix = "]") { "${it.host}:${it.port}" } +
                "/${lettuce.database}"
        }
        return "${lettuce.hostName}:${lettuce.port}/${lettuce.database}"
    }
}
