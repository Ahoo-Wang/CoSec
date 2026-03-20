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
package me.ahoo.cosec.api.principal

import me.ahoo.cosec.api.CoSec
import java.security.Principal

/**
 * Core principal interface representing a user or automated agent.
 *
 * A Principal is the entity that is being authenticated and authorized.
 * It contains the user's identity, roles, policies, and attributes.
 *
 * CoSecPrincipal extends [java.security.Principal] and adds:
 * - Unique identifier
 * - Role assignments
 * - Policy assignments
 * - Custom attributes
 * - Authentication state
 *
 * @see RoleCapable
 * @see PolicyCapable
 * @see TenantPrincipal
 */
interface CoSecPrincipal :
    Principal,
    PolicyCapable,
    RoleCapable {
    //endregion

    /** Unique identifier for this principal */
    val id: String

    /**
     * Returns the name of this principal, which is the same as [id].
     */
    override fun getName(): String = id

    /**
     * Custom attributes associated with this principal.
     * These can be used to store additional user information.
     */
    val attributes: Map<String, Any>

    /**
     * Whether this principal is anonymous (unauthenticated).
     *
     * An anonymous principal has [id] equal to [ANONYMOUS_ID].
     */
    val anonymous: Boolean
        get() = ANONYMOUS_ID == id

    /**
     * Whether this principal is authenticated.
     * Inverse of [anonymous].
     */
    val authenticated: Boolean
        get() = !anonymous

    companion object {
        //region ROOT - Root user with full permissions
        /** System property key for root user ID */
        const val ROOT_KEY = "cosec.root"

        /**
         * The root user ID.
         * Root users have full permissions and bypass all policy checks.
         * Can be customized via system property "cosec.root".
         */
        val ROOT_ID: String = System.getProperty(ROOT_KEY, CoSec.COSEC)

        //endregion

        /**
         * The anonymous user ID used for unauthenticated requests.
         */
        const val ANONYMOUS_ID = CoSec.DEFAULT

        /**
         * Extension property to check if a principal is the root user.
         *
         * @return true if this principal's ID equals [ROOT_ID]
         */
        val CoSecPrincipal.isRoot: Boolean
            get() = ROOT_ID == id
    }
}
