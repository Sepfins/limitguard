DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_type
        WHERE typname = 'no_matching_rule_behavior'
    ) THEN
        CREATE TYPE no_matching_rule_behavior AS ENUM (
            'ALLOW',
            'DENY',
            'NOT_APPLICABLE'
        );
    END IF;
END
$$;

ALTER TABLE tenant
    ADD COLUMN IF NOT EXISTS no_matching_rule_behavior no_matching_rule_behavior NOT NULL DEFAULT 'DENY';

COMMENT ON COLUMN tenant.no_matching_rule_behavior IS
    'Behavior when LimitGuard resolves valid funding authority but no active rule matches. DENY fails closed; NOT_APPLICABLE delegates the final decision to the consuming application; ALLOW explicitly permits an unbounded LimitGuard authorization.';
