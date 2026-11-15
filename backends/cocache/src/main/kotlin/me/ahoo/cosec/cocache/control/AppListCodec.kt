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

package me.ahoo.cosec.cocache.control

import me.ahoo.cocache.api.Codec
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.cocache.control.StoredJson.factory
import me.ahoo.cosec.cocache.control.StoredJson.field
import me.ahoo.cosec.cocache.control.StoredJson.items
import me.ahoo.cosec.cocache.control.StoredJson.malformed
import me.ahoo.cosec.cocache.control.StoredJson.only
import me.ahoo.cosec.cocache.control.StoredJson.read
import me.ahoo.cosec.cocache.control.StoredJson.text
import me.ahoo.cosec.control.store.AppList
import me.ahoo.cosec.document.json.CoSecJson

/**
 * The app list as `{"apps": [...sorted], "complete": true}`, so equal lists have equal digests. Entries are kept as
 * written, whatever they are: the grant analysis refuses to trust a list with one that is not an app id, never drops it.
 */
internal object AppListCodec : Codec<AppList> {
    private const val WHAT = "app list"

    override fun encode(value: AppList): ByteArray {
        val root = factory.objectNode()
        root.putArray("apps").apply { value.entries.sorted().forEach(::add) }
        root.put("complete", value.complete)
        return CoSecJson.mapper.writeValueAsBytes(root)
    }

    override fun decode(bytes: ByteArray): AppList {
        val root = read(bytes, WHAT)
        root.only(WHAT, "apps", "complete")
        val complete = root.field("complete", WHAT)
        if (!complete.isBoolean) malformed(WHAT, "$complete is not a boolean")
        return AppList(
            root.field("apps", WHAT).items(WHAT).mapTo(LinkedHashSet()) { it.text(WHAT) },
            complete.booleanValue()
        )
    }
}
