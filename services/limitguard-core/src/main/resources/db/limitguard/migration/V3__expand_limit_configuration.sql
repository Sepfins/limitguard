-- The configuration owns usage consumption mode, the configuration maximum and the rule
-- amount may each be explicitly unlimited, and every rule references a configuration.

ALTER TABLE limit_configuration
    ADD COLUMN usage_consumption_mode limit_usage_consumption_mode NOT NULL DEFAULT 'CASCADING',
    ADD COLUMN max_assignable_unlimited BOOLEAN NOT NULL DEFAULT false,
    ALTER COLUMN max_assignable_amount DROP NOT NULL,
    DROP CONSTRAINT chk_limit_configuration_amounts,
    ADD CONSTRAINT chk_limit_configuration_amounts CHECK (
        (max_assignable_amount IS NULL) = max_assignable_unlimited AND
        (max_assignable_amount IS NULL OR max_assignable_amount > 0) AND
        (
            default_amount IS NULL OR
            (
                default_amount > 0 AND
                (max_assignable_unlimited OR default_amount <= max_assignable_amount)
            )
        )
    ),
    ADD CONSTRAINT chk_limit_configuration_usage_consumption_mode CHECK (
        limit_kind = 'PERIODIC' OR usage_consumption_mode = 'CASCADING'
    );

COMMENT ON COLUMN limit_configuration.max_assignable_amount IS
    'Largest limit amount the tenant allows to be granted for this scope; NULL exactly when max_assignable_unlimited.';

COMMENT ON COLUMN limit_configuration.max_assignable_unlimited IS
    'True when rules under this configuration may be granted an unlimited amount.';

CREATE OR REPLACE FUNCTION enforce_limit_configuration_parent()
RETURNS TRIGGER AS $$
DECLARE
    parent_configuration limit_configuration%ROWTYPE;
    has_cycle BOOLEAN;
BEGIN
    IF NEW.parent_configuration_id IS NOT NULL THEN
        WITH RECURSIVE ancestors AS (
            SELECT id, parent_configuration_id
            FROM limit_configuration
            WHERE tenant_id = NEW.tenant_id
              AND id = NEW.parent_configuration_id

            UNION ALL

            SELECT parent.id, parent.parent_configuration_id
            FROM limit_configuration parent
            JOIN ancestors child
              ON parent.tenant_id = NEW.tenant_id
             AND parent.id = child.parent_configuration_id
        )
        SELECT EXISTS (
            SELECT 1
            FROM ancestors
            WHERE id = NEW.id
        )
        INTO has_cycle;

        IF has_cycle THEN
            RAISE EXCEPTION 'limit_configuration hierarchy cannot contain cycles';
        END IF;

        SELECT *
        INTO parent_configuration
        FROM limit_configuration
        WHERE tenant_id = NEW.tenant_id
          AND id = NEW.parent_configuration_id;

        IF FOUND THEN
            IF parent_configuration.limit_kind <> NEW.limit_kind OR
               parent_configuration.period IS DISTINCT FROM NEW.period OR
               parent_configuration.currency <> NEW.currency THEN
                RAISE EXCEPTION 'limit_configuration child must match parent kind, period, and currency';
            END IF;

            IF (NEW.max_assignable_unlimited AND NOT parent_configuration.max_assignable_unlimited) OR
               NEW.max_assignable_amount > parent_configuration.max_assignable_amount THEN
                RAISE EXCEPTION 'limit_configuration child max cannot exceed parent max';
            END IF;

            IF NEW.default_amount IS NOT NULL AND
               NEW.default_amount > parent_configuration.max_assignable_amount THEN
                RAISE EXCEPTION 'limit_configuration child default cannot exceed parent max';
            END IF;
        END IF;
    END IF;

    IF EXISTS (
        SELECT 1
        FROM limit_configuration child
        WHERE child.tenant_id = NEW.tenant_id
          AND child.parent_configuration_id = NEW.id
          AND (
              child.limit_kind <> NEW.limit_kind OR
              child.period IS DISTINCT FROM NEW.period OR
              child.currency <> NEW.currency OR
              (child.max_assignable_unlimited AND NOT NEW.max_assignable_unlimited) OR
              child.max_assignable_amount > NEW.max_assignable_amount OR
              child.default_amount > NEW.max_assignable_amount
          )
    ) THEN
        RAISE EXCEPTION 'limit_configuration parent cannot conflict with existing child configurations';
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER trg_limit_configuration_parent ON limit_configuration;

CREATE TRIGGER trg_limit_configuration_parent
    BEFORE INSERT OR UPDATE OF
        parent_configuration_id,
        limit_kind,
        period,
        currency,
        max_assignable_amount,
        max_assignable_unlimited,
        default_amount
    ON limit_configuration
    FOR EACH ROW
    EXECUTE FUNCTION enforce_limit_configuration_parent();

ALTER TABLE limit_rule
    ADD COLUMN amount_unlimited BOOLEAN NOT NULL DEFAULT false,
    ALTER COLUMN amount DROP NOT NULL,
    ALTER COLUMN configuration_id SET NOT NULL,
    DROP CONSTRAINT chk_limit_rule_amount,
    ADD CONSTRAINT chk_limit_rule_amount CHECK (
        (amount IS NULL) = amount_unlimited AND
        (amount IS NULL OR amount > 0)
    );

COMMENT ON COLUMN limit_rule.amount IS
    'Granted amount in the rule currency; NULL exactly when amount_unlimited.';

COMMENT ON COLUMN limit_rule.amount_unlimited IS
    'True when the owner removed the cap. Allowed only under a configuration whose maximum is unlimited.';

COMMENT ON COLUMN limit_rule.configuration_id IS
    'Configuration this rule is an amount for. The configuration bounds the amount.';
