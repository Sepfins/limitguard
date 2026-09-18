import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { LucideAngularModule } from 'lucide-angular';
import { bankName } from '../core/brand';
import { BankStore } from '../core/bank.store';
import { formatMoney } from '../core/format';

@Component({
  selector: 'app-cards-page',
  imports: [RouterLink, LucideAngularModule],
  templateUrl: './cards-page.html',
  styleUrl: './cards-page.css',
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class CardsPage {
  readonly store = inject(BankStore);
  readonly bankName = bankName;
  readonly formatMoney = formatMoney;
  readonly card = computed(() => this.store.cards()[0] ?? this.store.overview()?.primaryCard ?? null);
  readonly linkedAccount = computed(() => {
    const card = this.card();
    return card ? this.store.accounts().find((account) => account.id === card.accountId) ?? null : null;
  });
  readonly cardLimits = computed(() =>
    this.store
      .limits()
      .filter((limit) => limit.operation === 'CARD_PURCHASE' || limit.operation === 'ATM_WITHDRAWAL')
  );
}
