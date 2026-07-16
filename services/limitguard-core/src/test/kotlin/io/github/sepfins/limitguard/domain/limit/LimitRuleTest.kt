package io.github.sepfins.limitguard.domain.limit

import io.github.sepfins.limitguard.domain.common.CurrencyCode
import io.github.sepfins.limitguard.domain.common.EffectiveWindow
import io.github.sepfins.limitguard.domain.common.LimitConfigurationId
import io.github.sepfins.limitguard.domain.common.LimitRuleId
import io.github.sepfins.limitguard.domain.common.Money
import io.github.sepfins.limitguard.domain.common.PartyProfileId
import io.github.sepfins.limitguard.domain.common.TenantId
import io.github.sepfins.limitguard.domain.common.Version
import io.github.sepfins.limitguard.domain.mandate.MandateScope
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertFailsWith
import java.time.Instant

class LimitRuleTest {
    private val tenantId = TenantId.of(UUID.fromString("00000000-0000-0000-0000-000000000001"))
    private val ruleId = LimitRuleId.of(UUID.fromString("00000000-0000-0000-0000-000000000002"))
    private val ownerId = PartyProfileId.of(UUID.fromString("00000000-0000-0000-0000-000000000003"))
    private val configurationId = LimitConfigurationId.of(UUID.fromString("00000000-0000-0000-0000-000000000004"))
    private val eur = CurrencyCode.of("EUR")
    private val window = EffectiveWindow(Instant.parse("2026-01-01T00:00:00Z"), null)

    @Test
    fun `rule amount cannot exceed configuration cap`() {
        val configuration = limitConfiguration(maxAmount = "100")
        val rule = limitRule(amount = "101")

        assertFailsWith<IllegalArgumentException> {
            rule.validateAgainst(configuration)
        }
    }

    @Test
    fun `rule amount must be positive`() {
        assertFailsWith<IllegalArgumentException> {
            limitRule(amount = "0")
        }
    }

    private fun limitConfiguration(maxAmount: String): LimitConfiguration =
        LimitConfiguration(
            id = configurationId,
            tenantId = tenantId,
            parentConfigurationId = null,
            code = LimitConfigurationCode.of("ATM_DAILY"),
            name = LimitConfigurationName.of("ATM daily"),
            description = null,
            limitKind = LimitKind.Periodic(LimitPeriod.DAILY),
            fundingScope = LimitConfigurationFundingScope.AllFundingSources,
            channelScope = LimitChannelScope.AllChannels,
            operationScope = LimitOperationScope.AllOperations,
            maxAssignableAmount = Money.positive(maxAmount, eur),
            defaultAmount = null,
            priority = LimitPriority.of(100),
            isActive = true,
            effectiveWindow = window,
        )

    private fun limitRule(amount: String): LimitRule =
        LimitRule(
            id = ruleId,
            tenantId = tenantId,
            ownerPartyProfileId = ownerId,
            name = LimitRuleName.of("Child ATM daily"),
            description = null,
            limitKind = LimitKind.Periodic(LimitPeriod.DAILY),
            amount = Money.of(amount, eur),
            mandateScope = MandateScope.AllOwnerMandates,
            configurationId = configurationId,
            channelScope = LimitChannelScope.AllChannels,
            operationScope = LimitOperationScope.AllOperations,
            usageConsumptionMode = LimitUsageConsumptionMode.CASCADING,
            priority = LimitPriority.of(100),
            isActive = true,
            version = Version.INITIAL,
            effectiveWindow = window,
        )
}
