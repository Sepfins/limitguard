import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { DemoOperation } from '../core/models';
import { PaymentSimulatorApiService } from './payment-simulator-api.service';

describe('PaymentSimulatorApiService', () => {
  let api: PaymentSimulatorApiService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [PaymentSimulatorApiService, provideHttpClient(), provideHttpClientTesting()],
    });
    api = TestBed.inject(PaymentSimulatorApiService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('discovers the public scenario and customer resources', () => {
    api.scenario().subscribe();
    api.accounts().subscribe();
    api.cards().subscribe();
    api.limits().subscribe();

    const scenario = http.expectOne('/limitguard/demo/api/v1/scenario');
    const accounts = http.expectOne('/limitguard/demo/api/v1/customer/accounts');
    const cards = http.expectOne('/limitguard/demo/api/v1/customer/cards');
    const limits = http.expectOne('/limitguard/demo/api/v1/customer/limits');
    expect(scenario.request.method).toBe('GET');
    expect(accounts.request.method).toBe('GET');
    expect(cards.request.method).toBe('GET');
    expect(limits.request.method).toBe('GET');
    scenario.flush({});
    accounts.flush([]);
    cards.flush([]);
    limits.flush([]);
  });

  it('sends authorization with the durable idempotency key', () => {
    api
      .authorize(
        {
          externalPaymentId: 'sim-1',
          operation: 'CARD_PURCHASE' satisfies DemoOperation,
          accountId: 'account-1',
          cardId: 'card-1',
          description: 'Coffee',
          category: 'DINING',
          amount: '12.30',
          currency: 'EUR',
        },
        'payment-simulator-authorize-key',
      )
      .subscribe();

    const request = http.expectOne('/limitguard/demo/api/v1/integrations/payment-authorizations');
    expect(request.request.method).toBe('POST');
    expect(request.request.headers.get('Idempotency-Key')).toBe('payment-simulator-authorize-key');
    expect(request.request.body.amount).toBe('12.30');
    request.flush({});
  });

  it('maps each lifecycle action to its integration endpoint', () => {
    api.mutate('capture', 'sim/1', {}, 'capture-key').subscribe();
    api.mutate('release', 'sim/1', { reason: 'void' }, 'release-key').subscribe();
    api.mutate('reverse', 'sim/1', {}, 'reverse-key').subscribe();

    const capture = http.expectOne('/limitguard/demo/api/v1/integrations/payments/sim%2F1/captures');
    const release = http.expectOne('/limitguard/demo/api/v1/integrations/payments/sim%2F1/releases');
    const reverse = http.expectOne('/limitguard/demo/api/v1/integrations/payments/sim%2F1/reversals');
    expect(capture.request.headers.get('Idempotency-Key')).toBe('capture-key');
    expect(release.request.body).toEqual({ reason: 'void' });
    expect(reverse.request.method).toBe('POST');
    capture.flush({});
    release.flush({});
    reverse.flush({});
  });

  it('sends the account holder decision to the limit-change integration', () => {
    api.decideLimitChange('change-1', 'approve').subscribe();
    api.decideLimitChange('change-1', 'reject').subscribe();

    const approval = http.expectOne('/limitguard/demo/api/v1/integrations/limit-changes/change-1/approval');
    const rejection = http.expectOne('/limitguard/demo/api/v1/integrations/limit-changes/change-1/rejection');
    expect(approval.request.method).toBe('POST');
    expect(rejection.request.body).toEqual({ reason: 'Declined by the account holder' });
    approval.flush({});
    rejection.flush({});
  });
});
