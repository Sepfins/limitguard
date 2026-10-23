import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { ActivatedRoute } from '@angular/router';

@Component({
  selector: 'app-tenant-area-placeholder',
  template: `
    <h1>{{ title }}</h1>
    <p>This area is not available yet.</p>
  `,
  styles: `
    h1 {
      margin: 4px 0 6px;
      font-size: 34px;
    }

    p {
      margin: 0;
      color: var(--text-muted);
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class TenantAreaPlaceholder {
  protected readonly title: string = inject(ActivatedRoute).snapshot.data['title'];
}
