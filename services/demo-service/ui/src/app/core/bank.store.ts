import { DestroyRef, Injectable, computed, inject, signal } from '@angular/core';
import { forkJoin } from 'rxjs';
import { DemoApiError, DemoApiService } from './demo-api.service';
import { BankCard, LimitSetting, Overview, PaymentDetail, PaymentSummary } from './models';

@Injectable({ providedIn: 'root' })
export class BankStore {
  private readonly api = inject(DemoApiService);
  private readonly destroyRef = inject(DestroyRef);
  private eventSource?: EventSource;
  private refreshTimer?: number;

  readonly overview = signal<Overview | null>(null);
  readonly payments = signal<PaymentSummary[]>([]);
  readonly limits = signal<LimitSetting[]>([]);
  readonly cards = signal<BankCard[]>([]);
  readonly paymentDetail = signal<PaymentDetail | null>(null);
  readonly loading = signal(true);
  readonly refreshing = signal(false);
  readonly detailLoading = signal(false);
  readonly error = signal<string | null>(null);
  readonly connected = signal(false);
  readonly balancesVisible = signal(true);
  readonly accounts = computed(() => this.overview()?.accounts ?? []);

  constructor() {
    this.connectEvents();
    document.addEventListener('visibilitychange', this.onVisibilityChange);
    this.destroyRef.onDestroy(() => {
      this.eventSource?.close();
      document.removeEventListener('visibilitychange', this.onVisibilityChange);
      if (this.refreshTimer) window.clearTimeout(this.refreshTimer);
    });
  }

  load(): void {
    this.fetch(true);
  }

  refresh(): void {
    this.fetch(false);
  }

  loadPayment(externalPaymentId: string): void {
    this.detailLoading.set(true);
    this.paymentDetail.set(null);
    this.api.payment(externalPaymentId).subscribe({
      next: (detail) => {
        this.paymentDetail.set(detail);
        this.detailLoading.set(false);
      },
      error: (error: DemoApiError) => {
        this.error.set(error.message);
        this.detailLoading.set(false);
      }
    });
  }

  clearPayment(): void {
    this.paymentDetail.set(null);
  }

  replaceLimit(limit: LimitSetting): void {
    this.limits.update((items) => items.map((item) => (item.id === limit.id ? limit : item)));
    this.overview.update((value) =>
      value
        ? { ...value, limits: value.limits.map((item) => (item.id === limit.id ? limit : item)) }
        : value
    );
  }

  toggleBalances(): void {
    this.balancesVisible.update((visible) => !visible);
  }

  private fetch(initial: boolean): void {
    if (initial) this.loading.set(true);
    else this.refreshing.set(true);
    this.error.set(null);
    forkJoin({
      overview: this.api.overview(),
      payments: this.api.payments(),
      limits: this.api.limits(),
      cards: this.api.cards()
    }).subscribe({
      next: ({ overview, payments, limits, cards }) => {
        this.overview.set(overview);
        this.payments.set(payments);
        this.limits.set(limits);
        this.cards.set(cards);
        this.loading.set(false);
        this.refreshing.set(false);
      },
      error: (error: DemoApiError) => {
        this.error.set(error.message);
        this.loading.set(false);
        this.refreshing.set(false);
      }
    });
  }

  private connectEvents(): void {
    this.eventSource = new EventSource('/limitguard/demo/api/v1/customer/events');
    this.eventSource.onopen = () => this.connected.set(true);
    this.eventSource.onerror = () => this.connected.set(false);
    ['snapshot.required', 'payments.updated', 'limits.updated'].forEach((eventName) => {
      this.eventSource?.addEventListener(eventName, () => this.scheduleRefresh());
    });
  }

  private scheduleRefresh(): void {
    if (this.refreshTimer) window.clearTimeout(this.refreshTimer);
    this.refreshTimer = window.setTimeout(() => this.refresh(), 120);
  }

  private readonly onVisibilityChange = (): void => {
    if (document.visibilityState === 'visible') this.refresh();
  };
}
