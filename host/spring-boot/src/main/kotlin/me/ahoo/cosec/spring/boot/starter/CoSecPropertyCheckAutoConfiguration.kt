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

package me.ahoo.cosec.spring.boot.starter

import org.springframework.beans.factory.InitializingBean
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.core.env.ConfigurableEnvironment

/** Fails startup on a `cosec.*` property CoSec does not read ([UnknownProperties]), whatever else is configured. */
@AutoConfiguration
class CoSecPropertyCheckAutoConfiguration {
    @Bean
    fun cosecUnknownPropertiesCheck(environment: ConfigurableEnvironment): InitializingBean = InitializingBean {
        val mode = environment.getProperty(UnknownProperties.MODE_KEY, "fail")
        UnknownProperties.load(javaClass.classLoader).check(environment, warn = mode.equals("warn", ignoreCase = true))
    }
}
