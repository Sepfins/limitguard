package io.github.sepfins.limitguard.application.change

import io.github.sepfins.limitguard.api.LimitChangeService
import io.github.sepfins.limitguard.api.TrustedChangeActorContext
import io.github.sepfins.limitguard.api.commands.ApplyDueLimitChangesCommand
import io.github.sepfins.limitguard.api.commands.ApproveLimitRuleChangeCommand
import io.github.sepfins.limitguard.api.commands.CancelLimitRuleChangeCommand
import io.github.sepfins.limitguard.api.commands.RejectLimitRuleChangeCommand
import io.github.sepfins.limitguard.api.commands.RelayLimitChangeEventsCommand
import io.github.sepfins.limitguard.api.commands.RequestLimitRuleChangeCommand
import io.github.sepfins.limitguard.api.results.ApplyDueChangesResult
import io.github.sepfins.limitguard.api.results.LimitChangeRequestResult
import io.github.sepfins.limitguard.internal.json.JsonSnapshots

import io.github.sepfins.limitguard.port.ids.IdGenerator
import io.github.sepfins.limitguard.port.persistence.LimitChangeEventOutbox
import io.github.sepfins.limitguard.port.persistence.LimitChangePolicyEvaluationRepository
import io.github.sepfins.limitguard.port.persistence.LimitChangePolicyRepository
import io.github.sepfins.limitguard.port.persistence.LimitChangeRequestRepository
import io.github.sepfins.limitguard.port.persistence.LimitConfigurationRepository
import io.github.sepfins.limitguard.port.persistence.LimitRuleChangeEventRepository
import io.github.sepfins.limitguard.port.persistence.LimitRuleRepository
import io.github.sepfins.limitguard.port.persistence.LimitRuleRevisionRepository
import io.github.sepfins.limitguard.port.persistence.MandateRepository
import io.github.sepfins.limitguard.port.persistence.TenantRepository
import io.github.sepfins.limitguard.port.persistence.PartyProfileRepository
import io.github.sepfins.limitguard.port.persistence.PartyProfileDelegationRepository
import io.github.sepfins.limitguard.port.persistence.ChannelRepository
import io.github.sepfins.limitguard.port.policy.PolicyEngine
import io.github.sepfins.limitguard.port.time.ClockProvider
import io.github.sepfins.limitguard.port.transactions.TransactionRunner

internal class DefaultLimitChangeService(
    changeRequestRepository: LimitChangeRequestRepository,
    storedChangeEventRepository: LimitRuleChangeEventRepository,
    changeEventOutbox: LimitChangeEventOutbox,
    relayChangeEvents: Boolean,
    limitRuleRepository: LimitRuleRepository,
    limitRuleRevisionRepository: LimitRuleRevisionRepository,
    limitConfigurationRepository: LimitConfigurationRepository,
    mandateRepository: MandateRepository,
    tenantRepository: TenantRepository,
    partyProfileRepository: PartyProfileRepository,
    partyProfileDelegationRepository: PartyProfileDelegationRepository,
    channelRepository: ChannelRepository,
    policyRepository: LimitChangePolicyRepository,
    policyEvaluationRepository: LimitChangePolicyEvaluationRepository,
    policyEngines: Collection<PolicyEngine>,
    idGenerator: IdGenerator,
    clockProvider: ClockProvider,
    transactionRunner: TransactionRunner,
) : LimitChangeService {
    // Only the recorder sees the relaying repository; switching it off stops enqueueing.
    private val transitions = LimitChangeTransitionRecorder(
        requests = changeRequestRepository,
        events = if (relayChangeEvents) {
            RelayingLimitRuleChangeEventRepository(storedChangeEventRepository, changeEventOutbox)
        } else {
            storedChangeEventRepository
        },
        idGenerator = idGenerator,
    )
    private val mandates = LimitRuleMandateValidator(mandateRepository)
    private val candidates = LimitRuleCandidateFactory()
    private val locks = LimitChangeLocks(limitConfigurationRepository, changeRequestRepository, limitRuleRepository)
    private val eligibility = LimitChangeEligibilityValidator(
        tenants = tenantRepository,
        profiles = partyProfileRepository,
        delegations = partyProfileDelegationRepository,
        channels = channelRepository,
        mandates = mandateRepository,
    )
    private val application = LimitChangeApplicationWorkflow(
        transitions = transitions,
        limitRuleRepository = limitRuleRepository,
        limitRuleRevisionRepository = limitRuleRevisionRepository,
        candidateFactory = candidates,
        mandateValidator = mandates,
        eligibility = eligibility,
        idGenerator = idGenerator,
    )
    private val requestWorkflow = RequestLimitRuleChangeWorkflow(
        commandValidator = ChangeCommandValidator(),
        referenceResolver = ChangeReferenceResolver(limitRuleRevisionRepository),
        candidateFactory = candidates,
        mandateValidator = mandates,
        eligibility = eligibility,
        changeRequestFactory = LimitChangeRequestFactory(),
        transitions = transitions,
        policyRunner = LimitChangePolicyRunner(
            policyRepository = policyRepository,
            evaluationRepository = policyEvaluationRepository,
            transitions = transitions,
            engineRegistry = PolicyEngineRegistry(policyEngines),
            idGenerator = idGenerator,
        ),
        applicationWorkflow = application,
        changeRequestRepository = changeRequestRepository,
        changeEventRepository = storedChangeEventRepository,
        locks = locks,
        idGenerator = idGenerator,
        clockProvider = clockProvider,
        transactionRunner = transactionRunner,
    )
    private val approveWorkflow = ApproveLimitRuleChangeWorkflow(
        eligibility = eligibility,
        applicationWorkflow = application,
        locks = locks,
        clockProvider = clockProvider,
        transactionRunner = transactionRunner,
    )
    private val rejectWorkflow = RejectLimitRuleChangeWorkflow(
        eligibility = eligibility,
        transitions = transitions,
        locks = locks,
        clockProvider = clockProvider,
        transactionRunner = transactionRunner,
    )
    private val cancelWorkflow = CancelLimitRuleChangeWorkflow(
        eligibility = eligibility,
        transitions = transitions,
        locks = locks,
        clockProvider = clockProvider,
        transactionRunner = transactionRunner,
    )
    private val applyDueWorkflow = ApplyDueLimitChangesWorkflow(
        eligibility = eligibility,
        applicationWorkflow = application,
        transitions = transitions,
        changeRequestRepository = changeRequestRepository,
        locks = locks,
        clockProvider = clockProvider,
        transactionRunner = transactionRunner,
    )
    private val relayWorkflow = RelayLimitChangeEventsWorkflow(
        outbox = changeEventOutbox,
        changeEventRepository = storedChangeEventRepository,
        changeRequestRepository = changeRequestRepository,
        limitRuleRevisionRepository = limitRuleRevisionRepository,
        transactionRunner = transactionRunner,
    )

    override fun requestLimitRuleChange(
        command: RequestLimitRuleChangeCommand,
        trustedActorContext: TrustedChangeActorContext?,
    ): LimitChangeRequestResult =
        requestWorkflow.execute(command.copy(
            metadata = JsonSnapshots.objectValue(command.metadata),
            policyContext = JsonSnapshots.objectValue(command.policyContext, "policyContext"),
        ), trustedActorContext)

    override fun approveLimitRuleChange(
        command: ApproveLimitRuleChangeCommand,
        trustedActorContext: TrustedChangeActorContext?,
    ): LimitChangeRequestResult {
        val detached = command.copy(metadata = JsonSnapshots.objectValue(command.metadata))
        return approveWorkflow.execute(detached, trustedActorContext)
    }

    override fun rejectLimitRuleChange(
        command: RejectLimitRuleChangeCommand,
        trustedActorContext: TrustedChangeActorContext?,
    ): LimitChangeRequestResult {
        val detached = command.copy(metadata = JsonSnapshots.objectValue(command.metadata))
        return rejectWorkflow.execute(detached, trustedActorContext)
    }

    override fun cancelLimitRuleChange(
        command: CancelLimitRuleChangeCommand,
        trustedActorContext: TrustedChangeActorContext?,
    ): LimitChangeRequestResult {
        val detached = command.copy(metadata = JsonSnapshots.objectValue(command.metadata))
        return cancelWorkflow.execute(detached, trustedActorContext)
    }

    override fun applyDueLimitChanges(
        command: ApplyDueLimitChangesCommand,
        trustedActorContext: TrustedChangeActorContext.InternalSystem,
    ): ApplyDueChangesResult =
        applyDueWorkflow.execute(command)

    override fun relayLimitChangeEvents(
        command: RelayLimitChangeEventsCommand,
        trustedActorContext: TrustedChangeActorContext.InternalSystem,
    ): Int =
        relayWorkflow.execute(command)
}
