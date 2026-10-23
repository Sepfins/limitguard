package io.github.sepfins.limitguard.admin.support

import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin
import java.util.UUID

const val DEFAULT_TENANT_CLAIM = "limitguard_tenant_ids"

fun platformAdmin() = oidcLogin()
    .idToken { token ->
        token.subject("admin-user")
        token.claim("name", "Platform Admin")
        token.claim("realm_access", mapOf("roles" to listOf("PLATFORM_ADMIN")))
    }

fun tenantAdmin(tenantId: UUID, tenantClaim: String = DEFAULT_TENANT_CLAIM) = oidcLogin()
    .idToken { token ->
        token.subject("admin-user")
        token.claim("name", "Tenant Admin")
        token.claim(tenantClaim, listOf(tenantId.toString()))
        token.claim("realm_access", mapOf("roles" to listOf("TENANT_ADMIN")))
    }

fun viewer(tenantId: UUID, tenantClaim: String = DEFAULT_TENANT_CLAIM) = oidcLogin()
    .idToken { token ->
        token.subject("viewer-user")
        token.claim(tenantClaim, listOf(tenantId.toString()))
        token.claim("realm_access", mapOf("roles" to listOf("VIEWER")))
    }
