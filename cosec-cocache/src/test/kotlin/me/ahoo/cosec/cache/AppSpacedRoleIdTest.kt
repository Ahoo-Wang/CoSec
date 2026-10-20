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

package me.ahoo.cosec.cache

import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import org.junit.jupiter.api.Test

class AppSpacedRoleIdTest {

    @Test
    fun toStringIsTheKeySuffix() {
        AppSpacedRoleId("blog", "editor").toString().assert().isEqualTo("blog:editor")
        AppSpacedRoleId("blog", "editor", "team-a").toString().assert().isEqualTo("blog:editor@team-a")
        // The first ':' ends the app id and the first '@' ends the role id, so these stay unambiguous.
        AppSpacedRoleId("blog", "posts:editor", "a:b@c").toString().assert().isEqualTo("blog:posts:editor@a:b@c")
    }

    @Test
    fun rejectsIdsThatWouldMakeTheKeyAmbiguous() {
        assertThrownBy<IllegalArgumentException> { AppSpacedRoleId("blog:v2", "editor") }
        assertThrownBy<IllegalArgumentException> { AppSpacedRoleId("", "editor") }
        assertThrownBy<IllegalArgumentException> { AppSpacedRoleId("blog", "editor@team-a") }
        assertThrownBy<IllegalArgumentException> { AppSpacedRoleId("blog", "") }
    }
}
