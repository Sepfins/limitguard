import { ChangeDetectionStrategy, Component, computed, inject, signal, viewChild } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { LucideAngularModule } from 'lucide-angular';
import { finalize } from 'rxjs';
import { apiErrorMessage } from '../core/api-error';
import { Channel, LimitChangePolicyDecision, LimitConfiguration } from '../core/admin-api.models';
import { AdminApiService } from '../core/admin-api.service';
import { isoOrNull } from '../configurations/configuration-catalog';
import { describePolicy } from './policy-rule';
import { PolicyRuleEditor } from './policy-rule-editor';

const decisions: { value: LimitChangePolicyDecision; label: string }[] = [
  { value: 'ALLOW', label: 'Allow' },
  { value: 'DENY', label: 'Deny' },
  { value: 'REQUIRE_APPROVAL', label: 'Require approval' },
  { value: 'NO_OP', label: 'No decision' }
];

@Component({
  selector: 'app-new-limit-change-policy-page',
  imports: [ReactiveFormsModule, RouterLink, LucideAngularModule, PolicyRuleEditor],
  templateUrl: './new-limit-change-policy-page.html',
  styleUrl: './new-limit-change-policy-page.css',
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class NewLimitChangePolicyPage {
  protected readonly tenantId = inject(ActivatedRoute).snapshot.paramMap.get('tenantId') ?? '';
  private readonly api = inject(AdminApiService);
  private readonly router = inject(Router);
  private readonly ruleEditor = viewChild(PolicyRuleEditor);
  protected readonly decisions = decisions;
  protected readonly configurations = signal<LimitConfiguration[]>([]);
  protected readonly channels = signal<Channel[]>([]);
  protected readonly submitting = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly form = new FormGroup({
    code: new FormControl('', { nonNullable: true, validators: [Validators.required, Validators.maxLength(100)] }),
    name: new FormControl('', { nonNullable: true, validators: [Validators.required, Validators.maxLength(255)] }),
    description: new FormControl('', { nonNullable: true }),
    decision: new FormControl<LimitChangePolicyDecision>('REQUIRE_APPROVAL', { nonNullable: true }),
    limitConfigurationId: new FormControl('', { nonNullable: true }),
    priority: new FormControl(100, { nonNullable: true }),
    denialCode: new FormControl('', { nonNullable: true }),
    denialMessage: new FormControl('', { nonNullable: true }),
    startsAt: new FormControl('', { nonNullable: true }),
    endsAt: new FormControl('', { nonNullable: true })
  });
  protected readonly decision = toSignal(this.form.controls.decision.valueChanges, {
    initialValue: this.form.controls.decision.value
  });
  private readonly configurationId = toSignal(this.form.controls.limitConfigurationId.valueChanges, {
    initialValue: ''
  });

  protected readonly readback = computed(() => {
    const configuration = this.configurations().find((candidate) => candidate.id === this.configurationId());
    return describePolicy(this.decision(), configuration?.code ?? null, this.ruleEditor()?.blocks() ?? null, (id) =>
      this.channels().find((channel) => channel.id === id)?.code ?? `…${id.slice(-8)}`
    );
  });

  constructor() {
    this.api.listLimitConfigurations(this.tenantId, '', null, null, 0, 100).subscribe({
      next: (page) => this.configurations.set(page.items),
      error: () => this.configurations.set([])
    });
    this.api.listChannels(this.tenantId, '', null, 0, 100).subscribe({
      next: (page) => this.channels.set(page.items),
      error: () => this.channels.set([])
    });
  }

  // The conditions and the JSON check only shape; what a rule may say is the core's call.
  protected submit(): void {
    if (this.form.invalid || this.submitting()) {
      this.form.markAllAsTouched();
      this.error.set('Give the policy a code and a name.');
      return;
    }
    const value = this.form.getRawValue();
    const rule = this.ruleEditor()!.value();
    if ('error' in rule) {
      this.error.set(rule.error);
      return;
    }
    const deny = value.decision === 'DENY';
    this.submitting.set(true);
    this.error.set(null);
    this.api
      .createLimitChangePolicy(this.tenantId, {
        code: value.code,
        name: value.name,
        description: value.description || null,
        phase: 'AUTHORIZATION',
        decision: value.decision,
        limitConfigurationId: value.limitConfigurationId || null,
        priority: value.priority,
        conditionExpression: rule.condition,
        actionExpression: rule.actions,
        denialCode: deny ? value.denialCode || null : null,
        denialMessage: deny ? value.denialMessage || null : null,
        startsAt: isoOrNull(value.startsAt),
        endsAt: isoOrNull(value.endsAt)
      })
      .pipe(finalize(() => this.submitting.set(false)))
      .subscribe({
        next: () => this.router.navigate(['/tenants', this.tenantId, 'policies']),
        error: (error) => this.error.set(apiErrorMessage(error, 'The limit-change policy could not be created.'))
      });
  }
}
