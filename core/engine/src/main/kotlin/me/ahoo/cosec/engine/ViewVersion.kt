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

/**
 * The revisions of the documents one view composes (RFC 0004 §7.2, RFC 0003 Q20): [app] and [tenant] are null when
 * the view has no such document, 0 for one that is not stored. A plain class, like the other values an application
 * reads and never makes (RFC 0010 C4): a document added to the view later adds a field, not a `copy`.
 */
class ViewVersion(val platform: Long, val app: Long?, val tenant: Long?) {
    override fun equals(other: Any?): Boolean =
        this === other || other is ViewVersion &&
            platform == other.platform &&
            app == other.app &&
            tenant == other.tenant

    override fun hashCode(): Int = 31 * (31 * platform.hashCode() + (app?.hashCode() ?: 0)) + (tenant?.hashCode() ?: 0)

    override fun toString(): String = "ViewVersion(platform=$platform, app=$app, tenant=$tenant)"
}
