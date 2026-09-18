import { ChangeDetectionStrategy, Component, Input } from '@angular/core';
import { RouterLink } from '@angular/router';
import { LucideAngularModule } from 'lucide-angular';
import { categoryIcon, formatDateTime, formatMoney, operationLabel, statusLabel } from '../core/format';
import { PaymentSummary } from '../core/models';

@Component({
  selector: 'app-payment-list',
  imports: [RouterLink, LucideAngularModule],
  templateUrl: './payment-list.html',
  styleUrl: './payment-list.css',
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class PaymentList {
  @Input({ required: true }) payments: PaymentSummary[] = [];
  @Input() compact = false;

  readonly categoryIcon = categoryIcon;
  readonly formatDateTime = formatDateTime;
  readonly formatMoney = formatMoney;
  readonly operationLabel = operationLabel;
  readonly statusLabel = statusLabel;

  isStruck(displayStatus: string): boolean {
    return ['DECLINED', 'RELEASED', 'REVERSED', 'EXPIRED'].includes(displayStatus);
  }
}
