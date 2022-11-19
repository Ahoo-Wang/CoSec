package me.ahoo.cosec.policy

import me.ahoo.cosec.context.SecurityContext
import me.ahoo.cosec.context.request.Request

interface ConditionMatcher : RequestMatcher

object AllConditionMatcher : ConditionMatcher {
    const val TYPE = "ALL"
    override val type: String
        get() = TYPE
    override val pattern: String
        get() = "*"

    override fun match(request: Request, securityContext: SecurityContext): Boolean {
        return true
    }
}

object NoneConditionMatcher : ConditionMatcher {
    const val TYPE = "NONE"
    override val type: String
        get() = TYPE
    override val pattern: String
        get() = "!"
    override fun match(request: Request, securityContext: SecurityContext): Boolean {
        return false
    }
}
