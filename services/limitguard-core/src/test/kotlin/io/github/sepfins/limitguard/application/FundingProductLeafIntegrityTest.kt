package io.github.sepfins.limitguard.application

import io.github.sepfins.limitguard.api.errors.ValidationException
import io.github.sepfins.limitguard.support.FixedClockProvider
import io.github.sepfins.limitguard.support.FundingProductScenario
import io.github.sepfins.limitguard.support.PostgresFacade
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

    private fun scenario() =
        FundingProductScenario(PostgresFacade.create(FixedClockProvider(Instant.parse("2026-10-10T10:00:00Z"))))
}
