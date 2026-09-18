import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { formatMoney } from '../core/format';
import { LimitSetting } from '../core/models';

// Spent money is solid, held money is hatched; both slide as payments move through Payment Lab.
@Component({
  selector: 'app-limit-bar',
  template: `
    <div class="bar" role="img" [attr.aria-label]="label()">
      <span class="completed" [style.width.%]="completed()"></span>
      <span class="reserved" [style.width.%]="reserved()"></span>
    </div>
  `,
  styles: `
    :host {
      display: block;
    }

    .bar {
      display: flex;
      height: var(--bar-height, 8px);
      overflow: hidden;
      background: var(--surface-soft);
      border-radius: 999px;
    }

    span {
      flex: 0 0 auto;
      transition: width 700ms cubic-bezier(0.16, 1, 0.3, 1);
    }

    .completed {
      background: var(--accent);
    }

    .reserved {
      background: repeating-linear-gradient(135deg, var(--accent) 0 3px, transparent 3px 7px);
      box-shadow: inset 0 0 0 1px var(--accent);
      border-radius: 0 999px 999px 0;
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class LimitBar {
  readonly limit = input.required<LimitSetting>();

  protected readonly completed = computed(() => this.share(this.limit().consumedAmount.amount));
  protected readonly reserved = computed(() =>
    Math.min(this.share(this.limit().reservedAmount.amount), 100 - this.completed())
  );
  protected readonly label = computed(() => {
    const limit = this.limit();
    return `${formatMoney(limit.consumedAmount)} spent and ${formatMoney(limit.reservedAmount)} held of ${formatMoney(limit.currentAmount)}`;
  });

  private share(amount: string): number {
    const cap = Number(this.limit().currentAmount.amount);
    return cap > 0 ? Math.min(100, Math.max(0, (Number(amount) / cap) * 100)) : 0;
  }
}
