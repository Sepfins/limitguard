package io.github.sepfins.limitguard.api

enum class PartyType {
    INDIVIDUAL,
    LEGAL_ENTITY,
}

enum class PartyProfileDelegationType {
    GUARDIAN,
    LEGAL_REPRESENTATIVE,
    DELEGATE,
    AUTHORIZED_USER,
}

object EntityIdentifierTypes {
    const val PARTY_PROFILE: String = "PARTY_PROFILE"
    const val FUNDING_SOURCE: String = "FUNDING_SOURCE"
}

enum class FundingSourceCategory {
    TRANSACTIONAL,
    SAVINGS,
    TERM_DEPOSIT,
    PREPAID,
    CREDIT,
    SECURITIES,
    EWALLET,
}

enum class ProductNodeType {
    FAMILY,
    SUBFAMILY,
    PRODUCT,
}

enum class FundingSourceOwnerRole {
    PRIMARY_OWNER,
    JOINT_OWNER,
    BENEFICIAL_OWNER,
}

enum class LimitKind {
    PER_TRANSACTION,
    PERIODIC,
}

enum class LimitPeriod {
    DAILY,
    MONTHLY,
}

enum class LimitConfigurationFundingScope {
    ALL_FUNDING_SOURCES,
    FUNDING_SOURCE_CATEGORY,
    FUNDING_SOURCE_PRODUCT,
}

enum class LimitFundingScope {
    ALL_OWNER_SOURCES,
    FUNDING_SOURCE,
    FUNDING_SOURCE_GROUP,
}

enum class LimitChannelScope {
    ALL_CHANNELS,
    CHANNEL,
    CHANNEL_GROUP,
}

enum class LimitMandateScope {
    ALL_OWNER_MANDATES,
    MANDATE,
}

enum class LimitUsageConsumptionMode {
    CASCADING,
    ISOLATED,
}

enum class LimitChangeActorType {
    PARTY_PROFILE,
    BANK_WORKER,
    SYSTEM,
}

enum class LimitRuleChangeType {
    CREATE,
    UPDATE,
    DEACTIVATE,
    REACTIVATE,
    DELETE,
}

enum class LimitRuleChangeStatus {
    REQUESTED,
    APPROVED,
    PENDING_EFFECTIVE_DATE,
    APPLIED,
    REJECTED,
    CANCELLED,
    SUPERSEDED,
    FAILED,
}

enum class LimitRuleChangeEventType {
    REQUESTED,
    POLICY_EVALUATED,
    APPROVED,
    SCHEDULED,
    APPLIED,
    REJECTED,
    CANCELLED,
    SUPERSEDED,
    FAILED,
}

enum class LimitRuleRevisionEventType {
    CREATED,
    UPDATED,
    DEACTIVATED,
    REACTIVATED,
    DELETED,
}

enum class LimitChangePolicyPhase {
    REQUEST_VALIDATION,
    AUTHORIZATION,
    DEFAULTING,
    SCHEDULING,
    CONFLICT_RESOLUTION,
    PRE_APPLY,
    POST_APPLY,
}

enum class LimitChangePolicyEngine {
    JSON_LOGIC,
    CEL,
    SQL_FUNCTION,
    EXTERNAL_DECISION,
}

enum class LimitChangePolicyDecision {
    ALLOW,
    DENY,
    MUTATE_REQUEST,
    SCHEDULE_EFFECTIVE_AT,
    CANCEL_PENDING,
    CREATE_DEFAULT,
    REQUIRE_APPROVAL,
    NO_OP,
}

enum class AuthorizationDecision {
    ALLOWED,
    DENIED,
    NOT_APPLICABLE,
}

enum class NoMatchingRuleBehavior {
    ALLOW,
    DENY,
    NOT_APPLICABLE,
}

enum class TransactionStatus {
    AUTHORIZED,
    CAPTURED,
    PARTIALLY_CAPTURED,
    VOIDED,
    REVERSED,
    EXPIRED,
}

enum class TransactionEventType {
    AUTHORIZATION,
    CAPTURE,
    PARTIAL_CAPTURE,
    VOID,
    REVERSAL,
    EXPIRY,
    ADJUSTMENT,
}

enum class LimitUsageEventType {
    RESERVE,
    CONSUME,
    RELEASE,
    REVERSAL,
    ADJUSTMENT,
}

enum class OperationCapacityStatus {
    AVAILABLE,
    EXHAUSTED,
    UNLIMITED,
    UNAVAILABLE,
    NOT_APPLICABLE,
}
