import { ChangeDetectionStrategy, Component, input, signal } from '@angular/core';
import { LucideAngularModule } from 'lucide-angular';

@Component({
  selector: 'app-copy-id',
  imports: [LucideAngularModule],
  template: `
    <code [title]="id()">…{{ id().slice(-8) }}</code>
    <button
      class="icon-button"
      type="button"
      [attr.aria-label]="copied() ? 'Id copied' : 'Copy id'"
      [title]="copied() ? 'Copied' : 'Copy id'"
      (click)="copy()"
    >
      <lucide-icon [name]="copied() ? 'check' : 'copy'" [size]="15" />
    </button>
  `,
  styles: `
    :host {
      display: inline-flex;
      align-items: center;
      gap: 2px;
    }

    code {
      color: var(--text-muted);
      font-size: 12px;
      white-space: nowrap;
    }

    .icon-button {
      width: 28px;
      height: 28px;
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class CopyId {
  readonly id = input.required<string>();
  protected readonly copied = signal(false);

  protected copy(): void {
    navigator.clipboard.writeText(this.id()).then(() => {
      this.copied.set(true);
      setTimeout(() => this.copied.set(false), 1500);
    });
  }
}
