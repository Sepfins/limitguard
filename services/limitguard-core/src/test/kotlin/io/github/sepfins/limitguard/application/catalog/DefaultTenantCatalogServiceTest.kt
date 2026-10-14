package io.github.sepfins.limitguard.application.catalog

import io.github.sepfins.limitguard.api.LimitAmount
import io.github.sepfins.limitguard.api.FundingSourceCategory
import io.github.sepfins.limitguard.api.LimitChannelScope
import io.github.sepfins.limitguard.api.LimitFundingScope
import io.github.sepfins.limitguard.api.LimitKind
import io.github.sepfins.limitguard.api.LimitPeriod
import io.github.sepfins.limitguard.api.NoMatchingRuleBehavior
import io.github.sepfins.limitguard.api.PartyType
import io.github.sepfins.limitguard.api.ProductNodeType
import io.github.sepfins.limitguard.api.commands.AssignFundingSourceOwnerCommand
import io.github.sepfins.limitguard.api.commands.CreateLimitConfigurationCommand
import io.github.sepfins.limitguard.api.commands.CreateMandateCommand
import io.github.sepfins.limitguard.api.commands.RegisterChannelCommand
import io.github.sepfins.limitguard.api.commands.RegisterFundingSourceCommand
import io.github.sepfins.limitguard.api.commands.RegisterFundingSourceProductCommand
import io.github.sepfins.limitguard.api.commands.RegisterOperationTypeCommand
import io.github.sepfins.limitguard.api.commands.RegisterPartyProfileCommand
import io.github.sepfins.limitguard.api.commands.RegisterTenantCommand
import io.github.sepfins.limitguard.api.commands.SetChannelActiveCommand
import io.github.sepfins.limitguard.api.commands.SetFundingSourceActiveCommand
import io.github.sepfins.limitguard.api.commands.SetFundingSourceProductActiveCommand
import io.github.sepfins.limitguard.api.commands.SetLimitConfigurationActiveCommand
import io.github.sepfins.limitguard.api.commands.UpdateChannelCommand
import io.github.sepfins.limitguard.api.commands.UpdateFundingSourceCommand
import io.github.sepfins.limitguard.api.commands.UpdateFundingSourceProductCommand
import io.github.sepfins.limitguard.api.commands.UpdateLimitConfigurationCommand
import io.github.sepfins.limitguard.api.errors.ConflictException
import io.github.sepfins.limitguard.api.errors.NotFoundException
import io.github.sepfins.limitguard.api.errors.ValidationException
import io.github.sepfins.limitguard.api.queries.FindChannelByCodeQuery
import io.github.sepfins.limitguard.api.queries.FindFundingSourceByExternalReferenceQuery
import io.github.sepfins.limitguard.api.queries.FindFundingSourceProductByCodeQuery
import io.github.sepfins.limitguard.api.queries.FindLimitConfigurationByCodeQuery
import io.github.sepfins.limitguard.api.queries.FindTenantByNameQuery
import io.github.sepfins.limitguard.support.FixedClockProvider
import io.github.sepfins.limitguard.support.PostgresFacade
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DefaultTenantCatalogServiceTest {
    private val fixedNow = Instant.parse("2026-06-21T00:00:00Z")
    private val facade = PostgresFacade.create(FixedClockProvider(fixedNow))
    private val service = facade.tenantCatalogService
    private val tenant = service.registerTenant(RegisterTenantCommand("Retail Bank")).id

    @Test
    fun `register tenant rejects duplicate name and defaults to deny unmatched authorizations`() {
        assertEquals(NoMatchingRuleBehavior.DENY, service.registerTenant(RegisterTenantCommand("Other Bank")).noMatchingRuleBehavior)
        assertFailsWith<ConflictException> { service.registerTenant(RegisterTenantCommand("Retail Bank")) }
    }

    @Test
    fun `catalog commands reject missing tenant scoped references`() {
        val missing = UUID.randomUUID()
        val owner = profile("Owner")
        val assignee = profile("Assignee")
        val unowned = service.registerFundingSource(RegisterFundingSourceCommand(tenant, leafProduct().id)).id

        assertFailsWith<NotFoundException> { service.registerChannel(RegisterChannelCommand(missing, "ATM", "ATM")) }
        assertFailsWith<NotFoundException> {
            service.registerOperationType(RegisterOperationTypeCommand(tenant, "WIRE", "Wire", parentId = missing))
        }
        assertFailsWith<NotFoundException> {
            service.registerFundingSourceProduct(RegisterFundingSourceProductCommand(
                tenant, "CC2000", "Account", FundingSourceCategory.TRANSACTIONAL, ProductNodeType.PRODUCT, parentId = missing,
            ))
        }
        assertFailsWith<NotFoundException> { service.registerFundingSource(RegisterFundingSourceCommand(tenant, missing)) }
        assertFailsWith<NotFoundException> {
            service.assignFundingSourceOwner(AssignFundingSourceOwnerCommand(tenant, missing, unowned))
        }
        assertFailsWith<NotFoundException> {
            service.createMandate(CreateMandateCommand(
                tenant, owner, assignee, "Family payment delegate",
                fundingScope = LimitFundingScope.FUNDING_SOURCE, fundingSourceId = unowned,
            ))
        }
        assertFailsWith<NotFoundException> {
            service.createLimitConfiguration(configuration("DAILY").copy(channelScope = LimitChannelScope.CHANNEL, channelId = missing))
        }
    }

    @Test
    fun `a tenant can register a global operation`() {
        val result = service.registerOperationType(RegisterOperationTypeCommand(tenant, "CARD_PAYMENT", "Card payment", isGlobal = true))

        assertNull(result.tenantId)
        assertTrue(result.isGlobal)
    }

    @Test
    fun `create limit configuration rejects a child cap above its parent`() {
        val parent = service.createLimitConfiguration(configuration("TRANSFER_CAP"))

        assertFailsWith<ValidationException> {
            service.createLimitConfiguration(configuration("PREMIUM_TRANSFER_CAP").copy(
                maxAssignableAmount = LimitAmount.Bounded(BigDecimal("1000.01")), parentConfigurationId = parent.id,
            ))
        }
    }

    @Test
    fun `catalog administration updates details and controls activation while lookups still find inactive entries`() {
        val channel = service.registerChannel(RegisterChannelCommand(tenant, "ATM", "ATM"))
        val product = leafProduct()
        val fundingSource = service.registerFundingSource(RegisterFundingSourceCommand(tenant, product.id, externalReference = "ACC-001"))
        val configuration = service.createLimitConfiguration(configuration("ATM_DAILY").copy(defaultAmount = BigDecimal("250.00")))

        val updatedChannel = service.updateChannel(UpdateChannelCommand(tenant, channel.id, "Cash machine", mapOf("owner" to "channels-team")))
        val inactiveChannel = service.setChannelActive(SetChannelActiveCommand(tenant, channel.id, false))
        val updatedProduct = service.updateFundingSourceProduct(UpdateFundingSourceProductCommand(
            tenant, product.id, "Everyday current account", mapOf("segment" to "retail"),
        ))
        val inactiveProduct = service.setFundingSourceProductActive(SetFundingSourceProductActiveCommand(tenant, product.id, false))
        val updatedSource = service.updateFundingSource(UpdateFundingSourceCommand(
            tenant, fundingSource.id, "ACC-PRIMARY", mapOf("verified" to true),
        ))
        val inactiveSource = service.setFundingSourceActive(SetFundingSourceActiveCommand(tenant, fundingSource.id, false))
        val updatedConfiguration = service.updateLimitConfiguration(UpdateLimitConfigurationCommand(
            tenant, configuration.id, "ATM daily default", "Tenant-controlled default", BigDecimal("300.00"),
            mapOf("owner" to "limits-team"),
        ))
        val inactiveConfiguration = service.setLimitConfigurationActive(SetLimitConfigurationActiveCommand(tenant, configuration.id, false))

        assertEquals("Cash machine", updatedChannel.name)
        assertFalse(inactiveChannel.isActive)
        assertEquals("Everyday current account", updatedProduct.name)
        assertFalse(inactiveProduct.isActive)
        assertEquals("ACC-PRIMARY", updatedSource.externalReference)
        assertFalse(inactiveSource.isActive)
        assertEquals(BigDecimal("300.0000"), updatedConfiguration.defaultAmount)
        assertEquals(LimitAmount.Bounded(BigDecimal("1000.0000")), updatedConfiguration.maxAssignableAmount)
        assertFalse(inactiveConfiguration.isActive)
        assertEquals(fixedNow, inactiveConfiguration.updatedAt)
        val inquiry = facade.limitInquiryService
        assertEquals(tenant, inquiry.findTenantByName(FindTenantByNameQuery("Retail Bank"))?.id)
        assertEquals(inactiveChannel, inquiry.findChannelByCode(FindChannelByCodeQuery(tenant, "ATM")))
        assertEquals(inactiveProduct, inquiry.findFundingSourceProductByCode(FindFundingSourceProductByCodeQuery(tenant, "CC1000")))
        assertEquals(inactiveSource, inquiry.findFundingSourceByExternalReference(
            FindFundingSourceByExternalReferenceQuery(tenant, "ACC-PRIMARY"),
        ))
        assertEquals(inactiveConfiguration, inquiry.findLimitConfigurationByCode(FindLimitConfigurationByCodeQuery(tenant, "ATM_DAILY")))
    }

    private fun profile(name: String) = service.registerPartyProfile(RegisterPartyProfileCommand(
        tenant, PartyType.INDIVIDUAL, firstName = name, lastName = "Customer",
    )).id

    private fun leafProduct() = service.registerFundingSourceProduct(RegisterFundingSourceProductCommand(
        tenant, "CC1000", "Retail current account", FundingSourceCategory.TRANSACTIONAL, ProductNodeType.PRODUCT, isLeaf = true,
    ))

    private fun configuration(code: String) = CreateLimitConfigurationCommand(
        tenantId = tenant, code = code, name = code, limitKind = LimitKind.PERIODIC, period = LimitPeriod.DAILY,
        currency = "EUR", maxAssignableAmount = LimitAmount.Bounded(BigDecimal("1000.00")),
    )
}
