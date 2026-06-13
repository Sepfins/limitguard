package io.github.sepfins.limitguard.api

enum class PartyType {
    INDIVIDUAL,
    LEGAL_ENTITY,
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

enum class AuthorizationDecision {
    ALLOWED,
    DENIED,
}

enum class TransactionStatus {
    AUTHORIZED,
    CAPTURED,
    PARTIALLY_CAPTURED,
    VOIDED,
    REVERSED,
    EXPIRED,
}

enum class LimitUsageEventType {
    RESERVE,
    CONSUME,
    RELEASE,
    REVERSAL,
    ADJUSTMENT,
}
