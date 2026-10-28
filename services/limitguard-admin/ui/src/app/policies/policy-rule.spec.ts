import {
  actionsFromJsonLogic,
  actionsToJsonLogic,
  conditionFromJsonLogic,
  conditionToJsonLogic,
  describePolicy,
  emptyRule,
  formatJsonLogic,
  ruleFromJsonLogic
} from './policy-rule';

// The conditions and actions of the KBC and Sparkasse policies.
const youthCap = {
  and: [
    { '==': [{ var: 'facts.ownerIsMinor' }, true] },
    { decimal_gt: [{ var: 'changeRequest.requestedAmount' }, '125'] }
  ]
};
const coolingOff = {
  and: [
    { '==': [{ var: 'changeRequest.changeType' }, 'UPDATE'] },
    { in: [{ var: 'changeRequest.requestActorContext.requestChannelId' }, ['mobile-id', 'touch-id']] },
    { decimal_gt: [{ var: 'changeRequest.requestedAmount' }, { var: 'changeRequest.beforeAmount' }] }
  ]
};
const proxyMayOnlyLower = {
  and: [
    { '!=': [{ var: 'changeRequest.requestedBy.partyProfileId' }, { var: 'changeRequest.targetOwnerPartyProfileId' }] },
    { '!=': [{ var: 'facts.proxyMayRaise' }, true] },
    {
      or: [
        { '==': [{ var: 'changeRequest.changeType' }, 'CREATE'] },
        { decimal_gt: [{ var: 'changeRequest.requestedAmount' }, { var: 'changeRequest.beforeAmount' }] }
      ]
    }
  ]
};
const aboveAgreedMaximum = { decimal_gt: [{ var: 'changeRequest.requestedAmount' }, { var: 'facts.agreedMaximum' }] };

describe('policy rule blocks', () => {
  it.each([[{}], [youthCap], [coolingOff], [proxyMayOnlyLower], [aboveAgreedMaximum]])(
    'turns %j into blocks and back unchanged',
    (condition) => {
      const blocks = conditionFromJsonLogic(condition);
      expect(blocks).not.toBeNull();
      expect(conditionToJsonLogic(blocks!)).toEqual(condition);
    }
  );

  it('turns the cooling-off delay and a cap into blocks and back unchanged', () => {
    const actions = [
      { type: 'SET_EFFECTIVE_AT', instant: { var: 'facts.coolingOffEndsAt' } },
      { type: 'CAP_REQUESTED_AMOUNT', amount: '125' }
    ];
    expect(actionsToJsonLogic(actionsFromJsonLogic(actions)!)).toEqual(actions);
  });

  it('turns a delay the policy states itself into blocks and back unchanged', () => {
    const actions = [
      { type: 'SET_EFFECTIVE_AT', after: 'PT30M' },
      { type: 'SET_EFFECTIVE_AT', after: 'PT8H' },
      { type: 'SET_EFFECTIVE_AT', after: 'P2D' }
    ];
    const blocks = actionsFromJsonLogic(actions)!;
    expect(blocks[0]).toEqual({ kind: 'delayFor', amount: '30', unit: 'minutes' });
    expect(actionsToJsonLogic(blocks)).toEqual(actions);
    expect(describePolicy('ALLOW', 'CARD_DAILY', { condition: emptyRule(), actions: [blocks[0]] }, (id) => id)).toBe(
      'Allow changes to CARD_DAILY, then delay it by 30 minutes.'
    );
    expect(actionsFromJsonLogic([{ type: 'SET_EFFECTIVE_AT', after: 'PT1H30M' }])).toBeNull();
    expect(() => actionsToJsonLogic([{ kind: 'delayFor', amount: '1.5', unit: 'hours' }])).toThrow('whole number');
  });

  it('leaves anything the blocks cannot show to the JSON editor', () => {
    expect(conditionFromJsonLogic({ '>': [{ var: 'changeRequest.requestedAmount' }, 125] })).toBeNull();
    expect(conditionFromJsonLogic({ and: [{ or: [{ or: [youthCap] }] }] })).toBeNull();
    expect(actionsFromJsonLogic([{ type: 'CAP_REQUESTED_AMOUNT', amount: '125', currency: 'EUR' }])).toBeNull();
  });

  it('refuses unfinished blocks with a message', () => {
    const rule = emptyRule();
    rule.conditions.push({ kind: 'amount', operator: 'decimal_gt', against: { from: 'fixed', amount: '1,5' } });
    expect(() => conditionToJsonLogic(rule)).toThrow('Enter amounts as decimals');
    rule.conditions = [{ kind: 'fact', name: '', isTrue: true }];
    expect(() => conditionToJsonLogic(rule)).toThrow('Name each host fact');
  });

  it('formats a rule one condition per line', () => {
    expect(formatJsonLogic(youthCap)).toBe(
      '{"and": [\n  {"==":[{"var":"facts.ownerIsMinor"},true]},\n  {"decimal_gt":[{"var":"changeRequest.requestedAmount"},"125"]}\n]}'
    );
    expect(JSON.parse(formatJsonLogic(proxyMayOnlyLower))).toEqual(proxyMayOnlyLower);
  });

  it('reads a policy as one sentence', () => {
    const channel = (id: string) => ({ 'mobile-id': 'MOBILE', 'touch-id': 'TOUCH' })[id] ?? id;
    const delay = [{ type: 'SET_EFFECTIVE_AT', instant: { var: 'facts.coolingOffEndsAt' } }];
    expect(describePolicy('ALLOW', null, ruleFromJsonLogic(coolingOff, delay), channel)).toBe(
      'Allow changes to any limit when the change type is UPDATE and it comes through MOBILE or TOUCH and the ' +
        'requested amount is above the current amount, then delay it until host fact coolingOffEndsAt.'
    );
    expect(describePolicy('DENY', 'DAILY_ALL', ruleFromJsonLogic(proxyMayOnlyLower, []), channel)).toBe(
      'Deny changes to DAILY_ALL when someone other than the owner asks and host fact proxyMayRaise is not true ' +
        'and (the change type is CREATE or the requested amount is above the current amount).'
    );
  });
});
