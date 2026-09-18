import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { LucideAngularModule } from 'lucide-angular';
import { BankStore } from '../core/bank.store';
import { formatMoney, splitMoney } from '../core/format';
import { LimitBar } from '../shared/limit-bar';
import { PaymentList } from '../shared/payment-list';

@Component({
  selector: 'app-home-page',
  imports: [RouterLink, LucideAngularModule, LimitBar, PaymentList],
  templateUrl: './home-page.html',
  styleUrl: './home-page.css',
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class HomePage {
  readonly store = inject(BankStore);
  readonly formatMoney = formatMoney;
  readonly tab = signal<'activity' | 'accounts'>('activity');

  readonly balance = computed(() => {
    const overview = this.store.overview();
    return overview ? splitMoney(overview.totalBookedBalance, this.store.balancesVisible()) : { whole: '', rest: '' };
  });

  /** The daily card limit with the least room left, the one a customer is most likely to hit today. */
  readonly todayLimit = computed(() => {
    const daily = (this.store.overview()?.limits ?? []).filter(
      (limit) => limit.operation === 'CARD_PURCHASE' && limit.period === 'DAILY'
    );
    return daily.length === 0
      ? null
      : daily.reduce((lowest, limit) =>
          Number(limit.availableAmount.amount) < Number(lowest.availableAmount.amount) ? limit : lowest
        );
  });
}
