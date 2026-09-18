import { DestroyRef, Injectable, computed, inject, signal } from '@angular/core';
import { forkJoin } from 'rxjs';
import { DemoApiError } from '../core/demo-api-error';
import { Account, BankCard, DemoOperation, LimitSetting } from '../core/models';
import { PaymentSimulatorApiService } from './payment-simulator-api.service';
import {
  DemoScenario,
  LimitChangeDecision,
  PaymentAuthorizationRequest,
  PaymentAuthorizationResponse,
  PaymentMutationAction,
  PaymentMutationRequest,
  PaymentMutationResponse,
  PaymentSimulatorSnapshot,
} from './payment-simulator.models';

const IDEMPOTENCY_STORAGE_KEY = 'limitguard.payment-simulator.idempotency.v1';

@Injectable()
export class PaymentSimulatorStore {
  private readonly api = inject(PaymentSimulatorApiService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly disposed = signal(false);

  readonly scenario = signal<DemoScenario | null>(null);
  readonly accounts = signal<Account[]>([]);
  readonly cards = signal<BankCard[]>([]);
  readonly limits = signal<LimitSetting[]>([]);
  readonly authorization = signal<PaymentAuthorizationResponse | null>(null);
  readonly mutation = signal<PaymentMutationResponse | null>(null);
  readonly loading = signal(true);
  readonly refreshing = signal(false);
  readonly authorizing = signal(false);
  readonly activeAction = signal<PaymentMutationAction | null>(null);
  readonly failedAction = signal<PaymentMutationAction | null>(null);
  readonly error = signal<string | null>(null);
  readonly resultError = signal<string | null>(null);
  readonly decidingChange = signal<string | null>(null);
  readonly decisionError = signal<string | null>(null);
  /** Changes waiting for the holder; a cooling-off change starts on its own. */
  readonly heldLimits = computed(() =>
    this.limits().filter((limit) => limit.pendingChange !== null && limit.pendingChange.effectiveAt === null),
  );
  readonly loaded = computed(() => !this.loading() && this.scenario() !== null);
  readonly currentPaymentStatus = computed(
    () => this.mutation()?.transactionStatus ?? this.authorization()?.transactionStatus ?? null,
  );

  constructor() {
    this.destroyRef.onDestroy(() => this.disposed.set(true));
  }

  load(): void {
    this.loading.set(true);
    this.error.set(null);
    forkJoin({
      scenario: this.api.scenario(),
      accounts: this.api.accounts(),
      cards: this.api.cards(),
      limits: this.api.limits(),
    }).subscribe({
      next: (snapshot) => {
        this.applySnapshot(snapshot);
        this.loading.set(false);
      },
      error: (error: DemoApiError) => {
        this.loading.set(false);
        this.error.set(error.message);
      },
    });
  }

  refreshCapacity(): void {
    if (this.disposed()) return;
    this.refreshing.set(true);
    forkJoin({
      accounts: this.api.accounts(),
      limits: this.api.limits(),
    }).subscribe({
      next: ({ accounts, limits }) => {
        this.accounts.set(accounts);
        this.limits.set(limits);
        this.refreshing.set(false);
      },
      error: (error: DemoApiError) => {
        this.refreshing.set(false);
        this.resultError.set(error.message);
      },
    });
  }

  authorize(request: PaymentAuthorizationRequest): void {
    this.authorizing.set(true);
    this.resultError.set(null);
    this.failedAction.set(null);
    this.authorization.set(null);
    this.mutation.set(null);
    this.api.authorize(request, this.idempotencyKey('authorize', request.externalPaymentId)).subscribe({
      next: (response) => {
        this.authorization.set(response);
        this.authorizing.set(false);
        this.refreshCapacity();
      },
      error: (error: DemoApiError) => {
        this.authorizing.set(false);
        this.resultError.set(error.message);
      },
    });
  }

  mutate(action: PaymentMutationAction, externalPaymentId: string, request: PaymentMutationRequest): void {
    this.activeAction.set(action);
    this.resultError.set(null);
    this.failedAction.set(null);
    this.api.mutate(action, externalPaymentId, request, this.idempotencyKey(action, externalPaymentId)).subscribe({
      next: (response) => {
        this.mutation.set(response);
        this.activeAction.set(null);
        this.refreshCapacity();
      },
      error: (error: DemoApiError) => {
        this.activeAction.set(null);
        this.failedAction.set(action);
        this.resultError.set(error.message);
      },
    });
  }

  decideLimitChange(changeRequestId: string, decision: LimitChangeDecision): void {
    this.decidingChange.set(changeRequestId);
    this.decisionError.set(null);
    this.api.decideLimitChange(changeRequestId, decision).subscribe({
      next: () => {
        this.decidingChange.set(null);
        this.refreshCapacity();
      },
      error: (error: DemoApiError) => {
        this.decidingChange.set(null);
        this.decisionError.set(error.message);
        this.refreshCapacity();
      },
    });
  }

  clearResult(): void {
    this.authorization.set(null);
    this.mutation.set(null);
    this.resultError.set(null);
    this.failedAction.set(null);
  }

  limitFor(operation: DemoOperation): LimitSetting | null {
    const candidates = this.limits().filter((limit) => limit.operation === operation);
    return candidates.reduce<LimitSetting | null>(
      (lowest, candidate) =>
        lowest === null || Number(candidate.availableAmount.amount) < Number(lowest.availableAmount.amount)
          ? candidate
          : lowest,
      null,
    );
  }

  private applySnapshot(snapshot: PaymentSimulatorSnapshot): void {
    this.scenario.set(snapshot.scenario);
    this.accounts.set(snapshot.accounts);
    this.cards.set(snapshot.cards);
    this.limits.set(snapshot.limits);
  }

  private idempotencyKey(action: PaymentMutationAction | 'authorize', externalPaymentId: string): string {
    const scope = `${action}:${externalPaymentId}`;
    const keys = this.readIdempotencyKeys();
    const existing = keys[scope];
    if (existing) return existing;
    const key = `payment-simulator-${action}-${this.randomId()}`;
    keys[scope] = key;
    this.writeIdempotencyKeys(keys);
    return key;
  }

  private readIdempotencyKeys(): Record<string, string> {
    try {
      const value = localStorage.getItem(IDEMPOTENCY_STORAGE_KEY);
      if (!value) return {};
      const parsed: unknown = JSON.parse(value);
      if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) return {};
      return Object.fromEntries(
        Object.entries(parsed).filter(
          ([scope, key]) => typeof scope === 'string' && typeof key === 'string' && key.length > 0,
        ),
      );
    } catch {
      return {};
    }
  }

  private writeIdempotencyKeys(keys: Record<string, string>): void {
    try {
      localStorage.setItem(IDEMPOTENCY_STORAGE_KEY, JSON.stringify(keys));
    } catch {
      // A private browsing context can reject storage; requests remain usable for this tab.
    }
  }

  private randomId(): string {
    return typeof crypto.randomUUID === 'function'
      ? crypto.randomUUID()
      : `${Date.now()}-${Math.random().toString(16).slice(2)}`;
  }
}
