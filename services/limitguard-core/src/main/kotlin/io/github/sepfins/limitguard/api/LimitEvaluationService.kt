package io.github.sepfins.limitguard.api

import io.github.sepfins.limitguard.api.commands.AuthorizeTransactionCommand
import io.github.sepfins.limitguard.api.commands.CaptureTransactionCommand
import io.github.sepfins.limitguard.api.commands.ExpireDueAuthorizationsCommand
import io.github.sepfins.limitguard.api.commands.ReleaseAuthorizationCommand
import io.github.sepfins.limitguard.api.commands.ReverseTransactionCommand
import io.github.sepfins.limitguard.api.results.AuthorizationResult
import io.github.sepfins.limitguard.api.results.ExpireDueAuthorizationsResult
import io.github.sepfins.limitguard.api.results.UsageMutationResult

interface LimitEvaluationService {
    fun authorizeTransaction(command: AuthorizeTransactionCommand): AuthorizationResult

    fun captureTransaction(command: CaptureTransactionCommand): UsageMutationResult

    fun releaseAuthorization(command: ReleaseAuthorizationCommand): UsageMutationResult

    fun reverseTransaction(command: ReverseTransactionCommand): UsageMutationResult

    fun expireDueAuthorizations(command: ExpireDueAuthorizationsCommand): ExpireDueAuthorizationsResult
}
