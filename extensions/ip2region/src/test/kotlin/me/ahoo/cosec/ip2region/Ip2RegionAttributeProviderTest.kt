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

package me.ahoo.cosec.ip2region

import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.model.Action
import me.ahoo.cosec.model.Resource
import me.ahoo.cosec.model.Subject
import me.ahoo.cosec.model.condition.AttributeType
import me.ahoo.cosec.runtime.attribute.AttributeProviders
import me.ahoo.cosec.runtime.attribute.AttributeRequest
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

class Ip2RegionAttributeProviderTest {
    private val provider = Ip2RegionAttributeProvider()
    private val all = setOf(
        Ip2RegionAttributeProvider.COUNTRY,
        Ip2RegionAttributeProvider.PROVINCE,
        Ip2RegionAttributeProvider.CITY,
        Ip2RegionAttributeProvider.ISP,
    )

    private fun request(remoteIp: String) = AttributeRequest(
        Subject("anonymous", "", false, emptySet(), emptySet(), emptyMap(), false),
        null,
        Action("http", "GET"),
        Resource("http", "/", emptyMap()),
        remoteIp,
        "r-1",
        "",
    )

    private fun fetch(remoteIp: String, names: Set<String> = all) =
        runBlocking { provider.fetch(request(remoteIp), names) }

    @Test
    fun declaresFourStringAttributes() {
        val catalog = AttributeProviders(listOf(provider)).catalog
        all.forEach { catalog.external(it)!!.type.assert().isEqualTo(AttributeType.STRING) }
    }

    @Test
    fun answersTheFieldsOfAKnownAddress() {
        fetch("101.228.87.88").assert().isEqualTo(
            mapOf(
                Ip2RegionAttributeProvider.COUNTRY to "中国",
                Ip2RegionAttributeProvider.PROVINCE to "上海",
                Ip2RegionAttributeProvider.CITY to "上海市",
                Ip2RegionAttributeProvider.ISP to "电信",
            )
        )
    }

    @Test
    fun answersOnlyWhatItIsAskedFor() {
        fetch("101.228.87.88", setOf(Ip2RegionAttributeProvider.CITY))
            .assert().isEqualTo(mapOf(Ip2RegionAttributeProvider.CITY to "上海市"))
    }

    @Test
    fun unknownFieldsAndNonAddressesAreAbsent() {
        fetch("10.0.0.1").keys.assert().doesNotContain(Ip2RegionAttributeProvider.COUNTRY)
        fetch("localhost").assert().isEmpty()
        fetch("2001:db8::1").assert().isEmpty()
    }
}
