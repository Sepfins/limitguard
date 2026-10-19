package io.github.sepfins.limitguard.domain.limit

import io.github.sepfins.limitguard.domain.common.LimitConfigurationId
import io.github.sepfins.limitguard.domain.common.LimitRuleId
import io.github.sepfins.limitguard.domain.common.Metadata
import io.github.sepfins.limitguard.domain.common.PartyProfileId
import io.github.sepfins.limitguard.domain.common.TenantId
import io.github.sepfins.limitguard.domain.common.Version
import io.github.sepfins.limitguard.domain.mandate.MandateScope
import java.time.Instant

/**
 * An owner's amount for one configuration, optionally narrowed to one mandate. Kind, period,
 * currency, scopes, usage mode, and priority come from the configuration.
 */
data class LimitRule(
    val id: LimitRuleId,
    val tenantId: TenantId,
    val ownerPartyProfileId: PartyProfileId,
    val configurationId: LimitConfigurationId,
    val mandateScope: MandateScope,
    val amount: LimitAmount,
    val isActive: Boolean,
    val version: Version,
    val metadata: Metadata = Metadata.EMPTY,
    val createdAt: Instant? = null,
    val updatedAt: Instant? = null
)
