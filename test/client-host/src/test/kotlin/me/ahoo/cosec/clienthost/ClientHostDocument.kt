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

package me.ahoo.cosec.clienthost

/**
 * The platform document the host serves: what an application of the client's tests needs and nothing else. Anyone may
 * reach `/hello`, the sign-in, refresh and logout routes and the host's own `/test` routes; an authenticated caller reaches
 * `/orders`, and nobody deletes one (a `DENY` that no `ALLOW` overrides).
 */
internal const val CLIENT_HOST_DOCUMENT: String =
    """{"schema":2,"revision":1,"publishedAt":"2026-11-01T00:00:00Z","policies":[""" +
        """{"id":"public","statements":[""" +
        """{"effect":"allow","action":"http:GET","resource":["http:path:/hello","http:path:/test/**"]},""" +
        """{"effect":"allow","action":"http:POST","resource":[""" +
        """"http:path:/auth/login","http:path:/auth/refresh","http:path:/auth/logout","http:path:/test/**"]}]},""" +
        """{"id":"members","statements":[{"effect":"allow","action":"http:*","resource":"http:path:/orders/**",""" +
        """"condition":{"eq":["subject.authenticated",true]}}]},""" +
        """{"id":"no-delete","statements":[""" +
        """{"effect":"deny","action":"http:DELETE","resource":"http:path:/orders/**"}]}],""" +
        """"bindings":[{"id":"public","subject":"*","app":"*","space":"*","policy":"public"},""" +
        """{"id":"members","subject":"*","app":"*","space":"*","policy":"members"},""" +
        """{"id":"no-delete","subject":"*","app":"*","space":"*","policy":"no-delete"}]}"""
