package io.github.sepfins.limitguard.admin.security

import org.springframework.security.access.AccessDeniedException
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class AdminAuthorizer {
    fun requireTenantRead(identity: AdminIdentity, tenantId: UUID) {
        requireReader(identity)
        if (!identity.isPlatformAdmin && tenantId !in identity.tenantIds) {
            throw AccessDeniedException("Tenant access is not allowed")
        }
    }

    fun requireTenantMutation(identity: AdminIdentity, tenantId: UUID) {
        val allowed = identity.isPlatformAdmin ||
            (AdminRole.TENANT_ADMIN in identity.roles && tenantId in identity.tenantIds)
        if (!allowed) throw AccessDeniedException("Tenant administration is not allowed")
    }

    fun requireTenantCreation(identity: AdminIdentity) {
        if (!identity.isPlatformAdmin) {
            throw AccessDeniedException("Platform administration is required")
        }
    }

    fun requireReader(identity: AdminIdentity) {
        if (identity.roles.intersect(AdminRole.entries.toSet()).isEmpty()) {
            throw AccessDeniedException("LimitGuard Admin access is not allowed")
        }
    }
}
