package io.github.sepfins.limitguard.application.change

import io.github.sepfins.limitguard.api.LimitChangeService
import io.github.sepfins.limitguard.api.commands.ApplyDueLimitChangesCommand
import io.github.sepfins.limitguard.api.commands.ApproveLimitRuleChangeCommand
import io.github.sepfins.limitguard.api.commands.CancelLimitRuleChangeCommand
import io.github.sepfins.limitguard.api.commands.RejectLimitRuleChangeCommand
import io.github.sepfins.limitguard.api.commands.RequestLimitRuleChangeCommand
import io.github.sepfins.limitguard.api.commands.LimitRuleDraft
import io.github.sepfins.limitguard.api.results.ApplyDueChangesResult
import io.github.sepfins.limitguard.api.results.LimitChangeRequestResult
import io.github.sepfins.limitguard.internal.json.JsonSnapshots

import io.github.sepfins.limitguard.port.ids.IdGenerator
import io.github.sepfins.limitguard.port.persistence.LimitChangePolicyEvaluationRepository
import io.github.sepfins.limitguard.port.persistence.LimitChangePolicyRepository
import io.github.sepfins.limitguard.port.persistence.LimitChangeRequestRepository
import io.github.sepfins.limitguard.port.persistence.LimitConfigurationRepository
import io.github.sepfins.limitguard.port.persistence.LimitRuleChangeEventRepository
import io.github.sepfins.limitguard.port.persistence.LimitRuleRepository
import io.github.sepfins.limitguard.port.persistence.LimitRuleRevisionRepository
import io.github.sepfins.limitguard.port.persistence.MandateRepository
import io.github.sepfins.limitguard.port.policy.PolicyEngine
import io.github.sepfins.limitguard.port.time.ClockProvider
import io.github.sepfins.limitguard.port.transactions.TransactionRunner

internal class DefaultLimitChangeService(
    changeRequestRepository: LimitChangeRequestRepository,
    changeEventRepository: LimitRuleChangeEventRepository,
    limitRuleRepository: LimitRuleRepository,
    limitRuleRevisionRepository: LimitRuleRevisionRepository,
    limitConfigurationRepository: LimitConfigurationRepository,
    mandateRepository: MandateRepository,
    policyRepository: LimitChangePolicyRepository,
    policyEvaluationRepository: LimitChangePolicyEvaluationRepository,
    policyEngines: Collection<PolicyEngine>,
    idGenerator: IdGenerator,
    clockProvider: ClockProvider,
    transactionRunner: TransactionRunner,
) : LimitChangeService {
    private val events = LimitChangeEventFactory(idGenerator)
    private val mandates = LimitRuleMandateValidator(mandateRepository)
    private val candidates = LimitRuleCandidateFactory()
    private val targets = LimitChangeTargets(limitConfigurationRepository, limitRuleRepository, changeRequestRepository)
    private val locks = LimitChangeLocks(targets, changeRequestRepository, limitRuleRepository)
    private val application = LimitChangeApplicationWorkflow(
        changeRequestRepository = changeRequestRepository,
        changeEventRepository = changeEventRepository,
        limitRuleRepository = limitRuleRepository,
        limitRuleRevisionRepository = limitRuleRevisionRepository,
        changeEventFactory = events,
        targets = targets,
        idGenerator = idGenerator,
    )
    private val requestWorkflow = RequestLimitRuleChangeWorkflow(
        commandValidator = ChangeCommandValidator(),
        referenceResolver = ChangeReferenceResolver(limitConfigurationRepository, limitRuleRepository, limitRuleRevisionRepository),
        candidateFactory = candidates,
        mandateValidator = mandates,
        changeRequestFactory = LimitChangeRequestFactory(),
        changeEventFactory = events,
        policyRunner = LimitChangePolicyRunner(
            policyRepository = policyRepository,
            evaluationRepository = policyEvaluationRepository,
            changeEventRepository = changeEventRepository,
            engineRegistry = PolicyEngineRegistry(policyEngines),
            changeEventFactory = events,
            idGenerator = idGenerator,
        ),
        applicationWorkflow = application,
        changeRequestRepository = changeRequestRepository,
        changeEventRepository = changeEventRepository,
        targets = targets,
        locks = locks,
        idGenerator = idGenerator,
        clockProvider = clockProvider,
        transactionRunner = transactionRunner,
    )
    private val approveWorkflow = ApproveLimitRuleChangeWorkflow(
        candidateFactory = candidates,
        mandateValidator = mandates,
        applicationWorkflow = application,
        targets = targets,
        locks = locks,
        changeRequestRepository = changeRequestRepository,
        limitConfigurationRepository = limitConfigurationRepository,
        limitRuleRepository = limitRuleRepository,
        clockProvider = clockProvider,
        transactionRunner = transactionRunner,
    )
    private val rejectWorkflow = RejectLimitRuleChangeWorkflow(
        changeEventFactory = events,
        changeRequestRepository = changeRequestRepository,
        changeEventRepository = changeEventRepository,
        locks = locks,
        clockProvider = clockProvider,
        transactionRunner = transactionRunner,
    )
    private val cancelWorkflow = CancelLimitRuleChangeWorkflow(
        changeEventFactory = events,
        changeRequestRepository = changeRequestRepository,
        changeEventRepository = changeEventRepository,
        locks = locks,
        clockProvider = clockProvider,
        transactionRunner = transactionRunner,
    )
    private val applyDueWorkflow = ApplyDueLimitChangesWorkflow(
        applicationWorkflow = application,
        mandateValidator = mandates,
        changeEventFactory = events,
        changeRequestRepository = changeRequestRepository,
        changeEventRepository = changeEventRepository,
        limitConfigurationRepository = limitConfigurationRepository,
        limitRuleRepository = limitRuleRepository,
        locks = locks,
        clockProvider = clockProvider,
        transactionRunner = transactionRunner,
    )

    override fun requestLimitRuleChange(command: RequestLimitRuleChangeCommand): LimitChangeRequestResult =
        requestWorkflow.execute(command.copy(
            metadata = JsonSnapshots.objectValue(command.metadata),
            policyContext = JsonSnapshots.objectValue(command.policyContext, "policyContext"),
            requestedRule = command.requestedRule?.detachedJson("requestedRule"),
        ))

    override fun approveLimitRuleChange(command: ApproveLimitRuleChangeCommand): LimitChangeRequestResult =
        approveWorkflow.execute(command.copy(
            metadata = JsonSnapshots.objectValue(command.metadata),
            approvedRule = command.approvedRule?.detachedJson("approvedRule"),
        ))

    override fun rejectLimitRuleChange(command: RejectLimitRuleChangeCommand): LimitChangeRequestResult =
        rejectWorkflow.execute(command.copy(metadata = JsonSnapshots.objectValue(command.metadata)))

    override fun cancelLimitRuleChange(command: CancelLimitRuleChangeCommand): LimitChangeRequestResult =
        cancelWorkflow.execute(command.copy(metadata = JsonSnapshots.objectValue(command.metadata)))

    override fun applyDueLimitChanges(command: ApplyDueLimitChangesCommand): ApplyDueChangesResult =
        applyDueWorkflow.execute(command)

    private fun LimitRuleDraft.detachedJson(field: String): LimitRuleDraft =
        copy(metadata = JsonSnapshots.objectValue(metadata, "$field.metadata"))
}
