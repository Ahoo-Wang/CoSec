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

package me.ahoo.cosec.spring.boot

import org.springframework.core.env.ConfigurableEnvironment
import org.springframework.core.env.EnumerablePropertySource

/**
 * CoCache settings of a cache CoSec no longer creates. They are not `cosec.*` keys, so [UnknownProperties] does not see
 * them, and CoCache accepts a name CoSec once published: without this check they would silently do nothing. The
 * memberships cache of the 8.0 previews (`cocache.caches.MembershipCache.*`, its `ttl` above all) is held by each instance
 * now, for `cosec.authorization.membership.ttl` (DR1-D-2).
 */
internal object RetiredCacheSettings {
    /** The settings' prefix, with every separator and bracket removed, in lower case, as [normalized] writes a key. */
    private const val MEMBERSHIP_CACHE = "cocachecachesmembershipcache"

    /** Every key of [environment] that sets the retired memberships cache, sorted. */
    fun of(environment: ConfigurableEnvironment): List<String> = environment.propertySources
        .filterIsInstance<EnumerablePropertySource<*>>()
        .flatMap { it.propertyNames.asList() }
        .filter { normalized(it).startsWith(MEMBERSHIP_CACHE) }
        .distinct()
        .sorted()

    /** `cocache.caches.[MembershipCache].ttl`, `cocache.caches.MembershipCache.ttl` and `COCACHE_CACHES_MEMBERSHIPCACHE_TTL` alike. */
    private fun normalized(key: String): String = key.lowercase().filter { it.isLetterOrDigit() }

    /** Fails startup when [environment] still sets the retired memberships cache. */
    fun check(environment: ConfigurableEnvironment) {
        val keys = of(environment)
        check(keys.isEmpty()) {
            "CoSec no longer has a MembershipCache: memberships are held by each instance, never in Redis, so " +
                "${keys.joinToString()} would do nothing. Set cosec.authorization.membership.ttl for how long an " +
                "instance keeps a principal's memberships, and remove these: " +
                "https://cosec.ahoo.me/operations/upgrading-previews#from-8-0-0-beta-7"
        }
    }
}
