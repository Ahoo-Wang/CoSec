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
import me.ahoo.cosec.serialization.JsonResources
import org.springframework.core.io.Resource
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import org.springframework.core.io.support.ResourcePatternResolver
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode

/**
 * Loads rate limit rules from JSON resources. Each resource holds one rule object or an array of rules.
 *
 * Like local policies, anything that could silently drop a rule, and with it a quota, fails loading:
 * - a file that cannot be read or built;
 * - the same rule id declared twice, in one file or in two;
 * - a location that does not exist or matches no file, unless it is [DEFAULT_LOCATION], which applications without
 *   rate limit rules leave in place.
 *
 * @param resourceResolver Resolves [locations]
 */
class LocalRateLimitRuleLoader(
    private val locations: Set<String>,
    private val resourceResolver: ResourcePatternResolver = PathMatchingResourcePatternResolver()
) : RateLimitRuleProvider {
    companion object {
        const val DEFAULT_LOCATION = "classpath:cosec-rate-limit/*-rate-limit.json"
        private const val KIND = "Rate limit rule"
        private val log = KotlinLogging.logger {}
    }

    private val loadedRules: List<RateLimitRule> by lazy {
        val loaded = JsonResources.resources(locations, KIND, setOf(DEFAULT_LOCATION), resourceResolver)
            .flatMap { resource ->
                log.info { "Load rate limit rules [$resource]." }
                readRules(resource).map { resource to it }
            }
        JsonResources.requireUniqueIds(KIND, loaded) { it.id }
        loaded.map { it.second }
    }

    override fun getRules(): List<RateLimitRule> = loadedRules

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
