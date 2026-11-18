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

package me.ahoo.cosec.spring.boot.control

import me.ahoo.cosec.annotations.InternalCoSecApi
import org.springframework.boot.autoconfigure.condition.ConditionOutcome
import org.springframework.boot.autoconfigure.condition.SpringBootCondition
import org.springframework.context.annotation.ConditionContext
import org.springframework.core.env.Environment
import org.springframework.core.type.AnnotatedTypeMetadata
import org.springframework.util.ClassUtils

/**
 * Whether this process runs the control plane: `cosec-control` and `cosec-cocache` on the classpath, and
 * [ENABLED_KEY] not `false`. The one condition [CoSecControlAutoConfiguration] is wired on and the snapshot and
 * candidate stores are declared read-write by (SNP-18): a process without the control plane reads them read-only, so
 * they cannot drift apart. [ENABLED_KEY] is read through the [Environment], so `COSEC_CONTROL_ENABLED`, the form a
 * fleet that ships one image for both roles sets per role, counts the same as the dotted key.
 */
internal class ControlPlaneCondition : SpringBootCondition() {
    override fun getMatchOutcome(context: ConditionContext, metadata: AnnotatedTypeMetadata): ConditionOutcome {
        val missing = CLASSES.filterNot { ClassUtils.isPresent(it, context.classLoader) }
        return when {
            missing.isNotEmpty() -> ConditionOutcome.noMatch("the control plane is not on the classpath: $missing")
            !enabled(context.environment) -> ConditionOutcome.noMatch("$ENABLED_KEY=false")
            else -> ConditionOutcome.match("the control plane is on the classpath and switched on")
        }
    }

    companion object {
        /** `cosec.control.enabled`: `false` switches the control plane off on an instance whose classpath has it. */
        const val ENABLED_KEY = "${ControlProperties.PREFIX}.enabled"

        private val CLASSES = listOf(
            "me.ahoo.cosec.control.api.ControlHttp",
            "me.ahoo.cosec.cocache.control.CoCacheControlStore",
        )

        /** Whether the control plane's classes are on the classpath [classLoader] loads from. */
        fun present(classLoader: ClassLoader?): Boolean = CLASSES.all { ClassUtils.isPresent(it, classLoader) }

        /**
         * [ENABLED_KEY] as written, or null when it is not: `true` or `false` only (case and surrounding spaces aside).
         * Anything else fails startup: it is read neither as on nor as off.
         */
        fun written(environment: Environment): Boolean? {
            val value = environment.getProperty(ENABLED_KEY) ?: return null
            return when (value.trim().lowercase()) {
                "true" -> true
                "false" -> false
                else -> throw IllegalStateException("$ENABLED_KEY must be true or false, not [$value].")
            }
        }

        /** Whether the control plane is switched on: [ENABLED_KEY] unset or `true`. */
        fun enabled(environment: Environment): Boolean = written(environment) ?: true

        /** Whether the control plane runs in this process: its classes are present, and it is switched on. */
        fun wired(classLoader: ClassLoader?, environment: Environment): Boolean =
            present(classLoader) && enabled(environment)
    }
}
