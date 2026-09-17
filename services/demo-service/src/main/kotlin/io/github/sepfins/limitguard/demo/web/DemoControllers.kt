package io.github.sepfins.limitguard.demo.web

import io.github.sepfins.limitguard.demo.application.CustomerBankingService
import io.github.sepfins.limitguard.demo.application.DemoEventStream
import io.github.sepfins.limitguard.demo.application.LimitManagementService
import io.github.sepfins.limitguard.demo.application.PaymentProcessingService
import io.github.sepfins.limitguard.demo.domain.DemoOperation
import io.github.sepfins.limitguard.demo.web.dto.AccountResponse
import io.github.sepfins.limitguard.demo.web.dto.CardResponse
import io.github.sepfins.limitguard.demo.web.dto.LimitChangeRejectionRequest
import io.github.sepfins.limitguard.demo.web.dto.LimitResponse
import io.github.sepfins.limitguard.demo.web.dto.LimitUpdateRequest
import io.github.sepfins.limitguard.demo.web.dto.LimitUpdateResponse
import io.github.sepfins.limitguard.demo.web.dto.OverviewResponse
import io.github.sepfins.limitguard.demo.web.dto.PaymentAuthorizationRequest
import io.github.sepfins.limitguard.demo.web.dto.PaymentAuthorizationResponse
import io.github.sepfins.limitguard.demo.web.dto.PaymentDetailResponse
import io.github.sepfins.limitguard.demo.web.dto.PaymentMutationRequest
import io.github.sepfins.limitguard.demo.web.dto.PaymentMutationResponse
import io.github.sepfins.limitguard.demo.web.dto.PaymentSummaryResponse
import io.github.sepfins.limitguard.demo.web.dto.ScenarioResponse
import jakarta.validation.Valid
import org.springframework.http.MediaType
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.util.UUID

const val DEMO_API_BASE: String = "/limitguard/demo/api/v1"

@RestController
@RequestMapping(DEMO_API_BASE)
class ScenarioController(
    private val bankingService: CustomerBankingService,
) {
    @GetMapping("/scenario")
    fun scenario(): ScenarioResponse = bankingService.scenario()
}

@RestController
@RequestMapping("$DEMO_API_BASE/customer")
class CustomerController(
    private val bankingService: CustomerBankingService,
    private val limitManagementService: LimitManagementService,
    private val eventStream: DemoEventStream,
) {
    @GetMapping("/overview")
    fun overview(): OverviewResponse = bankingService.overview()

    @GetMapping("/accounts")
    fun accounts(): List<AccountResponse> = bankingService.accounts()

    @GetMapping("/accounts/{accountId}")
    fun account(@PathVariable accountId: UUID): AccountResponse = bankingService.account(accountId)

    @GetMapping("/cards")
    fun cards(): List<CardResponse> = bankingService.cards()

    @GetMapping("/cards/{cardId}")
    fun card(@PathVariable cardId: UUID): CardResponse = bankingService.card(cardId)

    @GetMapping("/payments")
    fun payments(@RequestParam(required = false) operation: DemoOperation?): List<PaymentSummaryResponse> =
        bankingService.payments(operation)

    @GetMapping("/payments/{externalPaymentId}")
    fun payment(@PathVariable externalPaymentId: String): PaymentDetailResponse =
        bankingService.payment(externalPaymentId)

    @GetMapping("/limits")
    fun limits(): List<LimitResponse> = bankingService.limits()

    @PatchMapping("/limits/{limitId}")
    fun updateLimit(
        @PathVariable limitId: UUID,
        @Valid @RequestBody request: LimitUpdateRequest,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
    ): LimitUpdateResponse =
        limitManagementService.update(limitId, request.amount, idempotencyKey)

    @DeleteMapping("/limits/{limitId}/pending-change")
    fun withdrawLimitChange(@PathVariable limitId: UUID): LimitUpdateResponse =
        limitManagementService.withdraw(limitId)

    @GetMapping("/events", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun events(): SseEmitter = eventStream.connect()
}

@RestController
@RequestMapping("$DEMO_API_BASE/integrations")
class PaymentIntegrationController(
    private val paymentProcessingService: PaymentProcessingService,
) {
    @PostMapping("/payment-authorizations")
    fun authorize(
        @Valid @RequestBody request: PaymentAuthorizationRequest,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
    ): PaymentAuthorizationResponse =
        paymentProcessingService.authorize(request, idempotencyKey)

    @PostMapping("/payments/{externalPaymentId}/captures")
    fun capture(
        @PathVariable externalPaymentId: String,
        @Valid @RequestBody request: PaymentMutationRequest,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
    ): PaymentMutationResponse =
        paymentProcessingService.capture(externalPaymentId, request, idempotencyKey)

    @PostMapping("/payments/{externalPaymentId}/releases")
    fun release(
        @PathVariable externalPaymentId: String,
        @Valid @RequestBody request: PaymentMutationRequest,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
    ): PaymentMutationResponse =
        paymentProcessingService.release(externalPaymentId, request, idempotencyKey)

    @PostMapping("/payments/{externalPaymentId}/reversals")
    fun reverse(
        @PathVariable externalPaymentId: String,
        @Valid @RequestBody request: PaymentMutationRequest,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
    ): PaymentMutationResponse =
        paymentProcessingService.reverse(externalPaymentId, request, idempotencyKey)
}

@RestController
@RequestMapping("$DEMO_API_BASE/integrations/limit-changes")
class LimitApprovalIntegrationController(
    private val limitManagementService: LimitManagementService,
) {
    @PostMapping("/{changeRequestId}/approval")
    fun approve(@PathVariable changeRequestId: UUID): LimitUpdateResponse =
        limitManagementService.approve(changeRequestId)

    @PostMapping("/{changeRequestId}/rejection")
    fun reject(
        @PathVariable changeRequestId: UUID,
        @Valid @RequestBody request: LimitChangeRejectionRequest,
    ): LimitUpdateResponse =
        limitManagementService.reject(changeRequestId, request.reason)
}

@Controller
class DemoSpaController {
    @GetMapping("/")
    fun home(): String = "redirect:/limitguard/demo/"

    @GetMapping(
        "/limitguard/demo",
        "/limitguard/demo/",
        "/limitguard/demo/home",
        "/limitguard/demo/accounts",
        "/limitguard/demo/accounts/{accountId}",
        "/limitguard/demo/cards",
        "/limitguard/demo/cards/{cardId}",
        "/limitguard/demo/activity",
        "/limitguard/demo/activity/{externalPaymentId}",
        "/limitguard/demo/limits",
    )
    fun demoApplication(): String = "forward:/limitguard/demo/index.html"

    @GetMapping(
        "/limitguard/payments",
        "/limitguard/payments/",
    )
    fun paymentApplication(): String = "forward:/limitguard/payments/index.html"
}
