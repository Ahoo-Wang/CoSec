package me.ahoo.cosec.policy

import me.ahoo.cosec.context.SecurityContext
import me.ahoo.cosec.context.request.Request

interface PermissionVerifier {
    fun verify(request: Request, securityContext: SecurityContext): VerifyResult
}

enum class VerifyResult {
    ALLOW,
    EXPLICIT_DENY,
    IMPLICIT_DENY
}
