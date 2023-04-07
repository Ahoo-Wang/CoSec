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

package me.ahoo.cosec.spring.boot.starter.policy

import me.ahoo.cosec.policy.action.ActionMatcherFactory
import me.ahoo.cosec.policy.action.ActionMatcherFactoryProvider
import me.ahoo.cosec.policy.condition.ConditionMatcherFactory
import me.ahoo.cosec.policy.condition.ConditionMatcherFactoryProvider
import org.springframework.context.ApplicationContext
import org.springframework.context.SmartLifecycle

class MatcherFactoryRegister(private val applicationContext: ApplicationContext) : SmartLifecycle {
    @Volatile
    private var running = false
    override fun start() {
        running = true
        applicationContext.getBeansOfType(ConditionMatcherFactory::class.java).values.forEach {
            ConditionMatcherFactoryProvider.register(it)
        }
        applicationContext.getBeansOfType(ActionMatcherFactory::class.java).values.forEach {
            ActionMatcherFactoryProvider.register(it)
        }
    }

    override fun stop() {
        running = false
    }

    override fun isRunning(): Boolean {
        return running
    }
}
