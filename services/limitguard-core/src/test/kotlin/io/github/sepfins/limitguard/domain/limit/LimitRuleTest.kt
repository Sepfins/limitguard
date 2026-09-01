package io.github.sepfins.limitguard.domain.limit

import io.github.sepfins.limitguard.domain.common.ChannelId
import io.github.sepfins.limitguard.domain.common.CurrencyCode
import io.github.sepfins.limitguard.domain.common.EffectiveWindow
import io.github.sepfins.limitguard.domain.common.LimitConfigurationId
import io.github.sepfins.limitguard.domain.common.LimitRuleId
import io.github.sepfins.limitguard.domain.common.Money
import io.github.sepfins.limitguard.domain.common.OperationTypeId
import io.github.sepfins.limitguard.domain.common.PartyProfileId
import io.github.sepfins.limitguard.domain.common.TenantId
import io.github.sepfins.limitguard.domain.common.Version
import io.github.sepfins.limitguard.domain.mandate.MandateScope
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LimitRuleTest {
    private val tenantId = TenantId.of(UUID.fromString("00000000-0000-0000-0000-000000000001"))
    private val ruleId = LimitRuleId.of(UUID.fromString("00000000-0000-0000-0000-000000000002"))
    private val ownerId = PartyProfileId.of(UUID.fromString("00000000-0000-0000-0000-000000000003"))
    private val configurationId = LimitConfigurationId.of(UUID.fromString("00000000-0000-0000-0000-000000000004"))
    private val parentConfigurationId = LimitConfigurationId.of(UUID.fromString("00000000-0000-0000-0000-000000000005"))
    private val channelId = ChannelId.of(UUID.fromString("00000000-0000-0000-0000-000000000006"))
    private val operationTypeId = OperationTypeId.of(UUID.fromString("00000000-0000-0000-0000-000000000007"))
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
    fun `rule channel scope must match configuration scope`() {
        val configuration = limitConfiguration(
            maxAmount = "100",
            channelScope = LimitChannelScope.ChannelSpecific(channelId),
        )

        assertFailsWith<IllegalArgumentException> {
            limitRule(amount = "50").validateAgainst(configuration)
        }
    }

    @Test
    fun `rule operation scope must match configuration scope`() {
        val configuration = limitConfiguration(
            maxAmount = "100",
            operationScope = LimitOperationScope.OperationTypeSpecific(operationTypeId),
        )

        assertFailsWith<IllegalArgumentException> {
            limitRule(amount = "50").validateAgainst(configuration)
        }
    }

    @Test
    fun `rule priority must match configuration priority`() {
        val configuration = limitConfiguration(
            maxAmount = "100",
            priority = LimitPriority.of(10),
        )

        assertFailsWith<IllegalArgumentException> {
            limitRule(amount = "50").validateAgainst(configuration)
        }
    }

    @Test
    fun `rule effective window must be contained by configuration window`() {
        val configuration = limitConfiguration(
            maxAmount = "100",
            effectiveWindow = EffectiveWindow(
                Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-02-01T00:00:00Z"),
            ),
        )

        assertFailsWith<IllegalArgumentException> {
            limitRule(
                amount = "50",
                effectiveWindow = EffectiveWindow(
                    Instant.parse("2026-01-02T00:00:00Z"),
                    Instant.parse("2026-02-02T00:00:00Z"),
                ),
            ).validateAgainst(configuration)
        }
    }

    @Test
    fun `inactive configuration cannot govern a rule`() {
        val configuration = limitConfiguration(maxAmount = "100", isActive = false)

        assertFailsWith<IllegalArgumentException> {
            limitRule(amount = "50").validateAgainst(configuration)
        }
    }

    @Test
    fun `rule is checked against every ancestor cap`() {
        val configuration = limitConfiguration(
            maxAmount = "1000",
            parentConfigurationId = parentConfigurationId,
        )
        val parent = limitConfiguration(
            id = parentConfigurationId,
            maxAmount = "500",
        )

        assertFailsWith<IllegalArgumentException> {
            limitRule(amount = "600").validateAgainst(configuration, listOf(parent))
        }
    }

    @Test
    fun `parented configuration requires complete ancestry for validation`() {
        val configuration = limitConfiguration(
            maxAmount = "100",
            parentConfigurationId = parentConfigurationId,
        )

        assertFailsWith<IllegalArgumentException> {
            limitRule(amount = "50").validateAgainst(configuration)
        }
    }

    @Test
    fun `matching rule validates against a complete configuration hierarchy`() {
        val configuration = limitConfiguration(
            maxAmount = "500",
            parentConfigurationId = parentConfigurationId,
        )
        val parent = limitConfiguration(
            id = parentConfigurationId,
            maxAmount = "1000",
        )
        val rule = limitRule(amount = "400")

        assertEquals(rule, rule.validateAgainst(configuration, listOf(parent)))
    }

    @Test
    fun `rule amount must be positive`() {
        assertFailsWith<IllegalArgumentException> {
            limitRule(amount = "0")
        }
    }

    @Test
    fun `isolated usage mode is invalid for per-transaction rule`() {
        assertFailsWith<IllegalArgumentException> {
            limitRule(
                amount = "100",
                limitKind = LimitKind.PerTransaction,
                usageConsumptionMode = LimitUsageConsumptionMode.ISOLATED,
            )
        }
    }

    private fun limitConfiguration(
        maxAmount: String,
        id: LimitConfigurationId = configurationId,
        parentConfigurationId: LimitConfigurationId? = null,
        channelScope: LimitChannelScope = LimitChannelScope.AllChannels,
        operationScope: LimitOperationScope = LimitOperationScope.AllOperations,
        priority: LimitPriority = LimitPriority.of(100),
        isActive: Boolean = true,
        effectiveWindow: EffectiveWindow = window,
    ): LimitConfiguration =
        LimitConfiguration(
            id = id,
            tenantId = tenantId,
            parentConfigurationId = parentConfigurationId,
            code = LimitConfigurationCode.of("ATM_DAILY"),
            name = LimitConfigurationName.of("ATM daily"),
            description = null,
            limitKind = LimitKind.Periodic(LimitPeriod.DAILY),
            fundingScope = LimitConfigurationFundingScope.AllFundingSources,
            channelScope = channelScope,
            operationScope = operationScope,
            maxAssignableAmount = Money.positive(maxAmount, eur),
            defaultAmount = null,
            priority = priority,
            isActive = isActive,
            effectiveWindow = effectiveWindow,
        )

    private fun limitRule(
        amount: String,
        limitKind: LimitKind = LimitKind.Periodic(LimitPeriod.DAILY),
        usageConsumptionMode: LimitUsageConsumptionMode = LimitUsageConsumptionMode.CASCADING,
        effectiveWindow: EffectiveWindow = window,
    ): LimitRule =
        LimitRule(
            id = ruleId,
            tenantId = tenantId,
            ownerPartyProfileId = ownerId,
            name = LimitRuleName.of("Child ATM daily"),
            description = null,
            limitKind = limitKind,
            amount = Money.of(amount, eur),
            mandateScope = MandateScope.AllOwnerMandates,
            configurationId = configurationId,
            channelScope = LimitChannelScope.AllChannels,
            operationScope = LimitOperationScope.AllOperations,
            usageConsumptionMode = usageConsumptionMode,
            priority = LimitPriority.of(100),
            isActive = true,
            version = Version.INITIAL,
            effectiveWindow = effectiveWindow,
        )
}
