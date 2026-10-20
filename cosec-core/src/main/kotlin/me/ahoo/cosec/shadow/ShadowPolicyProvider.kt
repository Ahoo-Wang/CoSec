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

package me.ahoo.cosec.shadow

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.cosec.api.permission.AppPermission
import me.ahoo.cosec.api.policy.Policy
import me.ahoo.cosec.serialization.CoSecJsonSerializer
import me.ahoo.cosec.serialization.JsonResources
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import org.springframework.core.io.support.ResourcePatternResolver
import tools.jackson.core.JacksonException

/**
 * Supplies candidate policies for [ShadowAuthorization]. A candidate replaces the enforced policy with the same id,
 * or adds a new one. Called for every shadowed request, so implementations must answer from memory; returning the
 * same list instance lets the overlay be reused.
 */
fun interface ShadowPolicyProvider {
    fun getPolicies(): List<Policy>

    /**
     * Candidate app permissions. Each replaces the enforced app permission with the same app id; role grants are
     * kept. Same instance rules as [getPolicies].
     */
    fun getAppPermissions(): List<AppPermission> = emptyList()
}

/**
 * Loads candidate policies from JSON resources, one policy per file, and candidate app permissions the same way.
 *
 * Shadow candidates are only observed, but the provider is as strict as local policies: a candidate that is silently
 * left out would make the shadow run report "no difference" for a change it never evaluated, and that report is what
 * decides whether the change is promoted. So loading fails on:
 * - a file that cannot be read or built;
 * - two files declaring the same policy id, or the same app permission id;
 * - a location that does not exist or matches no file, unless it is [DEFAULT_LOCATION] or
 *   [DEFAULT_APP_PERMISSION_LOCATION].
 *
 * @param resourceResolver Resolves the locations
 */
class LocalShadowPolicyProvider(
    private val locations: Set<String>,
    private val appPermissionLocations: Set<String> = emptySet(),
    private val resourceResolver: ResourcePatternResolver = PathMatchingResourcePatternResolver()
) : ShadowPolicyProvider {
    companion object {
        const val DEFAULT_LOCATION = "classpath:cosec-shadow/*-policy.json"
        const val DEFAULT_APP_PERMISSION_LOCATION = "classpath:cosec-shadow/*-app-permission.json"
        private val log = KotlinLogging.logger {}
    }

    private val loadedPolicies: List<Policy> by lazy {
        load(locations, "Shadow policy", Policy::class.java) { it.id }
    }

    private val loadedAppPermissions: List<AppPermission> by lazy {
        load(appPermissionLocations, "Shadow app permission", AppPermission::class.java) { it.id }
    }

    override fun getPolicies(): List<Policy> = loadedPolicies

    override fun getAppPermissions(): List<AppPermission> = loadedAppPermissions

    private fun <T> load(locations: Set<String>, kind: String, type: Class<T>, idOf: (T) -> String): List<T> {
        val optionalLocations = setOf(DEFAULT_LOCATION, DEFAULT_APP_PERMISSION_LOCATION)
        val loaded = JsonResources.resources(locations, kind, optionalLocations, resourceResolver).map { resource ->
            log.info { "Load shadow candidate [$resource]." }
            try {
                resource to CoSecJsonSerializer.readValue(resource.contentAsByteArray, type)
            } catch (cause: JacksonException) {
                throw IllegalStateException("Failed to load shadow candidate [$resource].", cause)
            } catch (cause: IllegalArgumentException) {
                throw IllegalStateException("Failed to load shadow candidate [$resource].", cause)
            }
        }
        JsonResources.requireUniqueIds(kind, loaded, idOf)
        return loaded.map { it.second }
    }
}
