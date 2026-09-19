package io.github.sepfins.limitguard.admin.web

import io.github.sepfins.limitguard.admin.security.AdminIdentityResolver
import io.github.sepfins.limitguard.admin.web.dto.MeResponse
import org.springframework.security.core.Authentication
import org.springframework.security.web.csrf.CsrfToken
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/limitguard/admin/api/v1")
class AdminSessionController(
    private val identityResolver: AdminIdentityResolver,
) {
    @GetMapping("/me")
    fun me(authentication: Authentication, csrfToken: CsrfToken): MeResponse {
        val identity = identityResolver.resolve(authentication)
        return MeResponse(
            subject = identity.subject,
            displayName = identity.displayName,
            email = identity.email,
            roles = identity.roles.map { it.name }.toSortedSet(),
            tenantIds = identity.tenantIds,
            logoutUrl = "/limitguard/admin/logout",
            csrfParameterName = csrfToken.parameterName,
            csrfToken = csrfToken.token,
        )
    }
}
