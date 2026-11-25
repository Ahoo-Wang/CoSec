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
package me.ahoo.cosec.compat.side

/**
 * The previous release's copy of [RefusalReasons] (the current one is in src/current/kotlin): a release before `crit`
 * (`SNP-27`), which refuses a record it cannot read without telling one a later version wrote from a broken one.
 */
internal object RefusalReasons {
    @Suppress("UNUSED_PARAMETER")
    fun of(error: Throwable): String = "malformed"
}
