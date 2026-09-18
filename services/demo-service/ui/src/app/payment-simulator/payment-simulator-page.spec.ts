import { importProvidersFrom } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { LucideAngularModule, icons } from 'lucide-angular';
import { of, throwError } from 'rxjs';
import { PaymentSimulatorApiService } from './payment-simulator-api.service';
import { PaymentSimulatorPage } from './payment-simulator-page';

describe('PaymentSimulatorPage', () => {
  const account = {
    id: 'account-1',
    accountType: 'CURRENT' as const,
    displayName: 'Household current',
    maskedIban: 'IE00 2048',
    accessLabel: 'Authorized user',
    bookedBalance: { amount: '1000.00', currency: 'EUR' },
    availableBalance: { amount: '900.00', currency: 'EUR' },
  };
  const card = {
    id: 'card-1',
    accountId: 'account-1',
    displayName: 'Everyday Debit',
    lastFour: '4821',
    expiresMonth: 8,
    expiresYear: 2029,
    status: 'ACTIVE' as const,
    network: 'Debit',
  };
  const limit = {
    id: 'limit-1',
    operation: 'CARD_PURCHASE' as const,
    displayName: 'Card purchases',
    limitKind: 'PERIODIC' as const,
    period: 'DAILY' as const,
    currentAmount: { amount: '1500.00', currency: 'EUR' },
    minimumAmount: { amount: '100.00', currency: 'EUR' },
    maximumAmount: { amount: '3000.00', currency: 'EUR' },
    stepAmount: { amount: '50.00', currency: 'EUR' },
    consumedAmount: { amount: '0.00', currency: 'EUR' },
    reservedAmount: { amount: '0.00', currency: 'EUR' },
    availableAmount: { amount: '1500.00', currency: 'EUR' },
    currentExposure: { amount: '0.00', currency: 'EUR' },
    capacityStatus: 'AVAILABLE' as const,
    resetsAt: '2026-08-28T00:00:00Z',
    version: 1,
    agreedMaximum: null,
    pendingChange: null,
  };
  const scenario = {
    scenarioKey: 'default-household',
    customer: {
      displayName: 'Emma Clarke',
      initials: 'EC',
      relationship: 'Household',
    },
    accountIds: [account.id],
    cardIds: [card.id],
  };
  const authorization = {
    externalPaymentId: 'sim-1',
    decision: 'ALLOWED' as const,
    authorizationDecisionId: 'decision-1',
    paymentTransactionId: 'transaction-1',
    transactionStatus: 'AUTHORIZED',
    amount: { amount: '40.00', currency: 'EUR' },
    violations: [],
    replayed: false,
  };
  const api = {
    scenario: vi.fn(),
    accounts: vi.fn(),
    cards: vi.fn(),
    limits: vi.fn(),
    authorize: vi.fn(),
    mutate: vi.fn(),
  };

  beforeEach(async () => {
    localStorage.clear();
    api.scenario.mockReset().mockReturnValue(of(scenario));
    api.accounts.mockReset().mockReturnValue(of([account]));
    api.cards.mockReset().mockReturnValue(of([card]));
    api.limits.mockReset().mockReturnValue(of([limit]));
    api.authorize.mockReset().mockReturnValue(of(authorization));
    api.mutate.mockReset().mockReturnValue(
      of({
        externalPaymentId: 'sim-1',
        transactionStatus: 'CAPTURED',
        mutationAmount: { amount: '40.00', currency: 'EUR' },
        authorizedAmount: { amount: '40.00', currency: 'EUR' },
        capturedAmount: { amount: '40.00', currency: 'EUR' },
        uncapturedAmount: { amount: '0.00', currency: 'EUR' },
        transactionEventId: 'event-1',
        replayed: false,
      }),
    );
    await TestBed.configureTestingModule({
      imports: [PaymentSimulatorPage],
      providers: [
        { provide: PaymentSimulatorApiService, useValue: api },
        provideRouter([]),
        importProvidersFrom(LucideAngularModule.pick(icons)),
      ],
    }).compileComponents();
  });

  afterEach(() => TestBed.resetTestingModule());

  it('rejects zero amounts before an integration request', () => {
    const fixture = TestBed.createComponent(PaymentSimulatorPage);
    fixture.detectChanges();
    const page = fixture.componentInstance;
    page.amountControl.setValue('0');
    page.authorize();

    expect(page.amountControl.hasError('positive')).toBe(true);
    expect(api.authorize).not.toHaveBeenCalled();
  });

  it('locks a completed request until a new payment reference is started', () => {
    const fixture = TestBed.createComponent(PaymentSimulatorPage);
    fixture.detectChanges();
    const page = fixture.componentInstance;
    page.authorize();
    fixture.detectChanges();

    expect(page.requestLocked()).toBe(true);
    expect(page.amountControl.disabled).toBe(true);

    page.newPayment();
    expect(page.requestLocked()).toBe(false);
  });

  it('offers the next payment once a released payment has nothing left to do', () => {
    api.mutate.mockReturnValue(
      of({
        externalPaymentId: 'sim-1',
        transactionStatus: 'VOIDED',
        mutationAmount: { amount: '40.00', currency: 'EUR' },
        authorizedAmount: { amount: '40.00', currency: 'EUR' },
        capturedAmount: { amount: '0.00', currency: 'EUR' },
        uncapturedAmount: { amount: '0.00', currency: 'EUR' },
        transactionEventId: 'event-1',
        replayed: false,
      }),
    );
    const fixture = TestBed.createComponent(PaymentSimulatorPage);
    fixture.detectChanges();
    const page = fixture.componentInstance;
    page.authorize();
    expect(page.paymentSettled()).toBe(false);
    const reference = page.externalPaymentIdControl.value;

    page.runMutation('release');
    fixture.detectChanges();
    expect(page.paymentSettled()).toBe(true);
    (fixture.nativeElement.querySelector('.next-payment') as HTMLButtonElement).click();

    expect(page.requestLocked()).toBe(false);
    expect(page.externalPaymentIdControl.value).not.toBe(reference);
  });

  it('retries the failed capture before reversing the payment', () => {
    api.mutate
      .mockReset()
      .mockReturnValueOnce(throwError(() => new Error('temporary integration failure')))
      .mockReturnValueOnce(
        of({
          externalPaymentId: 'sim-1',
          transactionStatus: 'CAPTURED',
          mutationAmount: { amount: '40.00', currency: 'EUR' },
          authorizedAmount: { amount: '40.00', currency: 'EUR' },
          capturedAmount: { amount: '40.00', currency: 'EUR' },
          uncapturedAmount: { amount: '0.00', currency: 'EUR' },
          transactionEventId: 'event-1',
          replayed: false,
        }),
      )
      .mockReturnValueOnce(
        of({
          externalPaymentId: 'sim-1',
          transactionStatus: 'REVERSED',
          mutationAmount: { amount: '40.00', currency: 'EUR' },
          authorizedAmount: { amount: '40.00', currency: 'EUR' },
          capturedAmount: { amount: '40.00', currency: 'EUR' },
          uncapturedAmount: { amount: '0.00', currency: 'EUR' },
          transactionEventId: 'event-2',
          replayed: false,
        }),
      );
    const fixture = TestBed.createComponent(PaymentSimulatorPage);
    fixture.detectChanges();
    const page = fixture.componentInstance;
    page.authorize();
    page.runMutation('capture');
    expect(page.store.failedAction()).toBe('capture');
    page.retryAuthorization();
    page.runMutation('reverse');

    expect(api.mutate).toHaveBeenCalledTimes(3);
    expect(api.mutate.mock.calls[1][0]).toBe('capture');
    expect(api.mutate.mock.calls[2][0]).toBe('reverse');
    expect(page.store.mutation()?.transactionStatus).toBe('REVERSED');
  });
});
