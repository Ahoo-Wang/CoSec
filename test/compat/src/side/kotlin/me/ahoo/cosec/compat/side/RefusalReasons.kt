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

package me.ahoo.cosec.compat.side

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.document.json.StoredFormat

/**
 * Why this version refused a stored record (`SNP-27`): `later` for one a later version wrote (a later format, or a member
 * its `crit` names that this version does not know), `malformed` for one that is not a record of its kind. One copy for
 * both sides: 8.0.0-beta.9, the previous release, tells the two apart too.
 */
internal object RefusalReasons {
    fun of(error: Throwable): String = if (StoredFormat.later(error) != null) "later" else "malformed"
}
