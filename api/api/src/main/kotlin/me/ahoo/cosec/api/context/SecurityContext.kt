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

package me.ahoo.cosec.api.context

import me.ahoo.cosec.api.principal.CoSecPrincipal
import me.ahoo.cosec.api.tenant.TenantCapable

/**
 * Security context containing information about the current user/request.
 *
 * This interface provides access to:
 * - The current principal (user)
 * - The tenant information
 * - Custom attributes for passing data between components
 *
 * The security context is typically populated during authentication
 * and remains available throughout the request lifecycle.
 *
 * @see CoSecPrincipal
 * @see TenantCapable
 * @see Authorization
 */
/** The validated scope of a request (RFC 0004 §4.6): its app, its space, and the principal's roles there. */
interface SecurityScope {
    val appId: String

    /** Empty for the default space. */
    val spaceId: String

    /** The principal's roles in [spaceId], in claim order. */
    val roles: List<String>
}

interface SecurityContext : TenantCapable {
    /**
     * Key for storing/retrieving SecurityContext from request attributes.
     */
    companion object {
        const val KEY = "COSEC_SECURITY_CONTEXT"
    }

    /**
     * Custom attributes for storing additional context data.
     * These attributes can be used to pass data between different
     * components during the authorization process.
     */
    val attributes: MutableMap<String, Any>

    /**
     * The current principal (user) making the request.
     *
     * @see CoSecPrincipal
     */
    val principal: CoSecPrincipal

    /**
     * The scope the request was authorized in, as `ScopeResolver` validated it (RFC 0004 X6): read it rather than the
     * `CoSec-App-Id` and `CoSec-Space-Id` headers, which are what the client declared. Null outside the 8.0 pipeline.
     */
    val scope: SecurityScope? get() = null

    /**
     * Gets an attribute value by key.
     *
     * @param attributeKey The attribute key
     * @return The attribute value, or null if not found
     */
    fun <V> getAttributeValue(attributeKey: String): V? {
        @Suppress("UNCHECKED_CAST")
        return attributes[attributeKey] as V?
    }

    /**
     * Gets a required attribute value, throwing if not found.
     *
     * @param attributeKey The attribute key
     * @return The attribute value
     * @throws IllegalArgumentException if the attribute is not found
     */
    fun <V> getRequiredAttributeValue(attributeKey: String): V = requireNotNull(getAttributeValue(attributeKey))

    /**
     * Sets an attribute value.
     *
     * @param attributeKey The attribute key
     * @param value The value to set
     * @return This security context for chaining
     */
    fun setAttributeValue(
        attributeKey: String,
        value: Any
    ): SecurityContext {
        attributes[attributeKey] = value
        return this
    }
}
