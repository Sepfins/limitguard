package io.github.sepfins.limitguard.admin.security

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("limitguard.admin")
data class AdminSecurityProperties(
    val tenantClaim: String = "limitguard_tenant_ids",
)
