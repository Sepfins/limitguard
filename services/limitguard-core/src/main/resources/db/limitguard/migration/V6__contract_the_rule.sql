-- A limit rule is an owner's amount for one configuration, optionally narrowed to one
-- mandate. Kind, period, currency, scopes, usage mode, priority, and window come from the
-- configuration. One rule per target and one pending change per target are constraints;
-- requests, events, and revisions keep amounts instead of rule snapshots.

ALTER TABLE limit_rule
    DROP COLUMN name,
    DROP COLUMN description,
    DROP COLUMN limit_kind,
    DROP COLUMN period,
    DROP COLUMN currency,
    DROP COLUMN mandate_scope,
    DROP COLUMN channel_scope,
    DROP COLUMN channel_id,
    DROP COLUMN channel_group_id,
    DROP COLUMN operation_type_id,
    DROP COLUMN usage_consumption_mode,
    DROP COLUMN priority,
    DROP COLUMN starts_at,
    DROP COLUMN ends_at,
    ADD CONSTRAINT uq_limit_rule_target UNIQUE NULLS NOT DISTINCT (
        tenant_id,
        owner_party_profile_id,
        configuration_id,
        mandate_id
    );

DROP INDEX IF EXISTS idx_limit_rule_configuration;

DROP TYPE limit_mandate_scope;

COMMENT ON TABLE limit_rule IS
    'An owner''s granted amount, or explicit unlimited, for one limit configuration, optionally narrowed to one mandate. The configuration supplies kind, period, currency, scopes, usage mode, and priority.';

COMMENT ON COLUMN limit_rule.mandate_id IS
    'NULL applies to the direct owner and every active mandate of the owner; a value narrows the rule to one owner-to-assignee funding envelope.';

COMMENT ON COLUMN limit_rule.amount IS
    'Granted amount in the configuration currency; NULL exactly when amount_unlimited.';

ALTER TABLE limit_rule_change_request
    DROP CONSTRAINT chk_limit_rule_change_request_target_rule,
    DROP CONSTRAINT chk_limit_rule_change_request_json,
    DROP COLUMN target_scope,
    DROP COLUMN target_scope_hash,
    DROP COLUMN before_rule,
    DROP COLUMN requested_rule,
    DROP COLUMN approved_rule,
    ADD COLUMN configuration_id UUID NOT NULL,
    ADD COLUMN before_amount NUMERIC(20, 4),
    ADD COLUMN before_unlimited BOOLEAN,
    ADD COLUMN requested_amount NUMERIC(20, 4),
    ADD COLUMN requested_unlimited BOOLEAN NOT NULL DEFAULT false,
    ADD FOREIGN KEY (tenant_id, configuration_id)
        REFERENCES limit_configuration(tenant_id, id),
    ADD CONSTRAINT chk_limit_rule_change_request_requested_amount CHECK (
        (requested_amount IS NULL) = requested_unlimited AND
        (requested_amount IS NULL OR requested_amount > 0)
    ),
    ADD CONSTRAINT chk_limit_rule_change_request_target_rule CHECK (
        (
            change_type = 'CREATE' AND
            before_unlimited IS NULL AND
            before_amount IS NULL
        ) OR
        (
            change_type <> 'CREATE' AND
            limit_rule_id IS NOT NULL AND
            before_unlimited IS NOT NULL AND
            (before_amount IS NULL) = before_unlimited AND
            (before_amount IS NULL OR before_amount > 0)
        )
    ),
    ADD CONSTRAINT chk_limit_rule_change_request_json CHECK (
        jsonb_typeof(policy_context) = 'object' AND
        jsonb_typeof(metadata) = 'object'
    );

COMMENT ON COLUMN limit_rule_change_request.configuration_id IS
    'Configuration of the target rule. With the owner and mandate it names the target, even before the rule exists.';

COMMENT ON COLUMN limit_rule_change_request.before_amount IS
    'Rule amount when the change was requested; NULL for CREATE and when before_unlimited.';

COMMENT ON COLUMN limit_rule_change_request.requested_amount IS
    'Amount the rule has once the change applies; NULL exactly when requested_unlimited.';

CREATE UNIQUE INDEX uq_limit_rule_change_request_pending_target
    ON limit_rule_change_request (
        tenant_id,
        target_owner_party_profile_id,
        configuration_id,
        target_mandate_id
    )
    NULLS NOT DISTINCT
    WHERE status IN (
        'REQUESTED',
        'APPROVED',
        'PENDING_EFFECTIVE_DATE'
    );

ALTER TABLE limit_rule_change_event
    DROP CONSTRAINT chk_limit_rule_change_event_json,
    DROP COLUMN before_rule,
    DROP COLUMN after_rule,
    ADD CONSTRAINT chk_limit_rule_change_event_json CHECK (
        jsonb_typeof(event_payload) = 'object'
    );

ALTER TABLE limit_rule_revision
    DROP CONSTRAINT chk_limit_rule_revision_json,
    DROP CONSTRAINT chk_limit_rule_revision_json_types,
    DROP COLUMN before_rule,
    DROP COLUMN after_rule,
    ADD COLUMN before_amount NUMERIC(20, 4),
    ADD COLUMN before_unlimited BOOLEAN,
    ADD COLUMN after_amount NUMERIC(20, 4),
    ADD COLUMN after_unlimited BOOLEAN NOT NULL DEFAULT false,
    ADD CONSTRAINT chk_limit_rule_revision_amounts CHECK (
        (before_amount IS NULL OR before_unlimited IS NOT NULL) AND
        (before_unlimited IS NULL OR (before_amount IS NULL) = before_unlimited) AND
        (before_amount IS NULL OR before_amount > 0) AND
        (after_amount IS NULL) = after_unlimited AND
        (after_amount IS NULL OR after_amount > 0)
    ),
    ADD CONSTRAINT chk_limit_rule_revision_json_types CHECK (
        jsonb_typeof(metadata) = 'object'
    );

COMMENT ON TABLE limit_rule_revision IS
    'Immutable applied-change history for limit_rule, linking each version to the request, actor, and before and after amounts.';
