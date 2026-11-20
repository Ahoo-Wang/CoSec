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

package me.ahoo.cosec.engine

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

/** R2A-1: a plain class that compares by its three revisions and says them all. */
class ViewVersionTest {
    @Test
    fun comparesByEveryRevisionAndSaysThem() {
        val version = ViewVersion(3, 5, 7)
        version.assert().isEqualTo(version)
        version.assert().isEqualTo(ViewVersion(3, 5, 7))
        version.hashCode().assert().isEqualTo(ViewVersion(3, 5, 7).hashCode())
        for (differs in listOf(
            ViewVersion(4, 5, 7),
            ViewVersion(3, 6, 7),
            ViewVersion(3, null, 7),
            ViewVersion(3, 5, 8),
            ViewVersion(3, 5, null)
        )) {
            version.assert().isNotEqualTo(differs)
        }
        val nothing: Any? = null
        version.assert().isNotEqualTo(nothing)
        version.equals("3/5/7").assert().isFalse()
        version.toString().assert().isEqualTo("ViewVersion(platform=3, app=5, tenant=7)")
        ViewVersion(1, null, null).toString().assert().isEqualTo("ViewVersion(platform=1, app=null, tenant=null)")
        ViewVersion(1, null, null).assert().isEqualTo(ViewVersion(1, null, null))
    }
}
