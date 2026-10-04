package io.github.sepfins.limitguard.api

import java.util.UUID

/**
 * A host assertion that this invocation is authorized as staff or internal SYSTEM.
 * It is not a credential. Construct it after host permission checks, never by
 * deserializing customer input. Core still checks financial-domain eligibility.
 */
sealed interface TrustedChangeActorContext {
    data class BankWorker(val bankWorkerId: UUID) : TrustedChangeActorContext

    data object InternalSystem : TrustedChangeActorContext
}
