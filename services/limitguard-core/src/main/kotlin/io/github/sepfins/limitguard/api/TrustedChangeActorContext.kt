package io.github.sepfins.limitguard.api

/**
 * A host assertion that this invocation is authorized as internal SYSTEM.
 * It is not a credential. Construct it after host permission checks, never by
 * deserializing customer input. Core still checks financial-domain eligibility.
 */
sealed interface TrustedChangeActorContext {
    data object InternalSystem : TrustedChangeActorContext
}
