package me.ahoo.cosec.policy

import me.ahoo.cosec.Named
import me.ahoo.cosec.tenant.Tenant

/**
 * Permission Policy
 */
interface Policy : Named, Tenant {
    val id: String
    val category: String
    val description: String
    val type: PolicyType
    val statements: Set<Statement>
}

data class PolicyData(
    override val id: String,
    override val category: String,
    override val name: String,
    override val description: String,
    override val type: PolicyType,
    override val tenantId: String,
    override val statements: Set<Statement> = emptySet()
) : Policy
