-- Parent relationships are authoritative. The legacy closure table is not maintained.
CREATE FUNCTION assert_operation_type_integrity()
RETURNS VOID AS $$
DECLARE
    invalid_id UUID;
    invalid_reason TEXT;
BEGIN
    SELECT child.id,
           CASE
               WHEN parent.id IS NULL THEN 'missing parent'
               WHEN child.is_global AND NOT parent.is_global THEN 'global node has a tenant-private parent'
               WHEN NOT child.is_global AND NOT parent.is_global AND child.tenant_id <> parent.tenant_id
                   THEN 'parent belongs to another tenant'
               WHEN parent.is_leaf THEN 'declared leaf has children'
           END
    INTO invalid_id, invalid_reason
    FROM operation_type child
    LEFT JOIN operation_type parent ON parent.id = child.parent_id
    WHERE child.parent_id IS NOT NULL
      AND (parent.id IS NULL
           OR (child.is_global AND NOT parent.is_global)
           OR (NOT child.is_global AND NOT parent.is_global AND child.tenant_id <> parent.tenant_id)
           OR parent.is_leaf)
    ORDER BY child.id
    LIMIT 1;

    IF invalid_id IS NULL THEN
        WITH RECURSIVE ancestry AS (
            SELECT id AS selected_id, id, parent_id FROM operation_type
            UNION ALL
            SELECT child.selected_id, parent.id, parent.parent_id
            FROM ancestry child
            JOIN operation_type parent ON parent.id = child.parent_id
        ) CYCLE id SET is_cycle USING visited_ids
        SELECT selected_id, 'cyclic ancestry'
        INTO invalid_id, invalid_reason
        FROM ancestry
        WHERE is_cycle
        ORDER BY selected_id
        LIMIT 1;
    END IF;

    IF invalid_id IS NOT NULL THEN
        RAISE EXCEPTION 'Invalid operation taxonomy at %: %', invalid_id, invalid_reason
            USING ERRCODE = '23514',
                  HINT = 'Correct operation_type explicitly before retrying; leaf flags and historical evidence are never repaired automatically.';
    END IF;
END;
$$ LANGUAGE plpgsql;

-- Fail the upgrade before installing guards rather than reclassifying existing data.
LOCK TABLE operation_type IN SHARE ROW EXCLUSIVE MODE;
SELECT assert_operation_type_integrity();

CREATE TABLE operation_type_catalog_guard (
    singleton BOOLEAN PRIMARY KEY DEFAULT true CHECK (singleton)
);
INSERT INTO operation_type_catalog_guard (singleton) VALUES (true);

COMMENT ON TABLE operation_type_catalog_guard IS
    'Internal serialization row for operation taxonomy writes. A real row update also prevents stale-snapshot write skew under repeatable-read isolation.';

CREATE FUNCTION lock_operation_type_catalog()
RETURNS TRIGGER AS $$
BEGIN
    -- Lock before catalog rows; updating also makes stale snapshot writers abort.
    UPDATE operation_type_catalog_guard SET singleton = singleton WHERE singleton;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Operation taxonomy serialization row is missing' USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE FUNCTION enforce_operation_type_integrity()
RETURNS TRIGGER AS $$
BEGIN
    PERFORM assert_operation_type_integrity();
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

-- Bind guards to their owning schema, including schema-qualified writes by other callers.
DO $$
DECLARE
    schema_name TEXT := current_schema();
    function_name TEXT;
BEGIN
    FOREACH function_name IN ARRAY ARRAY[
        'assert_operation_type_integrity', 'lock_operation_type_catalog', 'enforce_operation_type_integrity'
    ] LOOP
        EXECUTE format('ALTER FUNCTION %I.%I() SET search_path = %I, pg_temp',
                       schema_name, function_name, schema_name);
    END LOOP;
END;
$$;

CREATE TRIGGER trg_operation_type_catalog_lock
    BEFORE INSERT OR UPDATE OR DELETE ON operation_type
    FOR EACH STATEMENT EXECUTE FUNCTION lock_operation_type_catalog();

CREATE TRIGGER trg_operation_type_integrity
    AFTER INSERT OR UPDATE OR DELETE ON operation_type
    FOR EACH STATEMENT EXECUTE FUNCTION enforce_operation_type_integrity();

COMMENT ON COLUMN operation_type.is_leaf IS
    'Declared terminal node: may not have children. Non-leaf nodes remain valid transaction targets, including before any children are registered.';
