import { decimalAmount } from '../configurations/configuration-catalog';
import { LimitChangePolicyDecision } from '../core/admin-api.models';

// The prepared blocks a policy can be built from, and their JSON Logic. The core's validator still has the
// last word; these only cover what the bank walk-through needs, and anything else is written as JSON.

export type ChangeType = 'CREATE' | 'UPDATE' | 'DEACTIVATE' | 'REACTIVATE' | 'DELETE';
export type AmountOperator = 'decimal_gt' | 'decimal_gte' | 'decimal_lt' | 'decimal_lte' | 'decimal_eq';
export type GroupMode = 'all' | 'any';

/** What an amount is compared with, or capped at. */
export type AmountSource = { from: 'fixed'; amount: string } | { from: 'current' } | { from: 'fact'; name: string };

export type Condition =
  | { kind: 'changeType'; changeType: ChangeType }
  | { kind: 'amount'; operator: AmountOperator; against: AmountSource }
  | { kind: 'channel'; channelIds: string[] }
  | { kind: 'requester'; isOwner: boolean }
  | { kind: 'fact'; name: string; isTrue: boolean }
  | ConditionGroup;

export interface ConditionGroup {
  kind: 'group';
  mode: GroupMode;
  conditions: Condition[];
}

export type RuleAction = { kind: 'delay'; factName: string } | { kind: 'cap'; amount: AmountSource };

export const changeTypes: ChangeType[] = ['CREATE', 'UPDATE', 'DEACTIVATE', 'REACTIVATE', 'DELETE'];

const requestedAmount = { var: 'changeRequest.requestedAmount' };
const currentAmount = { var: 'changeRequest.beforeAmount' };
const changeTypeVar = { var: 'changeRequest.changeType' };
const channelVar = { var: 'changeRequest.requestActorContext.requestChannelId' };
const requesterVar = { var: 'changeRequest.requestedBy.partyProfileId' };
const ownerVar = { var: 'changeRequest.targetOwnerPartyProfileId' };
const operators: AmountOperator[] = ['decimal_gt', 'decimal_gte', 'decimal_lt', 'decimal_lte', 'decimal_eq'];

export function emptyRule(): ConditionGroup {
  return { kind: 'group', mode: 'all', conditions: [] };
}

// ---- blocks to JSON Logic; throws an Error with a message for the dialog when a block is unfinished

export function conditionToJsonLogic(rule: ConditionGroup): unknown {
  if (rule.conditions.length === 0) return {};
  if (rule.conditions.length === 1 && rule.mode === 'all') return toJsonLogic(rule.conditions[0]);
  return toJsonLogic(rule);
}

function toJsonLogic(condition: Condition): unknown {
  switch (condition.kind) {
    case 'group':
      if (condition.conditions.length === 0) throw new Error('An "any of" group needs at least one condition.');
      return { [condition.mode === 'all' ? 'and' : 'or']: condition.conditions.map(toJsonLogic) };
    case 'changeType':
      return { '==': [changeTypeVar, condition.changeType] };
    case 'amount':
      return { [condition.operator]: [requestedAmount, amountToJsonLogic(condition.against)] };
    case 'channel':
      if (condition.channelIds.length === 0) throw new Error('Pick at least one channel in the channel block.');
      return { in: [channelVar, condition.channelIds] };
    case 'requester':
      return { [condition.isOwner ? '==' : '!=']: [requesterVar, ownerVar] };
    case 'fact':
      return { [condition.isTrue ? '==' : '!=']: [factVar(condition.name), true] };
  }
}

export function actionsToJsonLogic(actions: RuleAction[]): unknown[] {
  return actions.map((action) =>
    action.kind === 'delay'
      ? { type: 'SET_EFFECTIVE_AT', instant: factVar(action.factName) }
      : { type: 'CAP_REQUESTED_AMOUNT', amount: amountToJsonLogic(action.amount) }
  );
}

function amountToJsonLogic(source: AmountSource): unknown {
  switch (source.from) {
    case 'fixed':
      if (!decimalAmount.test(source.amount)) throw new Error('Enter amounts as decimals such as 125 or 125.50.');
      return source.amount;
    case 'current':
      return currentAmount;
    case 'fact':
      return factVar(source.name);
  }
}

function factVar(name: string): { var: string } {
  const trimmed = name.trim();
  if (!/^[A-Za-z_][\w.]*$/.test(trimmed)) throw new Error('Name each host fact, for example ownerIsMinor.');
  return { var: `facts.${trimmed}` };
}

/** JSON with one condition or action per line. */
export function formatJsonLogic(json: unknown, indent = ''): string {
  const inner = indent + '  ';
  const [operation, args] = single(json);
  if ((operation === 'and' || operation === 'or') && Array.isArray(args)) {
    const lines = args.map((arg) => inner + formatJsonLogic(arg, inner));
    return `{"${operation}": [\n${lines.join(',\n')}\n${indent}]}`;
  }
  if (Array.isArray(json) && json.length > 0 && indent === '') {
    return `[\n${json.map((item) => inner + JSON.stringify(item)).join(',\n')}\n]`;
  }
  return JSON.stringify(json);
}

// ---- blocks to a plain sentence, so the editor and the list read a policy the same way

const operatorWords: Record<AmountOperator, string> = {
  decimal_gt: 'above',
  decimal_gte: 'at least',
  decimal_lt: 'below',
  decimal_lte: 'at most',
  decimal_eq: 'exactly'
};

const decisionOpenings: Record<LimitChangePolicyDecision, string> = {
  ALLOW: 'Allow',
  DENY: 'Deny',
  REQUIRE_APPROVAL: 'Require approval for',
  NO_OP: 'Take no decision on'
};

/** The policy as one plain sentence; a null rule is one the blocks cannot show. */
export function describePolicy(
  decision: LimitChangePolicyDecision,
  configurationCode: string | null,
  rule: { condition: ConditionGroup; actions: RuleAction[] } | null,
  channelCode: (id: string) => string
): string {
  const target = configurationCode ? `changes to ${configurationCode}` : 'changes to any limit';
  if (!rule) return `${decisionOpenings[decision]} ${target} when its custom JSON Logic rule matches.`;
  const condition = rule.condition.conditions.length > 0 ? ` when ${describeGroup(rule.condition, channelCode)}` : '';
  const actions = decision === 'DENY' ? [] : rule.actions.map(describeAction);
  const then = actions.length > 0 ? `, then ${actions.join(' and ')}` : '';
  return `${decisionOpenings[decision]} ${target}${condition}${then}.`;
}

function describeGroup(group: ConditionGroup, channelCode: (id: string) => string): string {
  return group.conditions
    .map((condition) => describeCondition(condition, channelCode))
    .join(group.mode === 'all' ? ' and ' : ' or ');
}

function describeCondition(condition: Condition, channelCode: (id: string) => string): string {
  switch (condition.kind) {
    case 'group':
      return `(${describeGroup(condition, channelCode) || 'an empty group'})`;
    case 'changeType':
      return `the change type is ${condition.changeType}`;
    case 'amount':
      return `the requested amount is ${operatorWords[condition.operator]} ${describeAmount(condition.against)}`;
    case 'channel': {
      const codes = condition.channelIds.map(channelCode);
      return `it comes through ${codes.length > 0 ? codes.join(' or ') : 'a channel not picked yet'}`;
    }
    case 'requester':
      return condition.isOwner ? 'the owner asks' : 'someone other than the owner asks';
    case 'fact':
      return `host fact ${condition.name.trim() || '[name]'} is ${condition.isTrue ? 'true' : 'not true'}`;
  }
}

function describeAction(action: RuleAction): string {
  return action.kind === 'delay'
    ? `delay it until host fact ${action.factName.trim() || '[name]'}`
    : `cap the requested amount at ${describeAmount(action.amount)}`;
}

function describeAmount(source: AmountSource): string {
  switch (source.from) {
    case 'fixed':
      return source.amount.trim() || '[amount]';
    case 'current':
      return 'the current amount';
    case 'fact':
      return `host fact ${source.name.trim() || '[name]'}`;
  }
}

// ---- JSON Logic to blocks; null when the expression uses anything the blocks cannot show

export function conditionFromJsonLogic(json: unknown): ConditionGroup | null {
  if (isRecord(json) && Object.keys(json).length === 0) return emptyRule();
  const group = parseGroup(json, true);
  if (group) return group;
  const single = parseLeaf(json);
  return single ? { kind: 'group', mode: 'all', conditions: [single] } : null;
}

function parseGroup(json: unknown, allowNested: boolean): ConditionGroup | null {
  const [operation, args] = single(json);
  if ((operation !== 'and' && operation !== 'or') || !Array.isArray(args)) return null;
  const conditions = args.map((arg) => (allowNested ? parseGroup(arg, false) : null) ?? parseLeaf(arg));
  if (conditions.some((condition) => condition === null)) return null;
  return { kind: 'group', mode: operation === 'and' ? 'all' : 'any', conditions: conditions as Condition[] };
}

function parseLeaf(json: unknown): Condition | null {
  const [operation, args] = single(json);
  if (!Array.isArray(args) || args.length !== 2) return null;
  const [left, right] = args;
  const leftVar = varName(left);
  if (operators.includes(operation as AmountOperator) && leftVar === requestedAmount.var) {
    const against = parseAmount(right);
    return against && { kind: 'amount', operator: operation as AmountOperator, against };
  }
  if (operation === 'in' && leftVar === channelVar.var && isStringArray(right)) {
    return { kind: 'channel', channelIds: right };
  }
  if (operation !== '==' && operation !== '!=') return null;
  const equals = operation === '==';
  if (equals && leftVar === changeTypeVar.var && changeTypes.includes(right as ChangeType)) {
    return { kind: 'changeType', changeType: right as ChangeType };
  }
  if (leftVar === requesterVar.var && varName(right) === ownerVar.var) return { kind: 'requester', isOwner: equals };
  const fact = factName(left);
  if (fact !== null && right === true) return { kind: 'fact', name: fact, isTrue: equals };
  return null;
}

/** Both halves of a stored policy as blocks, or null when either needs JSON. */
export function ruleFromJsonLogic(condition: unknown, actions: unknown): { condition: ConditionGroup; actions: RuleAction[] } | null {
  const conditionBlocks = conditionFromJsonLogic(condition);
  const actionBlocks = actionsFromJsonLogic(actions);
  return conditionBlocks && actionBlocks ? { condition: conditionBlocks, actions: actionBlocks } : null;
}

export function actionsFromJsonLogic(json: unknown): RuleAction[] | null {
  if (!Array.isArray(json)) return null;
  const actions = json.map((action): RuleAction | null => {
    if (!isRecord(action)) return null;
    const keys = Object.keys(action).sort().join();
    if (action['type'] === 'SET_EFFECTIVE_AT' && keys === 'instant,type') {
      const name = factName(action['instant']);
      return name === null ? null : { kind: 'delay', factName: name };
    }
    if (action['type'] === 'CAP_REQUESTED_AMOUNT' && keys === 'amount,type') {
      const amount = parseAmount(action['amount']);
      return amount && amount.from !== 'current' ? { kind: 'cap', amount } : null;
    }
    return null;
  });
  return actions.some((action) => action === null) ? null : (actions as RuleAction[]);
}

function parseAmount(json: unknown): AmountSource | null {
  if (typeof json === 'string' && decimalAmount.test(json)) return { from: 'fixed', amount: json };
  if (varName(json) === currentAmount.var) return { from: 'current' };
  const name = factName(json);
  return name === null ? null : { from: 'fact', name };
}

function factName(json: unknown): string | null {
  const name = varName(json);
  return name?.startsWith('facts.') ? name.slice('facts.'.length) : null;
}

function varName(json: unknown): string | null {
  const [operation, args] = single(json);
  return operation === 'var' && typeof args === 'string' ? args : null;
}

function single(json: unknown): [string | null, unknown] {
  if (!isRecord(json)) return [null, null];
  const entries = Object.entries(json);
  return entries.length === 1 ? entries[0] : [null, null];
}

function isRecord(json: unknown): json is Record<string, unknown> {
  return typeof json === 'object' && json !== null && !Array.isArray(json);
}

function isStringArray(json: unknown): json is string[] {
  return Array.isArray(json) && json.every((item) => typeof item === 'string');
}
