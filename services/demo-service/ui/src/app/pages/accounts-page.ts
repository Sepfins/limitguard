import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { LucideAngularModule } from 'lucide-angular';
import { BankStore } from '../core/bank.store';
import { formatMoney } from '../core/format';
import { Account, PaymentSummary } from '../core/models';
import { PaymentList } from '../shared/payment-list';

@Component({
  selector: 'app-accounts-page',
  imports: [LucideAngularModule, PaymentList],
  templateUrl: './accounts-page.html',
  styleUrl: './accounts-page.css',
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class AccountsPage {
  readonly store = inject(BankStore);
  readonly formatMoney = formatMoney;

  paymentsFor(account: Account): PaymentSummary[] {
    return this.store.payments().filter((payment) => payment.accountId === account.id).slice(0, 3);
  }
}
