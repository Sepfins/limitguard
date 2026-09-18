import { HttpClient, HttpErrorResponse, HttpHeaders } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, catchError, throwError } from 'rxjs';
import { DemoApiError } from './demo-api-error';
import {
  Account,
  ApiError,
  BankCard,
  DemoOperation,
  LimitSetting,
  LimitUpdateResponse,
  Overview,
  PaymentDetail,
  PaymentSummary,
} from './models';

export { DemoApiError } from './demo-api-error';

@Injectable({ providedIn: 'root' })
export class DemoApiService {
  private readonly http = inject(HttpClient);
  private readonly base = '/limitguard/demo/api/v1/customer';

  overview(): Observable<Overview> {
    return this.request(this.http.get<Overview>(`${this.base}/overview`));
  }

  accounts(): Observable<Account[]> {
    return this.request(this.http.get<Account[]>(`${this.base}/accounts`));
  }

  cards(): Observable<BankCard[]> {
    return this.request(this.http.get<BankCard[]>(`${this.base}/cards`));
  }

  payments(operation?: DemoOperation): Observable<PaymentSummary[]> {
    const url = operation
      ? `${this.base}/payments?operation=${encodeURIComponent(operation)}`
      : `${this.base}/payments`;
    return this.request(this.http.get<PaymentSummary[]>(url));
  }

  payment(externalPaymentId: string): Observable<PaymentDetail> {
    return this.request(this.http.get<PaymentDetail>(`${this.base}/payments/${encodeURIComponent(externalPaymentId)}`));
  }

  limits(): Observable<LimitSetting[]> {
    return this.request(this.http.get<LimitSetting[]>(`${this.base}/limits`));
  }

  updateLimit(limitId: string, amount: string): Observable<LimitUpdateResponse> {
    return this.request(
      this.http.patch<LimitUpdateResponse>(
        `${this.base}/limits/${limitId}`,
        { amount },
        {
          headers: new HttpHeaders({ 'Idempotency-Key': crypto.randomUUID() }),
        },
      ),
    );
  }

  withdrawLimitChange(limitId: string): Observable<LimitUpdateResponse> {
    return this.request(this.http.delete<LimitUpdateResponse>(`${this.base}/limits/${limitId}/pending-change`));
  }

  private request<T>(request: Observable<T>): Observable<T> {
    return request.pipe(
      catchError((error: HttpErrorResponse) => {
        const body = error.error as Partial<ApiError> | undefined;
        return throwError(
          () =>
            new DemoApiError(
              body?.code ?? 'REQUEST_FAILED',
              body?.message ?? 'The bank service could not be reached.',
              body?.fieldErrors ?? [],
              body?.correlationId,
              error.status,
            ),
        );
      }),
    );
  }
}
