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

package me.ahoo.cosec.limiter

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.cosec.serialization.CoSecJsonSerializer
import org.springframework.core.io.Resource
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import java.io.FileNotFoundException

/**
 * Loads rate limit rules from JSON resources. Each resource holds one rule object or an array of rules.
 *
 * Unlike policies, an unreadable rule fails startup: silently dropping a rate limit would remove a protection
 * without anyone noticing.
 */
class LocalRateLimitRuleLoader(
    private val locations: Set<String>
) : RateLimitRuleProvider {
    companion object {
        private val log = KotlinLogging.logger {}
        private val resourceResolver = PathMatchingResourcePatternResolver()
    }

    private val loadedRules: List<RateLimitRule> by lazy {
        loadRules().also { RateLimitRuleProvider.requireUniqueIds(it) }
    }

    override fun getRules(): List<RateLimitRule> = loadedRules

    private fun loadRules(): List<RateLimitRule> =
        locations
            .flatMap { location ->
                try {
                    resourceResolver.getResources(location).filter { it.exists() }
                } catch (_: FileNotFoundException) {
                    log.info { "No rate limit rules found at [$location]." }
                    emptyList()
                }
            }.flatMap { resource ->
                log.info { "Load rate limit rules [$resource]." }
                readRules(resource)
            }

    private fun readRules(resource: Resource): List<RateLimitRule> {
        try {
            val jsonNode = CoSecJsonSerializer.readTree(resource.contentAsByteArray)
            val ruleNodes: List<JsonNode> = if (jsonNode.isArray) jsonNode.values().toList() else listOf(jsonNode)
            return ruleNodes.map { CoSecJsonSerializer.treeToValue(it, RateLimitRule::class.java) }
        } catch (cause: JacksonException) {
            throw IllegalStateException("Failed to load rate limit rules [$resource].", cause)
        } catch (cause: IllegalArgumentException) {
            throw IllegalStateException("Failed to load rate limit rules [$resource].", cause)
        }
    }
}
