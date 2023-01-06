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

import me.ahoo.cosec.api.context.request.Request
import me.ahoo.cosec.context.request.RequestAttributesAppender
import org.lionsoul.ip2region.xdb.Searcher
import org.slf4j.LoggerFactory
import java.io.File

const val REQUEST_ATTRIBUTES_IP_REGION_KEY = "ipRegion"

class Ip2RegionRequestAttributesAppender(ip2regionFile: File = LOCAL_IP2REGION_FILE) : RequestAttributesAppender {
    companion object {
        private val log = LoggerFactory.getLogger(Ip2RegionRequestAttributesAppender::class.java)
        private val LOCAL_IP2REGION_FILE: File = Ip2RegionRequestAttributesAppender::class.java
            .classLoader.getResource("ip2region.xdb").let {
                File(it.file)
            }
    }

    private val searcher: Searcher by lazy {
        val dbBuffer = Searcher.loadContentFromFile(ip2regionFile.path)
        Searcher.newWithBuffer(dbBuffer)
    }

    override fun append(request: Request): Request {
        val region = try {
            val searchedRegion = searcher.search(request.remoteIp)
            if (log.isTraceEnabled) {
                log.trace(
                    "remoteIp:[{}],searchedRegion:[{}]",
                    request.remoteIp,
                    searchedRegion
                )
            }
            searchedRegion
        } catch (e: Exception) {
            if (log.isDebugEnabled) {
                log.debug("search ip2region failed!", e)
            }
            return request
        }
        return request.mergeAttributes(mapOf(REQUEST_ATTRIBUTES_IP_REGION_KEY to region))
    }
}
