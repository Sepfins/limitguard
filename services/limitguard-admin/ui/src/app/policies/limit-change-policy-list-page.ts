import { ChangeDetectionStrategy, Component, DestroyRef, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { LucideAngularModule } from 'lucide-angular';
import { finalize } from 'rxjs';
import { apiErrorMessage } from '../core/api-error';
import {
  Channel,
  LimitChangePolicy,
  LimitChangePolicyDecision,
  LimitConfiguration,
  PageResponse
} from '../core/admin-api.models';
import { AdminApiService } from '../core/admin-api.service';
import { AuthStore } from '../core/auth.store';
import { describePolicy, formatJsonLogic, ruleFromJsonLogic } from './policy-rule';

const decisionLabels: Record<LimitChangePolicyDecision, string> = {
  ALLOW: 'Allow',
  DENY: 'Deny',
  REQUIRE_APPROVAL: 'Require approval',
  NO_OP: 'No decision'
};
import { Guilloche } from '../shared/guilloche';

const emptyPage = { items: [], page: 0, size: 25, totalElements: 0, totalPages: 0 };


@Component({
  selector: 'app-limit-change-policy-list-page',
  imports: [ReactiveFormsModule, RouterLink, LucideAngularModule, Guilloche],
  templateUrl: './limit-change-policy-list-page.html',
  styleUrl: '../shared/list-page.css',
  styles: `
    .decision {
      font-weight: 600;
      white-space: nowrap;
    }

    .decision.deny {
      color: var(--danger);
    }

    .rule {
      min-width: 280px;
      max-width: 62ch;
      line-height: 1.5;
    }

    .denial {
      display: block;
      margin-top: 4px;
      color: var(--text-muted);
      font-size: 13px;
    }

    .json-cell {
      text-align: right;
    }

    .json-row td {
      padding-top: 0;
      background: var(--paper);
    }

    .json-pair {
      display: grid;
      grid-template-columns: minmax(0, 3fr) minmax(0, 2fr);
      gap: 16px;
      padding-top: 12px;
    }

    .json-pair span {
      color: var(--text-muted);
      font-size: 12px;
      font-weight: 600;
    }

    .json-pair pre {
      margin: 4px 0 0;
      padding: 10px 12px;
      overflow-x: auto;
      font-family: var(--mono);
      font-size: 12px;
      background: var(--surface);
      white-space: pre-wrap;
      overflow-wrap: anywhere;
      border: 1px solid var(--border);
      border-radius: var(--radius);
    }

    @media (max-width: 680px) {
      tbody td.priority {
        display: flex;
        padding-bottom: 5px;
      }

      tbody td.priority::before {
        display: block;
      }

      tbody td.stack {
        display: block;
        text-align: left;
      }

      tbody td.stack::before {
        display: block;
        margin-bottom: 4px;
      }

      .rule {
        min-width: 0;
      }

      .json-pair {
        grid-template-columns: 1fr;
      }
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class LimitChangePolicyListPage {
  protected readonly tenantId = inject(ActivatedRoute).snapshot.paramMap.get('tenantId') ?? '';
  private readonly api = inject(AdminApiService);
  protected readonly auth = inject(AuthStore);
  protected readonly configurationFilter = new FormControl('', { nonNullable: true });
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);
  protected readonly policies = signal<PageResponse<LimitChangePolicy>>(emptyPage);
  protected readonly configurations = signal<LimitConfiguration[]>([]);
  protected readonly channels = signal<Channel[]>([]);
  protected readonly expanded = signal<string | null>(null);
  protected readonly decisionLabels = decisionLabels;
  protected readonly json = formatJsonLogic;

  constructor() {
    this.configurationFilter.valueChanges
      .pipe(takeUntilDestroyed(inject(DestroyRef)))
      .subscribe(() => this.load(0));
    this.load(0);
    this.api.listLimitConfigurations(this.tenantId, '', null, null, 0, 100).subscribe({
      next: (page) => this.configurations.set(page.items),
      error: () => this.configurations.set([])
    });
    this.api.listChannels(this.tenantId, '', null, 0, 100).subscribe({
      next: (page) => this.channels.set(page.items),
      error: () => this.channels.set([])
    });
  }

  protected load(page = this.policies().page): void {
    this.loading.set(true);
    this.error.set(null);
    this.api
      .listLimitChangePolicies(this.tenantId, this.configurationFilter.value || null, page)
      .pipe(finalize(() => this.loading.set(false)))
      .subscribe({
        next: (result) => this.policies.set(result),
        error: (error) => this.error.set(apiErrorMessage(error, 'Limit-change policies could not be loaded.'))
      });
  }

  protected configurationCode(id: string | null): string {
    if (!id) return 'Every configuration';
    return this.configurations().find((configuration) => configuration.id === id)?.code ?? `…${id.slice(-8)}`;
  }

  protected readback(policy: LimitChangePolicy): string {
    const configuration = policy.limitConfigurationId ? this.configurationCode(policy.limitConfigurationId) : null;
    const rule = ruleFromJsonLogic(policy.conditionExpression, policy.actionExpression);
    return describePolicy(policy.decision, configuration, rule, (id) =>
      this.channels().find((channel) => channel.id === id)?.code ?? `…${id.slice(-8)}`
    );
  }

}
