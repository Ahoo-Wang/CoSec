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

package me.ahoo.cosec.spring.boot.control

import org.springframework.boot.autoconfigure.condition.ConditionOutcome
import org.springframework.boot.autoconfigure.condition.SpringBootCondition
import org.springframework.context.annotation.ConditionContext
import org.springframework.core.type.AnnotatedTypeMetadata
import org.springframework.util.ClassUtils

/**
 * Whether this process runs the control plane: `cosec-control` and `cosec-cocache` on the classpath. The one condition
 * [CoSecControlAutoConfiguration] is wired on and the snapshot and candidate stores are declared read-write by
 * (SNP-18): a process without the control plane reads them read-only, so they cannot drift apart. No property switches
 * the control plane off on its own (only `cosec.enabled`, which switches every store off too).
 */
internal class ControlPlaneCondition : SpringBootCondition() {
    override fun getMatchOutcome(context: ConditionContext, metadata: AnnotatedTypeMetadata): ConditionOutcome {
        val missing = CLASSES.filterNot { ClassUtils.isPresent(it, context.classLoader) }
        return if (missing.isEmpty()) {
            ConditionOutcome.match("the control plane's classes are on the classpath")
        } else {
            ConditionOutcome.noMatch("the control plane is not on the classpath: $missing")
        }
    }

    companion object {
        private val CLASSES =
            listOf("me.ahoo.cosec.control.api.ControlHttp", "me.ahoo.cosec.cocache.control.CoCacheControlStore")

        /** Whether the control plane runs in the process whose classes [classLoader] loads. */
        fun wired(classLoader: ClassLoader?): Boolean = CLASSES.all { ClassUtils.isPresent(it, classLoader) }
    }
}
