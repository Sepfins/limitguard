package io.github.sepfins.limitguard.admin.security

import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.Authentication
import org.springframework.security.oauth2.core.oidc.user.OidcUser
import org.springframework.stereotype.Component
import java.util.UUID

enum class AdminRole {
    PLATFORM_ADMIN,
    TENANT_ADMIN,
    VIEWER,
}

data class AdminIdentity(
    val subject: String,
    val displayName: String,
    val email: String?,
    val roles: Set<AdminRole>,
    val tenantIds: Set<UUID>,
) {
    val isPlatformAdmin: Boolean
        get() = AdminRole.PLATFORM_ADMIN in roles
}

@Component
class AdminIdentityResolver(
    private val properties: AdminSecurityProperties,
) {
    fun resolve(authentication: Authentication): AdminIdentity {
        val user = authentication.principal as? OidcUser
            ?: throw AccessDeniedException("An OpenID Connect identity is required")
        val roleNames = authentication.authorities
            .mapNotNull { authority -> authority.authority?.removePrefix(ROLE_PREFIX) }
            .plus(realmRoles(user.claims[REALM_ACCESS_CLAIM]))
        val roles = roleNames
            .mapNotNull { name -> AdminRole.entries.find { it.name == name } }
            .toSet()
        val tenantIds = claimValues(user.claims[properties.tenantClaim])
            .mapNotNull { value -> runCatching { UUID.fromString(value) }.getOrNull() }
            .toSet()

        return AdminIdentity(
            subject = user.subject,
            displayName = user.fullName ?: user.preferredUsername ?: user.subject,
            email = user.email,
            roles = roles,
            tenantIds = tenantIds,
        )
    }

    private fun claimValues(value: Any?): List<String> = when (value) {
        is Collection<*> -> value.mapNotNull { it?.toString() }
        is Array<*> -> value.mapNotNull { it?.toString() }
        is String -> value.split(',').map(String::trim).filter(String::isNotEmpty)
        else -> emptyList()
    }

    private fun realmRoles(claim: Any?): List<String> {
        val realmAccess = claim as? Map<*, *> ?: return emptyList()
        return claimValues(realmAccess[ROLES_CLAIM])
    }

    private companion object {
        const val ROLE_PREFIX = "ROLE_"
        const val REALM_ACCESS_CLAIM = "realm_access"
        const val ROLES_CLAIM = "roles"
    }
}
