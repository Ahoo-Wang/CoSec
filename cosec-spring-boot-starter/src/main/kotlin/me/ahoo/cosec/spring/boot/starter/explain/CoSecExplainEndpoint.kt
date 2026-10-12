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
package me.ahoo.cosec.spring.boot.starter.explain

import me.ahoo.cosec.explain.AuthorizationExplainer
import me.ahoo.cosec.explain.ExplainDetail
import me.ahoo.cosec.explain.ExplainOptions
import me.ahoo.cosec.explain.ExplainPrincipal
import me.ahoo.cosec.explain.ExplainRequest
import me.ahoo.cosec.explain.Explanation
import me.ahoo.cosec.serialization.CoSecJsonSerializer
import org.springframework.boot.actuate.endpoint.annotation.Endpoint
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation

/**
 * `POST /actuator/cosecExplain`: explains the authorization decision for a request, either for a described
 * `principal` or, without one, for the token or API key in the request's headers.
 *
 * The call itself goes through CoSec authorization like any other request, so grant it only to administrators.
 */
@Endpoint(id = "cosecExplain")
class CoSecExplainEndpoint(private val explainer: AuthorizationExplainer) {

    @WriteOperation
    fun explain(
        principal: Map<String, Any>?,
        request: Map<String, Any>,
        exhaustive: Boolean?,
        detail: String?
    ): Explanation? {
        val options = ExplainOptions(
            exhaustive = exhaustive ?: false,
            detail = detail?.let { ExplainDetail.valueOf(it.uppercase()) } ?: ExplainDetail.SUMMARY,
        )
        val explainRequest = CoSecJsonSerializer.convertValue(request, ExplainRequest::class.java).toRequest()
        // Without a described principal, the request's own token or API key decides who is explained.
        val explanation = principal?.let {
            explainer.explain(explainRequest, CoSecJsonSerializer.convertValue(it, ExplainPrincipal::class.java).toPrincipal(), options)
        } ?: explainer.explain(explainRequest, options)
        return explanation.block()
    }
}
