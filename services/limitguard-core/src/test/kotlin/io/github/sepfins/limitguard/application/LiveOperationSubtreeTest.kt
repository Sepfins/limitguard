package io.github.sepfins.limitguard.application

import io.github.sepfins.limitguard.api.AuthorizationDecision
import io.github.sepfins.limitguard.api.LimitFundingScope
import io.github.sepfins.limitguard.api.OperationCapacityStatus
import io.github.sepfins.limitguard.api.commands.AssignFundingSourceOwnerCommand
import io.github.sepfins.limitguard.api.commands.CaptureTransactionCommand
import io.github.sepfins.limitguard.api.commands.CreateMandateCommand
import io.github.sepfins.limitguard.api.commands.ReleaseAuthorizationCommand
import io.github.sepfins.limitguard.api.commands.ReverseTransactionCommand
import io.github.sepfins.limitguard.api.errors.ConflictException
import io.github.sepfins.limitguard.api.errors.NotFoundException
import io.github.sepfins.limitguard.api.errors.ValidationException
import io.github.sepfins.limitguard.api.queries.FindOperationTypesByCodeQuery
import io.github.sepfins.limitguard.domain.common.OperationTypeId
import io.github.sepfins.limitguard.domain.common.TenantId
import io.github.sepfins.limitguard.support.FixedClockProvider
import io.github.sepfins.limitguard.support.OperationSubtreeScenario
import io.github.sepfins.limitguard.support.createFacade
import io.github.sepfins.limitguard.support.persistence.InMemoryLimitguardRepositories
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LiveOperationSubtreeTest {
    @Test
    fun `selected operation and descendants share coverage while siblings do not`() {
        val s = scenario()
        val family = s.operation("CASH")
        val selected = s.operation("WITHDRAWAL", family)
        val child = s.operation("ATM", selected)
        val grandchild = s.operation("CONTACTLESS_ATM", child, leaf = true)
        val sibling = s.operation("DEPOSIT", family, leaf = true)
        val rule = s.grant(selected)

        for (operation in listOf(selected, child, grandchild)) {
            assertEquals(listOf(rule), s.capacity(operation).applicableLimits.map { it.limitRuleId })
            val authorization = s.authorize(operation)
            assertEquals(AuthorizationDecision.ALLOWED, authorization.decision)
            assertEquals(listOf(rule), authorization.matchedRules.map { it.id })
        }
        assertEquals(emptyList(), s.capacity(sibling).applicableLimits)
        assertEquals(emptyList(), s.authorize(sibling).matchedRules)
    }

    @Test
    fun `global registration cannot depend on a tenant private parent`() {
        val s = scenario()
        val local = s.operation("LOCAL")

        assertFailsWith<ValidationException> { s.operation("GLOBAL_CHILD", local, global = true) }
    }

    @Test
    fun `corrupt global ancestry fails new evaluations and descendant registration even with unmatched ALLOW`() {
        val repositories = InMemoryLimitguardRepositories()
        val s = scenario(repositories)
        val local = s.operation("LOCAL")
        val global = s.operation("GLOBAL", global = true)
        val selected = s.operation("SELECTED", global)
        val tenant = TenantId.of(s.tenant)
        val globalNode = requireNotNull(repositories.operationTypes.findById(tenant, OperationTypeId.of(global)))
        repositories.operationTypes.update(tenant, globalNode.copy(parentId = OperationTypeId.of(local)))

        assertFailsWith<ConflictException> { s.authorize(selected) }
        assertFailsWith<ConflictException> { s.capacity(selected) }
        assertFailsWith<ConflictException> { s.operation("FUTURE", selected) }
    }

    @Test
    fun `a declared operation leaf cannot gain a child or be silently reclassified`() {
        val s = scenario()
        val leaf = s.operation("LEAF", leaf = true)

        assertFailsWith<ValidationException> { s.operation("CHILD", leaf) }
        assertEquals(AuthorizationDecision.ALLOWED, s.authorize(leaf).decision)
    }

    @Test
    fun `a stale leaf flag with registered children rejects parent and descendant evaluation`() {
        val repositories = InMemoryLimitguardRepositories()
        val s = scenario(repositories)
        val parent = s.operation("PARENT")
        val child = s.operation("CHILD", parent)
        val tenant = TenantId.of(s.tenant)
        val parentNode = requireNotNull(repositories.operationTypes.findById(tenant, OperationTypeId.of(parent)))
        repositories.operationTypes.update(tenant, parentNode.copy(isLeaf = true))

        for (operation in listOf(parent, child)) {
            assertFailsWith<ConflictException> { s.authorize(operation) }
            assertFailsWith<ConflictException> { s.capacity(operation) }
            assertFailsWith<ConflictException> { s.operation("FUTURE", operation) }
        }
    }

    @Test
    fun `a shared leaf with another tenants child is invalid without disclosing that child`() {
        val repositories = InMemoryLimitguardRepositories()
        val s = scenario(repositories)
        val global = s.operation("GLOBAL", global = true)
        val other = OperationSubtreeScenario(s.facade, s.now)
        other.operation("PRIVATE_CHILD", global)
        val tenant = TenantId.of(s.tenant)
        val globalNode = requireNotNull(repositories.operationTypes.findById(tenant, OperationTypeId.of(global)))
        repositories.operationTypes.update(tenant, globalNode.copy(isLeaf = true))

        assertFailsWith<ConflictException> { s.authorize(global) }
        assertFailsWith<ConflictException> { s.capacity(global) }
    }

    @Test
    fun `global and local nodes with the same code retain distinct live subtrees`() {
        val s = scenario()
        val global = s.operation("TRANSFER", global = true)
        val globalChild = s.operation("GLOBAL_TRANSFER", global, global = true)
        val local = s.operation("TRANSFER")
        val globalRule = s.grant(global)
        val localRule = s.grant(local)
        val allRule = s.grant(null)
        val futureLocalDescendant = s.operation("INSTANT", globalChild, leaf = true)
        val futurePrivateDescendant = s.operation("LOCAL_INSTANT", local, leaf = true)
        val other = OperationSubtreeScenario(s.facade, s.now)
        val otherPrivate = other.operation("TRANSFER", global)

        assertEquals(setOf(global, local), s.facade.limitInquiryService.findOperationTypesByCode(
            FindOperationTypesByCodeQuery(s.tenant, "TRANSFER"),
        ).map { it.id }.toSet())
        for ((operation, specificRule) in listOf(
            global to globalRule, globalChild to globalRule, futureLocalDescendant to globalRule,
            local to localRule, futurePrivateDescendant to localRule,
        )) {
            val expected = setOf(specificRule, allRule)
            assertEquals(expected, s.capacity(operation).applicableLimits.map { it.limitRuleId }.toSet())
            assertEquals(expected, s.authorize(operation).matchedRules.map { it.id }.toSet())
        }
        assertFailsWith<NotFoundException> { s.authorize(otherPrivate) }
        assertFailsWith<NotFoundException> { s.capacity(otherPrivate) }
        assertFailsWith<NotFoundException> { s.operation("HIDDEN_PARENT", otherPrivate) }
    }

    @Test
    fun `hidden missing and cyclic ancestor paths are public errors not unrestricted decisions`() {
        for (corruption in listOf("HIDDEN", "MISSING", "CYCLE")) {
            val repositories = InMemoryLimitguardRepositories()
            val s = scenario(repositories)
            val root = s.operation("ROOT")
            val middle = s.operation("MIDDLE", root)
            val selected = s.operation("SELECTED", middle)
            val other = OperationSubtreeScenario(s.facade, s.now)
            val corruptParent = when (corruption) {
                "HIDDEN" -> other.operation("PRIVATE")
                "CYCLE" -> selected
                else -> UUID.randomUUID()
            }
            val tenant = TenantId.of(s.tenant)
            val rootNode = requireNotNull(repositories.operationTypes.findById(tenant, OperationTypeId.of(root)))
            repositories.operationTypes.update(tenant, rootNode.copy(parentId = OperationTypeId.of(corruptParent)))
            val command = s.command(selected, key = "invalid-path")

            if (corruption == "CYCLE") {
                assertFailsWith<ConflictException> { s.facade.limitEvaluationService.authorizeTransaction(command) }
                assertFailsWith<ConflictException> { s.capacity(selected) }
                assertFailsWith<ConflictException> { s.operation("FUTURE", selected) }
            } else {
                assertFailsWith<NotFoundException> { s.facade.limitEvaluationService.authorizeTransaction(command) }
                assertFailsWith<NotFoundException> { s.capacity(selected) }
                assertFailsWith<NotFoundException> { s.operation("FUTURE", selected) }
            }
            repositories.operationTypes.update(tenant, rootNode)
            assertEquals(AuthorizationDecision.ALLOWED, s.facade.limitEvaluationService.authorizeTransaction(command).decision)
            assertEquals(OperationCapacityStatus.UNLIMITED, s.capacity(selected).status)
        }
    }

    @Test
    fun `descendant isolation keeps equal priority ancestors and other owner cascades without implicit ceilings`() {
        val s = scenario()
        val ancestor = s.operation("TRANSFER")
        val descendant = s.operation("INSTANT", ancestor, leaf = true)
        val isolated = s.grant(descendant, priority = 10, isolated = true)
        val peerAncestor = s.grant(ancestor, priority = 10)
        val skipped = s.grant(null, amount = "1", priority = 20)
        val coOwner = s.profile("Co-owner")
        s.facade.tenantCatalogService.assignFundingSourceOwner(AssignFundingSourceOwnerCommand(s.tenant, coOwner, s.source))
        s.facade.tenantCatalogService.createMandate(CreateMandateCommand(s.tenant, coOwner, s.owner, "Co-owner grant"))
        val otherOwnerAncestor = s.grant(ancestor, priority = 20, ruleOwner = coOwner)
        val perTransactionAncestor = s.grant(ancestor, priority = 1000, perTransaction = true)
        val expected = setOf(isolated, peerAncestor, otherOwnerAncestor, perTransactionAncestor)

        assertEquals(expected, s.capacity(descendant).applicableLimits.map { it.limitRuleId }.toSet())
        val authorization = s.authorize(descendant)
        assertEquals(AuthorizationDecision.ALLOWED, authorization.decision)
        assertEquals(expected, authorization.matchedRules.map { it.id }.toSet())
        assertEquals(BigDecimal("40.0000"), s.usage(otherOwnerAncestor).reservedAmount)
        assertEquals(BigDecimal("0.0000"), s.usage(skipped).reservedAmount)
    }

    @Test
    fun `every matching per transaction cap binds even beyond an isolated periodic tier`() {
        val s = scenario()
        val ancestor = s.operation("PAYMENT")
        val descendant = s.operation("CARD", ancestor, leaf = true)
        val isolated = s.grant(descendant, priority = 10, isolated = true)
        val ancestorCap = s.grant(ancestor, amount = "25", priority = 1000, perTransaction = true)
        val descendantCap = s.grant(descendant, amount = "30", priority = 2000, perTransaction = true)
        val allCap = s.grant(null, amount = "35", priority = 3000, perTransaction = true)

        assertEquals(BigDecimal("25.0000"), s.capacity(descendant).effectiveAvailableAmount)
        val denied = s.authorize(descendant)
        assertEquals(AuthorizationDecision.DENIED, denied.decision)
        assertEquals(setOf(ancestorCap, descendantCap, allCap), denied.violations.map { it.limitRuleId }.toSet())
        assertEquals(BigDecimal("0.0000"), s.usage(isolated).reservedAmount)
    }

    @Test
    fun `overlapping mandates do not duplicate ancestor reservations or pool assignees`() {
        val s = scenario()
        val ancestor = s.operation("PAYMENT")
        val descendant = s.operation("CARD", ancestor, leaf = true)
        val delegate = s.profile("Delegate")
        val mandate = s.facade.tenantCatalogService.createMandate(CreateMandateCommand(
            s.tenant, s.owner, delegate, "All sources",
        )).id
        s.facade.tenantCatalogService.createMandate(CreateMandateCommand(
            s.tenant, s.owner, delegate, "Specific source", fundingScope = LimitFundingScope.FUNDING_SOURCE,
            fundingSourceId = s.source,
        ))
        val ownerWide = s.grant(ancestor)
        val mandateSpecific = s.grant(descendant, mandate = mandate)

        val authorization = s.authorize(descendant, actor = delegate)
        assertEquals(setOf(ownerWide, mandateSpecific), authorization.matchedRules.map { it.id }.toSet())
        assertEquals(BigDecimal("40.0000"), s.usage(ownerWide, delegate).reservedAmount)
        assertEquals(BigDecimal("40.0000"), s.usage(mandateSpecific, delegate).reservedAmount)
        assertEquals(BigDecimal("0.0000"), s.usage(ownerWide).reservedAmount)
        assertEquals(BigDecimal("460.0000"), s.capacity(descendant, delegate).effectiveAvailableAmount)
        assertEquals(BigDecimal("500.0000"), s.capacity(descendant).effectiveAvailableAmount)
    }

    @Test
    fun `future descendants and rules affect new evaluations but replay and mutations retain original buckets`() {
        val repositories = InMemoryLimitguardRepositories()
        val s = scenario(repositories)
        val root = s.operation("TRANSFER")
        val selected = s.operation("INSTANT", root)
        val originalRule = s.grant(root)
        val command = s.command(selected, amount = "80", key = "original")
        val original = s.facade.limitEvaluationService.authorizeTransaction(command)
        val payment = requireNotNull(original.paymentTransactionId)
        val future = s.operation("FUTURE", selected, leaf = true)
        assertEquals(listOf(originalRule), s.capacity(future).applicableLimits.map { it.limitRuleId })
        val futureRule = s.grant(selected, priority = 10, isolated = true)
        assertEquals(listOf(futureRule), s.capacity(future).applicableLimits.map { it.limitRuleId })
        val tenant = TenantId.of(s.tenant)
        val selectedNode = requireNotNull(repositories.operationTypes.findById(tenant, OperationTypeId.of(selected)))
        repositories.operationTypes.update(tenant, selectedNode.copy(parentId = OperationTypeId.of(UUID.randomUUID())))

        assertEquals(original, s.facade.limitEvaluationService.authorizeTransaction(command))
        val capture = s.facade.limitEvaluationService.captureTransaction(CaptureTransactionCommand(s.tenant, payment, BigDecimal("30")))
        val release = s.facade.limitEvaluationService.releaseAuthorization(ReleaseAuthorizationCommand(s.tenant, payment))
        val reverse = s.facade.limitEvaluationService.reverseTransaction(ReverseTransactionCommand(s.tenant, payment, BigDecimal("10")))
        for (mutation in listOf(capture, release, reverse)) {
            assertEquals(listOf(originalRule), mutation.usageEvents.map { it.limitRuleId })
            assertEquals(s.usage(originalRule).usageBucketId, mutation.usageEvents.single().usageBucketId)
        }
        assertEquals(BigDecimal("0.0000"), s.usage(originalRule).reservedAmount)
        assertEquals(BigDecimal("20.0000"), s.usage(originalRule).consumedAmount)
        assertEquals(BigDecimal("0.0000"), s.usage(futureRule).reservedAmount)
        assertEquals(BigDecimal("0.0000"), s.usage(futureRule).consumedAmount)
        assertFailsWith<NotFoundException> { s.authorize(future) }
    }

    private fun scenario(repositories: InMemoryLimitguardRepositories = InMemoryLimitguardRepositories()): OperationSubtreeScenario {
        val now = Instant.parse("2026-10-05T10:00:00Z")
        return OperationSubtreeScenario(repositories.createFacade(FixedClockProvider(now), emptyList()), now)
    }
}
