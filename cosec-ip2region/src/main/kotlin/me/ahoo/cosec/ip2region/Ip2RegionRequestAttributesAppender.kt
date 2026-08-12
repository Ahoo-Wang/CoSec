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

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.cosec.api.context.request.Request
import me.ahoo.cosec.context.request.RequestAttributesAppender
import org.lionsoul.ip2region.xdb.Searcher
import org.lionsoul.ip2region.xdb.Version
import java.io.File

const val REQUEST_ATTRIBUTES_IP_REGION_KEY = "ipRegion"

class Ip2RegionRequestAttributesAppender(ip2regionFile: File? = null, version: Version = Version.IPv4) :
    RequestAttributesAppender {
    companion object {
        private val log = KotlinLogging.logger {}
    }

    private val searcher: Searcher = run {
        val dbBuffer = if (ip2regionFile == null) {
            requireNotNull(
                Ip2RegionRequestAttributesAppender::class.java.classLoader.getResourceAsStream("ip2region.xdb")
            ) { "Classpath resource [ip2region.xdb] was not found." }.use {
                Searcher.loadContentFromInputStream(it)
            }
        } else {
            Searcher.loadContentFromFile(ip2regionFile)
        }
        Searcher.newWithBuffer(version, dbBuffer)
    }

    override fun append(request: Request): Request {
        val region = try {
            val searchedRegion = searcher.search(request.remoteIp)
            log.trace {
                "remoteIp:[${request.remoteIp}],searchedRegion:[$searchedRegion]"
            }
            searchedRegion
        } catch (e: Exception) {
            log.warn(e) {
                "search ip2region failed! remoteIp:[${request.remoteIp}]"
            }
            return request
        }
        return request.mergeAttributes(mapOf(REQUEST_ATTRIBUTES_IP_REGION_KEY to region))
    }
}
