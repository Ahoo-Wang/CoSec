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

@file:OptIn(InternalCoSecApi::class, ExperimentalCoSecApi::class)

package me.ahoo.cosec.migrate

import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.migrate.MigrateMain.OK
import me.ahoo.cosec.migrate.RedisAccess.controlStore
import me.ahoo.cosec.model.PrincipalId
import java.io.PrintStream

/** `bootstrap`: the first platform administrator. */
internal object BootstrapCommand {
    fun bootstrap(options: Options, out: PrintStream): Int {
        val principal = PrincipalId(options.required("platform-admin"))
        val revision = controlStore(options) { store -> Bootstrap(store).run(principal, options.flag("force")) }
        out.println(
            "Bound ${principal.value} of the platform tenant to cosec:platform-admin: ${Bootstrap.KEY} revision $revision."
        )
        return OK
    }
}
