package me.ahoo.cosec.policy

import me.ahoo.cosec.context.SecurityContext
import me.ahoo.cosec.context.request.Request

interface RequestMatcher {
    val type: String
    val pattern: String
    fun match(request: Request, securityContext: SecurityContext): Boolean
}
