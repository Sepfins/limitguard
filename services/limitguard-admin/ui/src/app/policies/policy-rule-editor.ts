import { NgTemplateOutlet } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, ElementRef, inject, input, signal } from '@angular/core';
import { LucideAngularModule } from 'lucide-angular';
import { Channel, LimitChangePolicyDecision } from '../core/admin-api.models';
import {
  actionsToJsonLogic,
  AmountSource,
  changeTypes,
  Condition,
  ConditionGroup,
  conditionToJsonLogic,
  emptyRule,
  formatJsonLogic,
  RuleAction,
  ruleFromJsonLogic
} from './policy-rule';

type BlockKind = Condition['kind'];

export type PolicyRuleValue = { condition: unknown; actions: unknown } | { error: string };

const newCondition: Record<BlockKind, () => Condition> = {
  changeType: () => ({ kind: 'changeType', changeType: 'UPDATE' }),
  amount: () => ({ kind: 'amount', operator: 'decimal_gt', against: { from: 'fixed', amount: '' } }),
  channel: () => ({ kind: 'channel', channelIds: [] }),
  requester: () => ({ kind: 'requester', isOwner: false }),
  fact: () => ({ kind: 'fact', name: '', isTrue: true }),
  group: () => ({ kind: 'group', mode: 'any', conditions: [] })
};

const conditionMenu: { kind: BlockKind; label: string; example: string }[] = [
  { kind: 'changeType', label: 'Change type', example: 'is UPDATE' },
  { kind: 'amount', label: 'Requested amount', example: 'is above 500' },
  { kind: 'channel', label: 'Channel', example: 'comes through MOBILE' },
  { kind: 'requester', label: 'Requester', example: 'is not the owner' },
  { kind: 'fact', label: 'Host fact', example: 'ownerIsMinor is true' },
  { kind: 'group', label: 'Any-of group', example: 'one of several conditions' }
];

const actionMenu: { action: () => RuleAction; label: string; example: string }[] = [
  { action: () => ({ kind: 'delay', factName: '' }), label: 'Delay', example: 'until host fact coolingOffEndsAt' },
  { action: () => ({ kind: 'cap', amount: { from: 'fixed', amount: '' } }), label: 'Cap amount', example: 'at 500' }
];

function newSource(from: string): AmountSource {
  if (from === 'current') return { from: 'current' };
  return from === 'fact' ? { from: 'fact', name: '' } : { from: 'fixed', amount: '' };
}

function parseJson(text: string): { value: unknown } | null {
  try {
    return { value: JSON.parse(text) };
  } catch {
    return null;
  }
}

// A policy's condition and actions as sentence rows, with the raw JSON Logic as the way out.
@Component({
  selector: 'app-policy-rule-editor',
  imports: [NgTemplateOutlet, LucideAngularModule],
  host: {
    '(document:click)': 'closeMenuOutside($event)',
    '(document:keydown.escape)': 'openMenu.set(null)'
  },
  template: `
    @if (mode() === 'blocks') {
      <div class="part" role="group" aria-labelledby="rule-when">
        <h3 id="rule-when">When</h3>
        <ng-container *ngTemplateOutlet="level; context: { $implicit: rule(), nested: false }" />
      </div>

      @if (decision() !== 'DENY') {
        <div class="part" role="group" aria-labelledby="rule-then">
          <h3 id="rule-then">Then also</h3>
          <div class="level">
            @for (action of actions(); track $index; let first = $first) {
              <div class="row">
                <span class="key">{{ first ? '' : 'and' }}</span>
                <div class="clause">
                  @if (action.kind === 'delay') {
                    <span>Delay it until host fact</span>
                    <input aria-label="Host fact with the effective time" placeholder="coolingOffEndsAt"
                      [value]="action.factName" (input)="action.factName = val($event); refresh()" />
                  } @else {
                    <span>Cap the requested amount at</span>
                    <span class="pair">
                      <select aria-label="Cap source" (change)="action.amount = newSource(val($event)); refresh()">
                        <option value="fixed" [selected]="action.amount.from === 'fixed'">a fixed amount</option>
                        <option value="fact" [selected]="action.amount.from === 'fact'">host fact</option>
                      </select>
                      <ng-container *ngTemplateOutlet="source; context: { $implicit: action.amount }" />
                    </span>
                  }
                </div>
                <ng-container *ngTemplateOutlet="remove; context: { $implicit: actions(), index: $index }" />
              </div>
            } @empty {
              <p class="empty">Nothing else: the decision applies to the amount as requested.</p>
            }
            <div class="add">
              <button type="button" class="add-button" aria-haspopup="menu" [attr.aria-expanded]="openMenu() === 'actions'"
                (click)="toggleMenu('actions')">
                <lucide-icon name="plus" [size]="14" /> Add action
              </button>
              @if (openMenu() === 'actions') {
                <div class="menu" role="menu">
                  @for (item of actionMenu; track item.label) {
                    <button type="button" role="menuitem" (click)="addAction(item.action())">
                      <span>{{ item.label }}</span><small>{{ item.example }}</small>
                    </button>
                  }
                </div>
              }
            </div>
          </div>
        </div>
      }

      <div class="footer">
        <small>Host facts are values your backend sends with each change request, such as ownerIsMinor.</small>
        <button type="button" class="link-button" (click)="useJson()">Edit as JSON</button>
      </div>
    } @else {
      <label class="field">
        <span>Condition (JSON Logic)</span>
        <textarea class="code-editor" rows="8" spellcheck="false" [value]="conditionText()"
          (input)="conditionText.set(val($event))"></textarea>
        <small class="hint">Compare amounts with decimal_gt, decimal_lt and the other decimal_ operations, as strings such as "125".</small>
      </label>
      <label class="field">
        <span>Actions (JSON array)</span>
        <textarea class="code-editor" rows="4" spellcheck="false" [value]="actionsText()"
          (input)="actionsText.set(val($event))"></textarea>
        <small class="hint">SET_EFFECTIVE_AT with an instant, or CAP_REQUESTED_AMOUNT with an amount. A deny policy takes none.</small>
      </label>
      <div class="footer">
        <span></span>
        <button type="button" class="link-button" (click)="useBlocks()">Edit as conditions</button>
      </div>
    }
    @if (switchError()) {
      <small class="switch-error" role="alert">{{ switchError() }}</small>
    }

    <ng-template #level let-group let-nested="nested">
      <div class="level" [class.nested]="nested">
        @for (condition of group.conditions; track $index; let first = $first) {
          <div class="row" [class.group-row]="condition.kind === 'group'">
            @if (first) {
              <span class="key"></span>
            } @else {
              <button type="button" class="key switch" (click)="toggleMode(group)"
                [title]="'Combine with ' + (group.mode === 'all' ? 'or' : 'and')"
                [attr.aria-label]="'Conditions combine with ' + joinWord(group) + '. Switch to ' + (group.mode === 'all' ? 'or' : 'and') + '.'">
                {{ joinWord(group) }}
              </button>
            }
            <div class="clause">
              @switch (condition.kind) {
                @case ('group') {
                  <span>{{ condition.mode === 'any' ? 'Any of these is true' : 'All of these are true' }}</span>
                }
                @case ('changeType') {
                  <span>Change type is</span>
                  <select aria-label="Change type" (change)="condition.changeType = val($event); refresh()">
                    @for (type of changeTypes; track type) {
                      <option [value]="type" [selected]="condition.changeType === type">{{ type }}</option>
                    }
                  </select>
                }
                @case ('amount') {
                  <span>Requested amount is</span>
                  <select aria-label="Comparison" (change)="condition.operator = val($event); refresh()">
                    <option value="decimal_gt" [selected]="condition.operator === 'decimal_gt'">above</option>
                    <option value="decimal_gte" [selected]="condition.operator === 'decimal_gte'">at least</option>
                    <option value="decimal_lt" [selected]="condition.operator === 'decimal_lt'">below</option>
                    <option value="decimal_lte" [selected]="condition.operator === 'decimal_lte'">at most</option>
                    <option value="decimal_eq" [selected]="condition.operator === 'decimal_eq'">exactly</option>
                  </select>
                  <span class="pair">
                    <select aria-label="Compared with" (change)="condition.against = newSource(val($event)); refresh()">
                      <option value="fixed" [selected]="condition.against.from === 'fixed'">a fixed amount</option>
                      <option value="current" [selected]="condition.against.from === 'current'">the current amount</option>
                      <option value="fact" [selected]="condition.against.from === 'fact'">host fact</option>
                    </select>
                    <ng-container *ngTemplateOutlet="source; context: { $implicit: condition.against }" />
                  </span>
                }
                @case ('channel') {
                  <span>Comes through</span>
                  @for (channel of channels(); track channel.id) {
                    <button type="button" class="chip" [class.on]="condition.channelIds.includes(channel.id)"
                      [attr.aria-pressed]="condition.channelIds.includes(channel.id)"
                      (click)="toggle(condition.channelIds, channel.id)">{{ channel.code }}</button>
                  }
                  @for (id of unknownChannels(condition.channelIds); track id) {
                    <button type="button" class="chip on" title="Not a channel of this tenant" (click)="toggle(condition.channelIds, id)">
                      …{{ id.slice(-8) }}
                    </button>
                  }
                  @if (channels().length === 0) {
                    <span class="muted">This tenant has no channels yet.</span>
                  }
                }
                @case ('requester') {
                  <span>Requested by</span>
                  <select aria-label="Requester" (change)="condition.isOwner = val($event) === 'owner'; refresh()">
                    <option value="owner" [selected]="condition.isOwner">the owner</option>
                    <option value="other" [selected]="!condition.isOwner">someone other than the owner</option>
                  </select>
                }
                @case ('fact') {
                  <span>Host fact</span>
                  <input aria-label="Host fact" placeholder="ownerIsMinor" [value]="condition.name"
                    (input)="condition.name = val($event); refresh()" />
                  <select aria-label="Fact state" (change)="condition.isTrue = val($event) === 'true'; refresh()">
                    <option value="true" [selected]="condition.isTrue">is true</option>
                    <option value="false" [selected]="!condition.isTrue">is not true</option>
                  </select>
                }
              }
            </div>
            <ng-container *ngTemplateOutlet="remove; context: { $implicit: group.conditions, index: $index }" />
            @if (condition.kind === 'group') {
              <div class="group-body">
                <ng-container *ngTemplateOutlet="level; context: { $implicit: condition, nested: true }" />
              </div>
            }
          </div>
        } @empty {
          <p class="empty">
            {{ nested ? 'Add the conditions this group chooses between.' : 'No conditions yet: the policy matches every change request.' }}
          </p>
        }
        <div class="add">
          <button type="button" class="add-button" aria-haspopup="menu" [attr.aria-expanded]="openMenu() === group"
            (click)="toggleMenu(group)">
            <lucide-icon name="plus" [size]="14" /> {{ nested ? 'Add to group' : 'Add condition' }}
          </button>
          @if (openMenu() === group) {
            <div class="menu" role="menu">
              @for (item of conditionMenu; track item.kind) {
                @if (!nested || item.kind !== 'group') {
                  <button type="button" role="menuitem" (click)="add(group, item.kind)">
                    <span>{{ item.label }}</span><small>{{ item.example }}</small>
                  </button>
                }
              }
            </div>
          }
        </div>
      </div>
    </ng-template>

    <ng-template #source let-source>
      @if (source.from === 'fixed') {
        <input class="amount-input" aria-label="Amount" inputmode="decimal" placeholder="500" [value]="source.amount"
          (input)="source.amount = val($event); refresh()" />
      } @else if (source.from === 'fact') {
        <input aria-label="Host fact" placeholder="agreedMaximum" [value]="source.name"
          (input)="source.name = val($event); refresh()" />
      }
    </ng-template>

    <ng-template #remove let-list let-index="index">
      <button type="button" class="remove" title="Remove" aria-label="Remove" (click)="list.splice(index, 1); refresh()">
        <lucide-icon name="x" [size]="15" />
      </button>
    </ng-template>
  `,
  styleUrl: './policy-rule-editor.css',
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class PolicyRuleEditor {
  readonly channels = input<Channel[]>([]);
  readonly decision = input<LimitChangePolicyDecision>('DENY');
  protected readonly mode = signal<'blocks' | 'json'>('blocks');
  protected readonly rule = signal<ConditionGroup>(emptyRule());
  protected readonly actions = signal<RuleAction[]>([]);
  protected readonly conditionText = signal('{}');
  protected readonly actionsText = signal('[]');
  protected readonly switchError = signal<string | null>(null);
  protected readonly openMenu = signal<unknown>(null);
  protected readonly changeTypes = changeTypes;
  protected readonly conditionMenu = conditionMenu;
  protected readonly actionMenu = actionMenu;
  protected readonly newSource = newSource;
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);

  /** The rule as blocks for the readback, or null when the JSON says more than the blocks can. */
  readonly blocks = computed(() => {
    if (this.mode() === 'blocks') return { condition: this.rule(), actions: this.actions() };
    const condition = parseJson(this.conditionText());
    const actions = parseJson(this.actionsText());
    return condition && actions ? ruleFromJsonLogic(condition.value, actions.value) : null;
  });

  reset(): void {
    this.mode.set('blocks');
    this.rule.set(emptyRule());
    this.actions.set([]);
    this.conditionText.set('{}');
    this.actionsText.set('[]');
    this.switchError.set(null);
  }

  /** The condition and actions as JSON values for the core, or why they cannot be sent yet. */
  value(): PolicyRuleValue {
    if (this.mode() === 'json') {
      const condition = parseJson(this.conditionText());
      if (!condition) return { error: 'The condition is not valid JSON.' };
      const actions = parseJson(this.actionsText());
      if (!actions) return { error: 'The actions are not valid JSON.' };
      return { condition: condition.value, actions: actions.value };
    }
    try {
      return {
        condition: conditionToJsonLogic(this.rule()),
        actions: this.decision() === 'DENY' ? [] : actionsToJsonLogic(this.actions())
      };
    } catch (error) {
      return { error: (error as Error).message };
    }
  }

  protected useJson(): void {
    const value = this.value();
    if ('error' in value) {
      this.switchError.set(value.error);
      return;
    }
    this.conditionText.set(formatJsonLogic(value.condition));
    this.actionsText.set(formatJsonLogic(value.actions));
    this.switchError.set(null);
    this.mode.set('json');
  }

  protected useBlocks(): void {
    const condition = parseJson(this.conditionText());
    const actions = parseJson(this.actionsText());
    const blocks = condition && actions && ruleFromJsonLogic(condition.value, actions.value);
    if (!blocks) {
      this.switchError.set("This rule can't be shown as conditions. Keep editing it as JSON.");
      return;
    }
    this.rule.set(blocks.condition);
    this.actions.set(blocks.actions);
    this.switchError.set(null);
    this.mode.set('blocks');
  }

  protected add(group: ConditionGroup, kind: BlockKind): void {
    group.conditions.push(newCondition[kind]());
    this.openMenu.set(null);
    this.refresh();
  }

  protected addAction(action: RuleAction): void {
    this.openMenu.set(null);
    this.actions.update((actions) => [...actions, action]);
  }

  protected toggleMode(group: ConditionGroup): void {
    group.mode = group.mode === 'all' ? 'any' : 'all';
    this.refresh();
  }

  protected joinWord(group: ConditionGroup): string {
    return group.mode === 'all' ? 'and' : 'or';
  }

  protected toggleMenu(menu: unknown): void {
    this.openMenu.update((open) => (open === menu ? null : menu));
  }

  protected closeMenuOutside(event: Event): void {
    const target = event.target as Node;
    const insideAdd = [...this.host.nativeElement.querySelectorAll('.add')].some((add) => add.contains(target));
    if (!insideAdd) this.openMenu.set(null);
  }

  protected toggle(ids: string[], id: string): void {
    const index = ids.indexOf(id);
    if (index >= 0) ids.splice(index, 1);
    else ids.push(id);
    this.refresh();
  }

  protected unknownChannels(ids: string[]): string[] {
    return ids.filter((id) => !this.channels().some((channel) => channel.id === id));
  }

  // Blocks are edited in place; a new top-level reference lets OnPush and the readback redraw them.
  protected refresh(): void {
    this.rule.set({ ...this.rule() });
    this.actions.set([...this.actions()]);
  }

  protected val(event: Event): string {
    return (event.target as HTMLInputElement | HTMLSelectElement).value;
  }
}
