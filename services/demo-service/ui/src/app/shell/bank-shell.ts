import { ChangeDetectionStrategy, Component, OnInit, computed, inject } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { LucideAngularModule } from 'lucide-angular';
import { bankName } from '../core/brand';
import { BankStore } from '../core/bank.store';

@Component({
  selector: 'app-bank-shell',
  imports: [RouterOutlet, RouterLink, RouterLinkActive, LucideAngularModule],
  templateUrl: './bank-shell.html',
  styleUrl: './bank-shell.css',
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class BankShell implements OnInit {
  readonly store = inject(BankStore);
  readonly bankName = bankName;
  readonly customer = computed(() => this.store.overview()?.customer);
  readonly navigation = [
    { path: '/home', label: 'Home', icon: 'home' },
    { path: '/accounts', label: 'Accounts', icon: 'wallet-cards' },
    { path: '/cards', label: 'Cards', icon: 'credit-card' },
    { path: '/activity', label: 'Activity', icon: 'activity' },
    { path: '/limits', label: 'Limits', icon: 'sliders-horizontal' }
  ];

  ngOnInit(): void {
    this.store.load();
  }
}
