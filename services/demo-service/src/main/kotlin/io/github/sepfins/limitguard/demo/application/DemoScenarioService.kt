package io.github.sepfins.limitguard.demo.application

import io.github.sepfins.limitguard.api.FundingSourceCategory
import io.github.sepfins.limitguard.api.FundingSourceOwnerRole
import io.github.sepfins.limitguard.api.LimitChangeActorType
import io.github.sepfins.limitguard.api.LimitChangePolicyDecision
import io.github.sepfins.limitguard.api.LimitChangePolicyEngine
import io.github.sepfins.limitguard.api.LimitChangePolicyPhase
import io.github.sepfins.limitguard.api.LimitChannelScope
import io.github.sepfins.limitguard.api.LimitConfigurationFundingScope
import io.github.sepfins.limitguard.api.LimitFundingScope
import io.github.sepfins.limitguard.api.LimitKind
import io.github.sepfins.limitguard.api.LimitMandateScope
import io.github.sepfins.limitguard.api.LimitPeriod
import io.github.sepfins.limitguard.api.LimitRuleChangeStatus
import io.github.sepfins.limitguard.api.LimitRuleChangeType
import io.github.sepfins.limitguard.api.LimitUsageConsumptionMode
import io.github.sepfins.limitguard.api.Limitguard
import io.github.sepfins.limitguard.api.NoMatchingRuleBehavior
import io.github.sepfins.limitguard.api.PartyProfileDelegationType
import io.github.sepfins.limitguard.api.PartyType
import io.github.sepfins.limitguard.api.ProductNodeType
import io.github.sepfins.limitguard.api.commands.AssignFundingSourceOwnerCommand
import io.github.sepfins.limitguard.api.commands.CreateLimitChangePolicyCommand
import io.github.sepfins.limitguard.api.commands.CreateLimitConfigurationCommand
import io.github.sepfins.limitguard.api.commands.CreateMandateCommand
import io.github.sepfins.limitguard.api.commands.CreatePartyProfileDelegationCommand
import io.github.sepfins.limitguard.api.commands.LimitChangeActorCommand
import io.github.sepfins.limitguard.api.commands.LimitRuleDraft
import io.github.sepfins.limitguard.api.commands.RegisterChannelCommand
import io.github.sepfins.limitguard.api.commands.RegisterFundingSourceCommand
import io.github.sepfins.limitguard.api.commands.RegisterFundingSourceProductCommand
import io.github.sepfins.limitguard.api.commands.RegisterOperationTypeCommand
import io.github.sepfins.limitguard.api.commands.RegisterPartyProfileCommand
import io.github.sepfins.limitguard.api.commands.RegisterTenantCommand
import io.github.sepfins.limitguard.api.commands.RequestLimitRuleChangeCommand
import io.github.sepfins.limitguard.demo.configuration.DemoProperties
import io.github.sepfins.limitguard.demo.domain.AccountType
import io.github.sepfins.limitguard.demo.domain.CardStatus
import io.github.sepfins.limitguard.demo.domain.DemoAccount
import io.github.sepfins.limitguard.demo.domain.DemoCard
import io.github.sepfins.limitguard.demo.domain.DemoLimitSetting
import io.github.sepfins.limitguard.demo.domain.DemoOperation
import io.github.sepfins.limitguard.demo.domain.DemoScenario
import io.github.sepfins.limitguard.demo.domain.ScenarioStatus
import io.github.sepfins.limitguard.demo.persistence.DemoBankRepository
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.util.UUID

private val AGREED_TRANSFER_MAXIMUM = BigDecimal("7500")

@Service
class DemoScenarioService(
    private val limitguard: Limitguard,
    private val repository: DemoBankRepository,
    private val transactionTemplate: TransactionTemplate,
    private val properties: DemoProperties,
) {
    fun current(): DemoScenario =
        repository.findScenarioByKey(properties.scenarioKey)?.requireReady()
            ?: error("demo scenario has not been initialized")

    fun currentOrNull(): DemoScenario? =
        repository.findScenarioByKey(properties.scenarioKey)?.takeIf { it.status == ScenarioStatus.READY }

    fun ensureInitialized() {
        val existing = repository.findScenarioByKey(properties.scenarioKey)
        if (existing?.status?.name == "READY") return
        check(existing == null) {
            "Demo scenario bootstrap previously failed. Drop schema limitguard_demo and restart."
        }

        val scenarioId = UUID.randomUUID()
        val now = Instant.now(Clock.systemUTC())
        repository.insertBootstrappingScenario(scenarioId, properties.scenarioKey, now)

        try {
            val manifest = createCoreScenario()
            val accounts = createAccounts(scenarioId, manifest, now)
            val card = createCard(scenarioId, accounts.current.id, now)
            val settings = createLimitSettings(scenarioId, manifest, now)

            transactionTemplate.executeWithoutResult {
                accounts.all.forEach(repository::insertAccount)
                repository.insertCard(card)
                settings.forEach(repository::insertLimitSetting)
                repository.completeScenario(
                    id = scenarioId,
                    tenantId = manifest.tenantId,
                    customerPartyProfileId = manifest.customerProfileId,
                    ownerPartyProfileId = manifest.ownerProfileId,
                    authorityDelegationId = manifest.delegationId,
                    mandateId = manifest.mandateId,
                    now = Instant.now(Clock.systemUTC()),
                )
            }
        } catch (exception: Exception) {
            repository.failScenario(scenarioId, Instant.now(Clock.systemUTC()))
            throw exception
        }
    }

    private fun createCoreScenario(): CoreManifest {
        val catalog = limitguard.tenantCatalogService
        val tenant = catalog.registerTenant(
            RegisterTenantCommand(
                name = "Wrenfield",
                config = mapOf("journey" to "CUSTOMER_BANKING_DEMO"),
                noMatchingRuleBehavior = NoMatchingRuleBehavior.DENY,
            ),
        )
        val tenantId = tenant.id

        val cardChannel = catalog.registerChannel(
            RegisterChannelCommand(tenantId, "CARD_POS", "Card terminal"),
        )
        val atmChannel = catalog.registerChannel(
            RegisterChannelCommand(tenantId, "ATM", "ATM"),
        )
        val onlineChannel = catalog.registerChannel(
            RegisterChannelCommand(tenantId, "ONLINE_BANKING", "Online banking"),
        )

        val cardOperation = catalog.registerOperationType(
            RegisterOperationTypeCommand(tenantId, "CARD_PURCHASE", "Card purchase", isLeaf = true),
        )
        val atmOperation = catalog.registerOperationType(
            RegisterOperationTypeCommand(tenantId, "CASH_WITHDRAWAL", "Cash withdrawal", isLeaf = true),
        )
        val transferOperation = catalog.registerOperationType(
            RegisterOperationTypeCommand(tenantId, "BANK_TRANSFER", "Bank transfer", isLeaf = true),
        )

        val owner = catalog.registerPartyProfile(
            RegisterPartyProfileCommand(
                tenantId = tenantId,
                partyType = PartyType.INDIVIDUAL,
                firstName = "Daniel",
                lastName = "Clarke",
                metadata = mapOf("demoRole" to "HOUSEHOLD_OWNER"),
            ),
        )
        val customer = catalog.registerPartyProfile(
            RegisterPartyProfileCommand(
                tenantId = tenantId,
                partyType = PartyType.INDIVIDUAL,
                firstName = "Emma",
                lastName = "Clarke",
                metadata = mapOf("demoRole" to "ACTIVE_CUSTOMER"),
            ),
        )
        val delegation = catalog.createPartyProfileDelegation(
            CreatePartyProfileDelegationCommand(
                tenantId = tenantId,
                ownerPartyProfileId = owner.id,
                delegatePartyProfileId = customer.id,
                delegationType = PartyProfileDelegationType.AUTHORIZED_USER,
                metadata = mapOf("journey" to "HOUSEHOLD_BANKING"),
            ),
        )

        val currentProduct = catalog.registerFundingSourceProduct(
            RegisterFundingSourceProductCommand(
                tenantId = tenantId,
                code = "HOUSEHOLD_CURRENT",
                name = "Household current account",
                category = FundingSourceCategory.TRANSACTIONAL,
                nodeType = ProductNodeType.PRODUCT,
                isLeaf = true,
            ),
        )
        val savingsProduct = catalog.registerFundingSourceProduct(
            RegisterFundingSourceProductCommand(
                tenantId = tenantId,
                code = "PERSONAL_SAVINGS",
                name = "Personal savings account",
                category = FundingSourceCategory.SAVINGS,
                nodeType = ProductNodeType.PRODUCT,
                isLeaf = true,
            ),
        )
        val currentSource = catalog.registerFundingSource(
            RegisterFundingSourceCommand(
                tenantId = tenantId,
                productId = currentProduct.id,
                externalReference = "DEMO-HOUSEHOLD-CURRENT",
            ),
        )
        val savingsSource = catalog.registerFundingSource(
            RegisterFundingSourceCommand(
                tenantId = tenantId,
                productId = savingsProduct.id,
                externalReference = "DEMO-PERSONAL-SAVINGS",
            ),
        )
        catalog.assignFundingSourceOwner(
            AssignFundingSourceOwnerCommand(
                tenantId = tenantId,
                partyProfileId = owner.id,
                fundingSourceId = currentSource.id,
                ownerRole = FundingSourceOwnerRole.PRIMARY_OWNER,
            ),
        )
        catalog.assignFundingSourceOwner(
            AssignFundingSourceOwnerCommand(
                tenantId = tenantId,
                partyProfileId = customer.id,
                fundingSourceId = savingsSource.id,
                ownerRole = FundingSourceOwnerRole.PRIMARY_OWNER,
            ),
        )
        val mandate = catalog.createMandate(
            CreateMandateCommand(
                tenantId = tenantId,
                ownerPartyProfileId = owner.id,
                assigneePartyProfileId = customer.id,
                name = "Household spending authority",
                description = "Authorized access to the shared household account",
                authorityDelegationId = delegation.id,
                fundingScope = LimitFundingScope.FUNDING_SOURCE,
                fundingSourceId = currentSource.id,
                metadata = mapOf("journey" to "HOUSEHOLD_BANKING"),
            ),
        )

        seedLimitChangePolicies(tenantId)

        val cardLimit = createLimit(
            tenantId = tenantId,
            ownerProfileId = owner.id,
            customerProfileId = customer.id,
            delegationId = delegation.id,
            mandateId = mandate.id,
            productId = currentProduct.id,
            channelId = cardChannel.id,
            operationTypeId = cardOperation.id,
            code = "CARD_PURCHASE_DAILY",
            name = "Daily card purchases",
            amount = "1500",
            maximum = "3000",
            kind = LimitKind.PERIODIC,
            period = LimitPeriod.DAILY,
        )
        val atmLimit = createLimit(
            tenantId = tenantId,
            ownerProfileId = owner.id,
            customerProfileId = customer.id,
            delegationId = delegation.id,
            mandateId = mandate.id,
            productId = currentProduct.id,
            channelId = atmChannel.id,
            operationTypeId = atmOperation.id,
            code = "ATM_WITHDRAWAL_DAILY",
            name = "Daily ATM withdrawals",
            amount = "500",
            maximum = "1000",
            kind = LimitKind.PERIODIC,
            period = LimitPeriod.DAILY,
        )
        val transferPerTransaction = createLimit(
            tenantId = tenantId,
            ownerProfileId = owner.id,
            customerProfileId = customer.id,
            delegationId = delegation.id,
            mandateId = mandate.id,
            productId = currentProduct.id,
            channelId = onlineChannel.id,
            operationTypeId = transferOperation.id,
            code = "TRANSFER_PER_TRANSACTION",
            name = "Per-transfer limit",
            amount = "2500",
            maximum = "5000",
            kind = LimitKind.PER_TRANSACTION,
            period = null,
        )
        val transferDaily = createLimit(
            tenantId = tenantId,
            ownerProfileId = owner.id,
            customerProfileId = customer.id,
            delegationId = delegation.id,
            mandateId = mandate.id,
            productId = currentProduct.id,
            channelId = onlineChannel.id,
            operationTypeId = transferOperation.id,
            code = "TRANSFER_DAILY",
            name = "Daily bank transfers",
            amount = "5000",
            maximum = "10000",
            kind = LimitKind.PERIODIC,
            period = LimitPeriod.DAILY,
        )
        // Bound after the bootstrap rules exist, so they judge only the customer's own changes.
        seedConfigurationPolicies(
            tenantId,
            cardLimit.configurationId,
            atmLimit.configurationId,
            transferDaily.configurationId,
        )

        return CoreManifest(
            tenantId = tenantId,
            ownerProfileId = owner.id,
            customerProfileId = customer.id,
            delegationId = delegation.id,
            mandateId = mandate.id,
            currentFundingSourceId = currentSource.id,
            savingsFundingSourceId = savingsSource.id,
            cardLimit = cardLimit,
            atmLimit = atmLimit,
            transferPerTransactionLimit = transferPerTransaction,
            transferDailyLimit = transferDaily,
        )
    }

    private fun seedLimitChangePolicies(tenantId: UUID) {
        limitguard.tenantCatalogService.createLimitChangePolicy(
            CreateLimitChangePolicyCommand(
                tenantId = tenantId,
                code = "DENY_BELOW_CURRENT_EXPOSURE",
                name = "Protect current usage",
                phase = LimitChangePolicyPhase.AUTHORIZATION,
                engine = LimitChangePolicyEngine.JSON_LOGIC,
                decision = LimitChangePolicyDecision.DENY,
                priority = 10,
                conditionExpression = mapOf(
                    "decimal_lt" to listOf(
                        mapOf("var" to "changeRequest.requestedRule.amount"),
                        mapOf("var" to "facts.currentExposure"),
                    ),
                ),
                denialCode = "LIMIT_BELOW_CURRENT_EXPOSURE",
                denialMessage = "The new limit cannot be below current spending and reservations",
                metadata = mapOf("journey" to "SELF_SERVICE"),
            ),
        )
        limitguard.tenantCatalogService.createLimitChangePolicy(
            CreateLimitChangePolicyCommand(
                tenantId = tenantId,
                code = "ALLOW_DEMO_SELF_SERVICE",
                name = "Allow demo self-service changes",
                phase = LimitChangePolicyPhase.AUTHORIZATION,
                engine = LimitChangePolicyEngine.JSON_LOGIC,
                decision = LimitChangePolicyDecision.ALLOW,
                priority = 100,
                conditionExpression = mapOf(
                    "===" to listOf(
                        mapOf("var" to "facts.journey"),
                        "SELF_SERVICE",
                    ),
                ),
                metadata = mapOf("journey" to "SELF_SERVICE"),
            ),
        )
    }

    private fun seedConfigurationPolicies(
        tenantId: UUID,
        cardConfigurationId: UUID,
        atmConfigurationId: UUID,
        transferDailyConfigurationId: UUID,
    ) {
        val requestedAmount = mapOf("var" to "changeRequest.requestedRule.amount")
        limitguard.tenantCatalogService.createLimitChangePolicy(
            CreateLimitChangePolicyCommand(
                tenantId = tenantId,
                code = "PROXY_MAY_ONLY_LOWER",
                name = "Authorized users may only lower cash",
                description = "Only the account holder raises the cash limit; an authorized user may lower it.",
                phase = LimitChangePolicyPhase.AUTHORIZATION,
                engine = LimitChangePolicyEngine.JSON_LOGIC,
                decision = LimitChangePolicyDecision.DENY,
                limitConfigurationId = atmConfigurationId,
                priority = 20,
                conditionExpression = mapOf(
                    "and" to listOf(
                        mapOf(
                            "!=" to listOf(
                                mapOf("var" to "changeRequest.requestedBy.partyProfileId"),
                                mapOf("var" to "changeRequest.targetOwnerPartyProfileId"),
                            ),
                        ),
                        mapOf(
                            "or" to listOf(
                                mapOf("==" to listOf(mapOf("var" to "changeRequest.changeType"), "CREATE")),
                                mapOf(
                                    "decimal_gt" to listOf(
                                        requestedAmount,
                                        mapOf("var" to "changeRequest.beforeRule.amount"),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
                denialCode = "PROXY_MAY_ONLY_LOWER",
                denialMessage = "As an authorized user you can only lower the cash limit. Ask the account holder to raise it.",
                metadata = mapOf("journey" to "SELF_SERVICE"),
            ),
        )
        limitguard.tenantCatalogService.createLimitChangePolicy(
            CreateLimitChangePolicyCommand(
                tenantId = tenantId,
                code = "ABOVE_AGREED_MAXIMUM",
                name = "Holder approves transfers above the agreed maximum",
                description = "A daily transfer limit above the maximum agreed with the account holder waits for their approval.",
                phase = LimitChangePolicyPhase.AUTHORIZATION,
                engine = LimitChangePolicyEngine.JSON_LOGIC,
                decision = LimitChangePolicyDecision.REQUIRE_APPROVAL,
                limitConfigurationId = transferDailyConfigurationId,
                priority = 30,
                conditionExpression = mapOf(
                    "decimal_gt" to listOf(requestedAmount, AGREED_TRANSFER_MAXIMUM.toPlainString()),
                ),
                metadata = mapOf("journey" to "SELF_SERVICE"),
            ),
        )
        limitguard.tenantCatalogService.createLimitChangePolicy(
            CreateLimitChangePolicyCommand(
                tenantId = tenantId,
                code = "CARD_RAISE_COOLING_OFF",
                name = "Card limit raises wait out a cooling-off period",
                description = "A raised card limit starts at the cooling-off end the bank sends with the request.",
                phase = LimitChangePolicyPhase.AUTHORIZATION,
                engine = LimitChangePolicyEngine.JSON_LOGIC,
                decision = LimitChangePolicyDecision.ALLOW,
                limitConfigurationId = cardConfigurationId,
                priority = 40,
                conditionExpression = mapOf(
                    "and" to listOf(
                        mapOf("==" to listOf(mapOf("var" to "changeRequest.changeType"), "UPDATE")),
                        mapOf(
                            "decimal_gt" to listOf(
                                requestedAmount,
                                mapOf("var" to "changeRequest.beforeRule.amount"),
                            ),
                        ),
                    ),
                ),
                actionExpression = listOf(
                    mapOf("type" to "SET_EFFECTIVE_AT", "instant" to mapOf("var" to "facts.coolingOffEndsAt")),
                ),
                metadata = mapOf("journey" to "SELF_SERVICE"),
            ),
        )
    }

    private fun createLimit(
        tenantId: UUID,
        ownerProfileId: UUID,
        customerProfileId: UUID,
        delegationId: UUID,
        mandateId: UUID,
        productId: UUID,
        channelId: UUID,
        operationTypeId: UUID,
        code: String,
        name: String,
        amount: String,
        maximum: String,
        kind: LimitKind,
        period: LimitPeriod?,
    ): CoreLimit {
        val configuration = limitguard.tenantCatalogService.createLimitConfiguration(
            CreateLimitConfigurationCommand(
                tenantId = tenantId,
                code = code,
                name = name,
                description = "Customer-managed $name",
                limitKind = kind,
                period = period,
                currency = "EUR",
                maxAssignableAmount = BigDecimal(maximum),
                defaultAmount = BigDecimal(amount),
                fundingScope = LimitConfigurationFundingScope.FUNDING_SOURCE_PRODUCT,
                fundingSourceProductId = productId,
                channelScope = LimitChannelScope.CHANNEL,
                channelId = channelId,
                operationTypeId = operationTypeId,
                priority = 100,
                metadata = mapOf("journey" to "SELF_SERVICE"),
            ),
        )
        val request = limitguard.limitChangeService.requestLimitRuleChange(
            RequestLimitRuleChangeCommand(
                tenantId = tenantId,
                changeType = LimitRuleChangeType.CREATE,
                requestedBy = LimitChangeActorCommand(
                    actorType = LimitChangeActorType.PARTY_PROFILE,
                    partyProfileId = customerProfileId,
                ),
                targetOwnerPartyProfileId = ownerProfileId,
                requesterPartyProfileId = customerProfileId,
                authorityDelegationId = delegationId,
                requestChannelId = channelId,
                targetMandateId = mandateId,
                requestedRule = LimitRuleDraft(
                    name = name,
                    description = "Customer-managed $name",
                    limitKind = kind,
                    period = period,
                    amount = BigDecimal(amount),
                    currency = "EUR",
                    mandateScope = LimitMandateScope.MANDATE,
                    mandateId = mandateId,
                    configurationId = configuration.id,
                    channelScope = LimitChannelScope.CHANNEL,
                    channelId = channelId,
                    operationTypeId = operationTypeId,
                    usageConsumptionMode = LimitUsageConsumptionMode.CASCADING,
                    priority = 100,
                    metadata = mapOf("journey" to "SELF_SERVICE"),
                ),
                policyContext = mapOf(
                    "journey" to "SELF_SERVICE",
                    "currentExposure" to "0.0000",
                ),
                idempotencyKey = "demo-bootstrap-$code",
                metadata = mapOf("journey" to "SELF_SERVICE"),
            ),
        )
        check(request.status == LimitRuleChangeStatus.APPLIED) {
            "Demo limit $code was not applied: ${request.status}"
        }
        return CoreLimit(
            ruleId = requireNotNull(request.limitRuleId),
            configurationId = configuration.id,
            operationTypeId = operationTypeId,
            channelId = channelId,
            maximum = BigDecimal(maximum),
        )
    }

    private fun createAccounts(
        scenarioId: UUID,
        manifest: CoreManifest,
        now: Instant,
    ): SeededAccounts {
        val current = DemoAccount(
            id = UUID.randomUUID(),
            scenarioId = scenarioId,
            fundingSourceId = manifest.currentFundingSourceId,
            ownerPartyProfileId = manifest.ownerProfileId,
            accountType = AccountType.CURRENT,
            displayName = "Household current",
            maskedIban = "IE00 WREN 9000 •••• 2048",
            accessLabel = "Authorized user",
            currency = "EUR",
            openingBalance = BigDecimal("18420.40"),
            displayOrder = 1,
            createdAt = now,
        )
        val savings = DemoAccount(
            id = UUID.randomUUID(),
            scenarioId = scenarioId,
            fundingSourceId = manifest.savingsFundingSourceId,
            ownerPartyProfileId = manifest.customerProfileId,
            accountType = AccountType.SAVINGS,
            displayName = "Personal savings",
            maskedIban = "IE00 WREN 9000 •••• 7712",
            accessLabel = "Personal",
            currency = "EUR",
            openingBalance = BigDecimal("18250.00"),
            displayOrder = 2,
            createdAt = now,
        )
        return SeededAccounts(current, savings)
    }

    private fun createCard(scenarioId: UUID, accountId: UUID, now: Instant): DemoCard =
        DemoCard(
            id = UUID.randomUUID(),
            scenarioId = scenarioId,
            accountId = accountId,
            displayName = "Everyday Debit",
            lastFour = "4821",
            expiresMonth = 8,
            expiresYear = 2029,
            status = CardStatus.ACTIVE,
            network = "Debit",
            createdAt = now,
        )

    private fun createLimitSettings(
        scenarioId: UUID,
        manifest: CoreManifest,
        now: Instant,
    ): List<DemoLimitSetting> =
        listOf(
            setting(scenarioId, DemoOperation.CARD_PURCHASE, "Card purchases", manifest.cardLimit, 1, now),
            setting(scenarioId, DemoOperation.ATM_WITHDRAWAL, "ATM withdrawals", manifest.atmLimit, 2, now),
            setting(
                scenarioId,
                DemoOperation.BANK_TRANSFER,
                "Per transfer",
                manifest.transferPerTransactionLimit,
                3,
                now,
            ),
            setting(
                scenarioId,
                DemoOperation.BANK_TRANSFER,
                "Daily transfers",
                manifest.transferDailyLimit,
                4,
                now,
                agreedMaximum = AGREED_TRANSFER_MAXIMUM,
            ),
        )

    private fun setting(
        scenarioId: UUID,
        operation: DemoOperation,
        displayName: String,
        limit: CoreLimit,
        displayOrder: Int,
        now: Instant,
        agreedMaximum: BigDecimal? = null,
    ): DemoLimitSetting =
        DemoLimitSetting(
            id = UUID.randomUUID(),
            scenarioId = scenarioId,
            operation = operation,
            displayName = displayName,
            limitRuleId = limit.ruleId,
            limitConfigurationId = limit.configurationId,
            operationTypeId = limit.operationTypeId,
            channelId = limit.channelId,
            minimumAmount = BigDecimal("50"),
            maximumAmount = limit.maximum,
            stepAmount = BigDecimal("50"),
            agreedMaximumAmount = agreedMaximum,
            pendingChangeRequestId = null,
            pendingChangeAmount = null,
            pendingChangeEffectiveAt = null,
            displayOrder = displayOrder,
            createdAt = now,
        )

    private data class CoreLimit(
        val ruleId: UUID,
        val configurationId: UUID,
        val operationTypeId: UUID,
        val channelId: UUID,
        val maximum: BigDecimal,
    )

    private data class CoreManifest(
        val tenantId: UUID,
        val ownerProfileId: UUID,
        val customerProfileId: UUID,
        val delegationId: UUID,
        val mandateId: UUID,
        val currentFundingSourceId: UUID,
        val savingsFundingSourceId: UUID,
        val cardLimit: CoreLimit,
        val atmLimit: CoreLimit,
        val transferPerTransactionLimit: CoreLimit,
        val transferDailyLimit: CoreLimit,
    )

    private data class SeededAccounts(
        val current: DemoAccount,
        val savings: DemoAccount,
    ) {
        val all: List<DemoAccount> = listOf(current, savings)
    }
}

@Service
@Order(Ordered.HIGHEST_PRECEDENCE)
class DemoScenarioInitializer(
    private val scenarioService: DemoScenarioService,
    private val activitySeeder: DemoActivitySeeder,
) : ApplicationRunner {
    override fun run(args: ApplicationArguments) {
        scenarioService.ensureInitialized()
        activitySeeder.seed()
    }
}
