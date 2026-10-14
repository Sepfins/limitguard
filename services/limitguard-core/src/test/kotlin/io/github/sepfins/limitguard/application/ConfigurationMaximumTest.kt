package io.github.sepfins.limitguard.application

import io.github.sepfins.limitguard.api.LimitAmount
import io.github.sepfins.limitguard.api.LimitChangeActorType
import io.github.sepfins.limitguard.api.LimitKind
import io.github.sepfins.limitguard.api.LimitPeriod
import io.github.sepfins.limitguard.api.LimitRuleChangeStatus
import io.github.sepfins.limitguard.api.LimitRuleChangeType
import io.github.sepfins.limitguard.api.LimitUsageConsumptionMode
import io.github.sepfins.limitguard.api.PartyType
import io.github.sepfins.limitguard.api.TrustedChangeActorContext
import io.github.sepfins.limitguard.api.commands.ApplyDueLimitChangesCommand
import io.github.sepfins.limitguard.api.commands.ApproveLimitRuleChangeCommand
import io.github.sepfins.limitguard.api.commands.CreateLimitConfigurationCommand
import io.github.sepfins.limitguard.api.commands.LimitChangeActorCommand
import io.github.sepfins.limitguard.api.commands.LimitRuleDraft
import io.github.sepfins.limitguard.api.commands.RegisterPartyProfileCommand
import io.github.sepfins.limitguard.api.commands.RegisterTenantCommand
import io.github.sepfins.limitguard.api.commands.RequestLimitRuleChangeCommand
import io.github.sepfins.limitguard.api.commands.SetLimitConfigurationActiveCommand
import io.github.sepfins.limitguard.api.errors.ValidationException
import io.github.sepfins.limitguard.api.queries.GetLimitChangeQuery
import io.github.sepfins.limitguard.api.queries.GetLimitRuleQuery
import io.github.sepfins.limitguard.support.FixedClockProvider
import io.github.sepfins.limitguard.support.PostgresFacade
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ConfigurationMaximumTest {
    @Test
    fun `a configuration fixes usage consumption mode currency and a bounded or unlimited maximum`() {
        val fixture = Fixture()
        val unlimited = fixture.configuration("MONTHLY", LimitAmount.Unlimited, LimitUsageConsumptionMode.ISOLATED)

        assertEquals(LimitAmount.Unlimited, unlimited.maxAssignableAmount)
        assertEquals("EUR", unlimited.currency)
        assertEquals(LimitUsageConsumptionMode.ISOLATED, unlimited.usageConsumptionMode)
        assertEquals(
            LimitAmount.Bounded(BigDecimal("1000.0000")),
            fixture.configuration("BOUNDED", LimitAmount.Bounded(BigDecimal("1000"))).maxAssignableAmount,
        )
        assertFailsWith<ValidationException> {
            fixture.facade.tenantCatalogService.createLimitConfiguration(fixture.command("ISOLATED_SINGLE").copy(
                limitKind = LimitKind.PER_TRANSACTION, period = null, usageConsumptionMode = LimitUsageConsumptionMode.ISOLATED,
            ))
        }
    }

    @Test
    fun `a rule amount must fit the configuration maximum and unlimited needs an unlimited maximum`() {
        val fixture = Fixture()
        val bounded = fixture.configuration("BOUNDED", LimitAmount.Bounded(BigDecimal("1000"))).id
        val unlimited = fixture.configuration("UNLIMITED", LimitAmount.Unlimited).id

        for (amount in listOf(LimitAmount.Bounded(BigDecimal("1000.01")), LimitAmount.Unlimited)) {
            val failure = assertFailsWith<ValidationException> { fixture.request(bounded, amount) }
            assertTrue(failure.message.orEmpty().contains("maxAssignableAmount"))
        }
        val granted = fixture.approve(fixture.request(unlimited, LimitAmount.Unlimited).id)

        assertEquals(LimitRuleChangeStatus.APPLIED, granted.status)
        val rule = fixture.facade.limitInquiryService.getLimitRule(GetLimitRuleQuery(fixture.tenant, granted.limitRuleId!!))
        assertEquals(LimitAmount.Unlimited, rule.amount)
        assertEquals(unlimited, rule.configurationId)
    }

    @Test
    fun `the grant is validated again at application against the then-current configuration`() {
        val fixture = Fixture()
        val configuration = fixture.configuration("UNLIMITED", LimitAmount.Unlimited).id
        val scheduled = fixture.approve(
            fixture.request(configuration, LimitAmount.Unlimited, effectiveAt = NOW.plusSeconds(60)).id,
        )
        assertEquals(LimitRuleChangeStatus.PENDING_EFFECTIVE_DATE, scheduled.status)

        fixture.facade.tenantCatalogService.setLimitConfigurationActive(
            SetLimitConfigurationActiveCommand(fixture.tenant, configuration, false),
        )
        fixture.clock.setTo(NOW.plusSeconds(60))
        val result = fixture.facade.limitChangeService.applyDueLimitChanges(
            ApplyDueLimitChangesCommand(fixture.tenant), TrustedChangeActorContext.InternalSystem,
        )

        assertEquals(listOf(scheduled.id), result.failedChangeRequestIds)
        val failed = fixture.facade.limitInquiryService.getLimitChange(GetLimitChangeQuery(fixture.tenant, scheduled.id))
        assertEquals(LimitRuleChangeStatus.FAILED, failed.request.status)
        assertTrue(failed.request.decisionReason.orEmpty().contains("configuration must be active"))
    }

    private class Fixture {
        val clock = FixedClockProvider(NOW)
        val facade = PostgresFacade.create(clock, emptyList())
        val tenant = facade.tenantCatalogService.registerTenant(RegisterTenantCommand("Maximum bank ${UUID.randomUUID()}")).id
        val owner = facade.tenantCatalogService.registerPartyProfile(RegisterPartyProfileCommand(
            tenantId = tenant, partyType = PartyType.INDIVIDUAL, firstName = "Alex", lastName = "Customer",
        )).id

        fun command(code: String) = CreateLimitConfigurationCommand(
            tenantId = tenant, code = code, name = code, limitKind = LimitKind.PERIODIC, period = LimitPeriod.MONTHLY,
            currency = "EUR", maxAssignableAmount = LimitAmount.Bounded(BigDecimal("1000")),
        )

        fun configuration(
            code: String,
            maximum: LimitAmount,
            mode: LimitUsageConsumptionMode = LimitUsageConsumptionMode.CASCADING,
        ) = facade.tenantCatalogService.createLimitConfiguration(
            command(code).copy(maxAssignableAmount = maximum, usageConsumptionMode = mode),
        )

        fun request(configuration: UUID, amount: LimitAmount, effectiveAt: Instant? = null) =
            facade.limitChangeService.requestLimitRuleChange(RequestLimitRuleChangeCommand(
                tenantId = tenant,
                changeType = LimitRuleChangeType.CREATE,
                requestedBy = LimitChangeActorCommand(LimitChangeActorType.SYSTEM),
                targetOwnerPartyProfileId = owner,
                requestedRule = LimitRuleDraft(
                    name = "Monthly spending", limitKind = LimitKind.PERIODIC, period = LimitPeriod.MONTHLY,
                    amount = amount, currency = "EUR", configurationId = configuration,
                ),
                effectiveAt = effectiveAt,
            ), TrustedChangeActorContext.InternalSystem)

        fun approve(id: UUID) = facade.limitChangeService.approveLimitRuleChange(
            ApproveLimitRuleChangeCommand(tenant, id, LimitChangeActorCommand(LimitChangeActorType.SYSTEM)),
            TrustedChangeActorContext.InternalSystem,
        )
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-06-26T12:00:00Z")
    }
}
