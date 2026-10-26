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

package me.ahoo.cosec.control.grant

import me.ahoo.cosec.model.AppId
import me.ahoo.cosec.model.Match
import me.ahoo.cosec.model.SpaceId

/** A set of values of one scope dimension: every value, or finitely many. */
sealed interface Dimension<T> {
    fun contains(value: T): Boolean

    class Every<T> : Dimension<T> {
        override fun contains(value: T): Boolean = true

        override fun equals(other: Any?): Boolean = other is Every<*>

        override fun hashCode(): Int = 0

        override fun toString(): String = Match.WILDCARD
    }

    data class Only<T>(val values: Set<T>) : Dimension<T> {
        override fun contains(value: T): Boolean = value in values

        override fun toString(): String = values.toString()
    }

    companion object {
        fun <T> of(match: Match<T>): Dimension<T> = when (match) {
            Match.Any -> Every()
            is Match.Exactly -> Only(setOf(match.value))
        }
    }
}

/**
 * A set of `(app, space)` pairs: every app in [apps] with every space in [spaces]. Both what a fragment grants to
 * (σ_G) and where a grantor's statement applies are such products (RFC 0007 §7.4.3).
 */
data class ScopeSet(val apps: Dimension<AppId>, val spaces: Dimension<SpaceId>) {
    val isEmpty: Boolean get() = empty(apps) || empty(spaces)

    /** Whether every pair of this set is in [other]. A dimension of every value is covered only by every value. */
    fun coveredBy(other: ScopeSet): Boolean = isEmpty || (covered(apps, other.apps) && covered(spaces, other.spaces))

    fun intersects(other: ScopeSet): Boolean = !intersect(other).isEmpty

    fun intersect(other: ScopeSet): ScopeSet = ScopeSet(intersect(apps, other.apps), intersect(spaces, other.spaces))

    override fun toString(): String = "($apps, $spaces)"

    companion object {
        val EVERY: ScopeSet = ScopeSet(Dimension.Every(), Dimension.Every())

        fun of(app: Match<AppId>, space: Match<SpaceId>): ScopeSet = ScopeSet(Dimension.of(app), Dimension.of(space))

        private fun <T> empty(dimension: Dimension<T>): Boolean = dimension is Dimension.Only && dimension.values.isEmpty()

        private fun <T> covered(inner: Dimension<T>, outer: Dimension<T>): Boolean = when (outer) {
            is Dimension.Every -> true
            is Dimension.Only -> inner is Dimension.Only && outer.values.containsAll(inner.values)
        }

        fun <T> intersect(a: Dimension<T>, b: Dimension<T>): Dimension<T> = when {
            a is Dimension.Every -> b
            b is Dimension.Every -> a
            else -> Dimension.Only((a as Dimension.Only).values intersect (b as Dimension.Only).values)
        }
    }
}
