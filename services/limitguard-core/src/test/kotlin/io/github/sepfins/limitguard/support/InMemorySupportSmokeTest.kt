package io.github.sepfins.limitguard.support

import io.github.sepfins.limitguard.api.errors.ConflictException
import io.github.sepfins.limitguard.application.DefaultLimitguard
import io.github.sepfins.limitguard.domain.channel.Channel
import io.github.sepfins.limitguard.domain.channel.ChannelCode
import io.github.sepfins.limitguard.domain.channel.ChannelName
import io.github.sepfins.limitguard.domain.common.ChannelId
import io.github.sepfins.limitguard.domain.common.Metadata
import io.github.sepfins.limitguard.domain.common.TenantId
import io.github.sepfins.limitguard.domain.tenant.Tenant
import io.github.sepfins.limitguard.domain.tenant.TenantName
import io.github.sepfins.limitguard.support.persistence.InMemoryLimitguardRepositories
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import java.time.Instant

class InMemorySupportSmokeTest {
    @Test
    fun `constructs in-memory wiring for later application services`() {
        val repositories = InMemoryLimitguardRepositories()
        val clock = FixedClockProvider(Instant.parse("2026-06-14T00:00:00Z"))
        val ids = SequentialIdGenerator(startAt = 42L)
        val transactions = DirectTransactionRunner()

        val limitguard = DefaultLimitguard(
            tenantRepository = repositories.tenants,
            channelRepository = repositories.channels,
            operationTypeRepository = repositories.operationTypes,
            partyRepository = repositories.parties,
            individualRepository = repositories.individuals,
            legalEntityRepository = repositories.legalEntities,
            partyLegalIdentifierRepository = repositories.partyLegalIdentifiers,
            partyProfileRepository = repositories.partyProfiles,
            fundingSourceRepository = repositories.fundingSources,
            fundingSourceProductRepository = repositories.fundingSourceProducts,
            fundingSourceOwnerRepository = repositories.fundingSourceOwners,
            mandateRepository = repositories.mandates,
            limitConfigurationRepository = repositories.limitConfigurations,
            idGenerator = ids,
            clockProvider = clock,
            transactionRunner = transactions,
        )

        assertNotNull(repositories.tenants)
        assertEquals(Instant.parse("2026-06-14T00:00:00Z"), clock.now())
        assertEquals(UUID(0L, 42L), ids.newId())
        assertEquals("ok", transactions.inTransaction { "ok" })
        assertNotNull(limitguard.tenantCatalogService)
        assertNotNull(limitguard.limitChangeService)
        assertNotNull(limitguard.limitEvaluationService)
    }

    @Test
    fun `repositories enforce tenant scoped uniqueness`() {
        val repositories = InMemoryLimitguardRepositories()
        val tenantId = TenantId.of(UUID(0L, 1L))
        val otherTenantId = TenantId.of(UUID(0L, 2L))
        val now = Instant.parse("2026-06-14T00:00:00Z")

        repositories.tenants.insert(Tenant(tenantId, TenantName.of("Primary Bank"), Metadata.EMPTY, now))
        repositories.tenants.insert(Tenant(otherTenantId, TenantName.of("Other Bank"), Metadata.EMPTY, now))

        repositories.channels.insert(
            tenantId,
            Channel(
                id = ChannelId.of(UUID(0L, 10L)),
                tenantId = tenantId,
                code = ChannelCode.of("ATM"),
                name = ChannelName.of("ATM"),
                isActive = true,
                createdAt = now,
            ),
        )

        assertFailsWith<ConflictException> {
            repositories.channels.insert(
                tenantId,
                Channel(
                    id = ChannelId.of(UUID(0L, 11L)),
                    tenantId = tenantId,
                    code = ChannelCode.of("ATM"),
                    name = ChannelName.of("Duplicate ATM"),
                    isActive = true,
                    createdAt = now,
                ),
            )
        }

        repositories.channels.insert(
            otherTenantId,
            Channel(
                id = ChannelId.of(UUID(0L, 12L)),
                tenantId = otherTenantId,
                code = ChannelCode.of("ATM"),
                name = ChannelName.of("ATM"),
                isActive = true,
                createdAt = now,
            ),
        )

        assertEquals(1, repositories.channels.findActive(tenantId).size)
        assertEquals(1, repositories.channels.findActive(otherTenantId).size)
    }
}
