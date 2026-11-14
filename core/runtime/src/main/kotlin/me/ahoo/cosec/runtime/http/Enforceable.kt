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

package me.ahoo.cosec.runtime.http

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.engine.pattern.ResourcePattern
import me.ahoo.cosec.model.Action
import me.ahoo.cosec.model.Resource

/**
 * What enforcement decides an action and a resource as, for what is decided without a request: a check by host code
 * (`AuthorizationChecker`, `PIP-15`) and the control plane's simulate, explain and analyze (`CTL-3`). One judgement, so
 * that neither decides what no request could be decided on, nor decides it otherwise than a request would be.
 */
@InternalCoSecApi
object Enforceable {
    /**
     * [resource] as enforcement reads it, or null when enforcement would refuse it. An `http` path goes through
     * [PathNormalizer.normalize], since the engine does not resolve a dot segment and a wildcard under `/public` would
     * match `/public/../admin`; a typed id, which no request carries as written, is held to [PathNormalizer.withinBounds],
     * since its globs would otherwise cost more than any request's.
     */
    fun resource(resource: Resource): Resource? {
        val id = if (resource.type == ResourcePattern.HTTP_TYPE) {
            PathNormalizer.normalize(resource.id)
        } else {
            resource.id.takeIf(PathNormalizer::withinBounds)
        }
        return when (id) {
            null -> null
            resource.id -> resource
            else -> Resource(resource.type, id, resource.attributes)
        }
    }

    /**
     * [action] as enforcement reads a request's method (`PIP-18`), or null when enforcement would refuse it. An `http`
     * action whose verb holds a lower-case letter is refused, as a request with that method is (400); `http:HEAD` is
     * `http:GET`, as a HEAD request is decided. Any other action, `service:verb` included, as it is.
     */
    fun action(action: Action): Action? = when {
        action.namespace != ResourcePattern.HTTP_TYPE -> action
        HttpMethods.hasLowerCase(action.verb) -> null
        action.verb == HttpMethods.HEAD -> Action(action.namespace, HttpMethods.decidedAs(action.verb))
        else -> action
    }
}
