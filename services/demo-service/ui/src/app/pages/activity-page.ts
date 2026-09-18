import { ChangeDetectionStrategy, Component, DestroyRef, HostListener, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router } from '@angular/router';
import { LucideAngularModule } from 'lucide-angular';
import { BankStore } from '../core/bank.store';
import { formatDateTime, formatMoney, operationLabel, statusLabel } from '../core/format';
import { DemoOperation } from '../core/models';
import { PaymentList } from '../shared/payment-list';

interface ActivityFilter {
  label: string;
  operation: DemoOperation | 'ALL';
}

@Component({
  selector: 'app-activity-page',
  imports: [LucideAngularModule, PaymentList],
  templateUrl: './activity-page.html',
  styleUrl: './activity-page.css',
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class ActivityPage {
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);

  readonly store = inject(BankStore);
  readonly selectedFilter = signal<DemoOperation | 'ALL'>('ALL');
  readonly filters: ActivityFilter[] = [
    { label: 'All', operation: 'ALL' },
    { label: 'Card', operation: 'CARD_PURCHASE' },
    { label: 'ATM', operation: 'ATM_WITHDRAWAL' },
    { label: 'Transfer', operation: 'BANK_TRANSFER' }
  ];
  readonly filteredPayments = computed(() => {
    const operation = this.selectedFilter();
    return operation === 'ALL'
      ? this.store.payments()
      : this.store.payments().filter((payment) => payment.operation === operation);
  });

  readonly formatDateTime = formatDateTime;
  readonly formatMoney = formatMoney;
  readonly statusLabel = statusLabel;
  readonly operationLabel = operationLabel;

  constructor() {
    this.route.queryParamMap.pipe(takeUntilDestroyed(this.destroyRef)).subscribe((params) => {
      const paymentId = params.get('payment');
      if (paymentId) this.store.loadPayment(paymentId);
      else this.store.clearPayment();
    });
  }

  selectFilter(operation: DemoOperation | 'ALL'): void {
    this.selectedFilter.set(operation);
  }

  closeDetail(): void {
    void this.router.navigate([], {
      relativeTo: this.route,
      queryParams: { payment: null },
      queryParamsHandling: 'merge',
      replaceUrl: true
    });
  }

  statusClass(value: string): string {
    return value.toLowerCase().replaceAll('_', '-');
  }

  @HostListener('document:keydown.escape')
  onEscape(): void {
    if (this.store.paymentDetail() || this.store.detailLoading()) this.closeDetail();
  }
}
