package io.github.sepfins.limitguard.application

import io.github.sepfins.limitguard.api.commands.UpdateFundingSourceCommand
import io.github.sepfins.limitguard.api.errors.NotFoundException
import io.github.sepfins.limitguard.api.errors.ValidationException
import io.github.sepfins.limitguard.domain.common.FundingSourceProductId
import io.github.sepfins.limitguard.domain.common.TenantId
import io.github.sepfins.limitguard.support.FixedClockProvider
import io.github.sepfins.limitguard.support.FundingProductScenario
import io.github.sepfins.limitguard.support.createFacade
import io.github.sepfins.limitguard.support.persistence.InMemoryLimitguardRepositories
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class FundingProductLeafIntegrityTest {
    @Test
    fun `genuine leaf onboarding retains its product path and permits multiple sources`() {
        val s = scenario()
        val family = s.product("FAMILY")
        val subfamily = s.product("SUBFAMILY", family.id)
        val leaf = s.product("ACCOUNT", subfamily.id, leaf = true)
        val first = s.source(leaf.id, "account-1")
        val second = s.source(leaf.id, "account-2")

        assertEquals(leaf, s.findProduct("ACCOUNT"))
        assertEquals(subfamily.id, leaf.parentId)
        assertEquals(family.id, subfamily.parentId)
        assertEquals(first, s.findSource("account-1"))
        assertEquals(second, s.findSource("account-2"))
        assertEquals(leaf.id, first.productId)
        assertEquals(leaf.id, second.productId)
    }

    @Test
    fun `a declared funding leaf cannot grow or move its assigned sources`() {
        val s = scenario()
        val leaf = s.product("ACCOUNT", leaf = true)
        val source = s.source(leaf.id, "account-1")

        assertFailsWith<ValidationException> { s.product("CHILD", leaf.id, leaf = true) }

        assertEquals(leaf, s.findProduct("ACCOUNT"))
        assertEquals(source, s.findSource("account-1"))
        assertNull(s.findProduct("CHILD"))
    }

    @Test
    fun `a stale leaf flag cannot admit a source even when its child is inactive`() {
        val repositories = InMemoryLimitguardRepositories()
        val s = scenario(repositories)
        val parent = s.product("FAMILY")
        val child = s.product("CHILD", parent.id, leaf = true, active = false)
        val tenant = TenantId.of(s.tenant)
        val stored = requireNotNull(repositories.fundingSourceProducts.findById(
            tenant, FundingSourceProductId.of(parent.id),
        ))
        repositories.fundingSourceProducts.update(tenant, stored.copy(isLeaf = true))

        assertFailsWith<ValidationException> { s.source(parent.id, "invalid-account") }

        assertNull(s.findSource("invalid-account"))
        assertEquals(parent.copy(isLeaf = true), s.findProduct("FAMILY"))
        assertEquals(child, s.findProduct("CHILD"))
    }

    @Test
    fun `an unassigned inactive declared leaf still cannot gain a child`() {
        val s = scenario()
        val leaf = s.product("ACCOUNT", leaf = true, active = false)

        assertFailsWith<ValidationException> { s.product("CHILD", leaf.id) }

        assertEquals(leaf, s.findProduct("ACCOUNT"))
        assertNull(s.findProduct("CHILD"))
        assertNull(s.product("CHILD", leaf = true).parentId)
    }

    @Test
    fun `a product without declared assignability cannot admit a source with or without children`() {
        val s = scenario()
        val family = s.product("FAMILY")

        assertFailsWith<ValidationException> { s.source(family.id, "invalid-account") }
        assertNull(s.findSource("invalid-account"))
        val child = s.product("CHILD", family.id, leaf = true)
        assertFailsWith<ValidationException> { s.source(family.id, "invalid-account") }

        assertNull(s.findSource("invalid-account"))
        assertEquals(family, s.findProduct("FAMILY"))
        assertEquals(child.id, s.source(child.id, "invalid-account").productId)
    }

    @Test
    fun `parent product and source references cannot cross tenant boundaries`() {
        val s = scenario()
        val other = FundingProductScenario(s.facade)
        val foreignParent = other.product("FAMILY")
        val foreignLeaf = other.product("ACCOUNT", foreignParent.id, leaf = true)
        val foreignSource = other.source(foreignLeaf.id, "account-1")

        assertFailsWith<NotFoundException> { s.product("CHILD", foreignParent.id, leaf = true) }
        assertFailsWith<NotFoundException> { s.source(foreignLeaf.id, "account-1") }
        assertFailsWith<NotFoundException> {
            s.facade.tenantCatalogService.updateFundingSource(UpdateFundingSourceCommand(
                s.tenant, foreignSource.id, "moved-account", emptyMap(),
            ))
        }

        assertNull(s.findProduct("CHILD"))
        assertNull(s.findSource("account-1"))
        assertNull(s.findSource("moved-account"))
        assertEquals(foreignSource, other.findSource("account-1"))
        assertEquals(foreignLeaf, other.findProduct("ACCOUNT"))
    }

    private fun scenario(repositories: InMemoryLimitguardRepositories = InMemoryLimitguardRepositories()) =
        FundingProductScenario(repositories.createFacade(
            FixedClockProvider(Instant.parse("2026-10-10T10:00:00Z")), emptyList(),
        ))
}
