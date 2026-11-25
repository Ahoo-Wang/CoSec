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

package me.ahoo.cosec.compat

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * RFC 0014 T: the previous side runs the published release, the current side this build, and neither is on the test's
 * own classpath, so no class of one version answers for the other.
 */
class SideIsolationTest {
    private val classes = listOf(
        "me.ahoo.cosec.document.identity.IdentityHeader",
        "me.ahoo.cosec.cocache.CoCacheTokenFamilyStore",
        "me.ahoo.cosec.jwt.JwtTokenVerifier",
        "me.ahoo.cosec.control.change.ChangeRequestCodec",
    )

    @Test
    fun `the previous side runs the published release and the current side this build`() {
        for (name in classes) {
            Side.PREVIOUS("where", "class" to name).assert().describedAs(name)
                .contains("/modules-2/").contains("-${Baseline.previous}.jar")
            Side.CURRENT("where", "class" to name).assert().describedAs(name).doesNotContain("/modules-2/")
        }
    }

    @Test
    fun `neither version is visible to the test's own class loader`() {
        for (name in classes) {
            assertThrows<ClassNotFoundException>(name) { Class.forName(name, false, javaClass.classLoader) }
        }
    }
}
