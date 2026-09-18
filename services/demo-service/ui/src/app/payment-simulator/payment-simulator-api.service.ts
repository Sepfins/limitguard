import { HttpClient, HttpErrorResponse, HttpHeaders } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, catchError, throwError } from 'rxjs';
import { Account, ApiError, BankCard, DemoOperation, LimitSetting, LimitUpdateResponse } from '../core/models';
import { DemoApiError } from '../core/demo-api-error';
import {
  DemoScenario,
  LimitChangeDecision,
  PaymentAuthorizationRequest,
  PaymentAuthorizationResponse,
  PaymentMutationAction,
  PaymentMutationRequest,
  PaymentMutationResponse,
} from './payment-simulator.models';

@Injectable({ providedIn: 'root' })
export class PaymentSimulatorApiService {
  private readonly http = inject(HttpClient);
  private readonly base = '/limitguard/demo/api/v1';
  private readonly integrations = `${this.base}/integrations`;

  scenario(): Observable<DemoScenario> {
    return this.request(this.http.get<DemoScenario>(`${this.base}/scenario`));
  }

  accounts(): Observable<Account[]> {
    return this.request(this.http.get<Account[]>(`${this.base}/customer/accounts`));
  }

  cards(): Observable<BankCard[]> {
    return this.request(this.http.get<BankCard[]>(`${this.base}/customer/cards`));
  }

  limits(): Observable<LimitSetting[]> {
    return this.request(this.http.get<LimitSetting[]>(`${this.base}/customer/limits`));
  }

  authorize(request: PaymentAuthorizationRequest, idempotencyKey: string): Observable<PaymentAuthorizationResponse> {
    return this.request(
      this.http.post<PaymentAuthorizationResponse>(`${this.integrations}/payment-authorizations`, request, {
        headers: this.idempotencyHeaders(idempotencyKey),
      }),
    );
  }

  mutate(
    action: PaymentMutationAction,
    externalPaymentId: string,
    request: PaymentMutationRequest,
    idempotencyKey: string,
  ): Observable<PaymentMutationResponse> {
    const path = action === 'capture' ? 'captures' : action === 'release' ? 'releases' : 'reversals';
    return this.request(
      this.http.post<PaymentMutationResponse>(
        `${this.integrations}/payments/${encodeURIComponent(externalPaymentId)}/${path}`,
        request,
        { headers: this.idempotencyHeaders(idempotencyKey) },
      ),
    );
  }

  /** The account holder's answer to a held limit change, as their own banking app would send it. */
  decideLimitChange(changeRequestId: string, decision: LimitChangeDecision): Observable<LimitUpdateResponse> {
    const url = `${this.integrations}/limit-changes/${encodeURIComponent(changeRequestId)}`;
    return this.request(
      decision === 'approve'
        ? this.http.post<LimitUpdateResponse>(`${url}/approval`, {})
        : this.http.post<LimitUpdateResponse>(`${url}/rejection`, { reason: 'Declined by the account holder' }),
    );
  }

  private idempotencyHeaders(key: string): HttpHeaders {
    return new HttpHeaders({ 'Idempotency-Key': key });
  }

  private request<T>(request: Observable<T>): Observable<T> {
    return request.pipe(
      catchError((error: HttpErrorResponse) => {
        const body = error.error as Partial<ApiError> | undefined;
        return throwError(
          () =>
            new DemoApiError(
              body?.code ?? 'REQUEST_FAILED',
              body?.message ?? 'The payment integration could not be reached.',
              body?.fieldErrors ?? [],
              body?.correlationId,
              error.status,
            ),
        );
      }),
    );
  }
}
