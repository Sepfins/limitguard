-- ============================================================================
-- Tenancy and external identifiers
-- ============================================================================

CREATE TABLE tenant (
    id UUID PRIMARY KEY DEFAULT uuidv7(),
    name TEXT NOT NULL,
    config JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (name)
);

CREATE TABLE identifier_scheme (
    id UUID PRIMARY KEY DEFAULT uuidv7(),
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    entity_type VARCHAR(50) NOT NULL,
    scheme_name VARCHAR(100) NOT NULL,
    format_regex VARCHAR(500),
    max_length INT,
    is_primary BOOLEAN NOT NULL DEFAULT false,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, entity_type, scheme_name),
    CONSTRAINT chk_identifier_scheme_max_length CHECK (
        max_length IS NULL OR max_length > 0
    )
);

CREATE UNIQUE INDEX uq_identifier_scheme_primary
    ON identifier_scheme (tenant_id, entity_type)
    WHERE is_primary;

CREATE TABLE entity_identifier (
    id UUID PRIMARY KEY DEFAULT uuidv7(),
    entity_type VARCHAR(50) NOT NULL,
    entity_id UUID NOT NULL,
    scheme_id UUID NOT NULL REFERENCES identifier_scheme(id),
    identifier VARCHAR(500) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (scheme_id, identifier)
);

CREATE INDEX idx_entity_identifier_entity
    ON entity_identifier (entity_type, entity_id);

-- ============================================================================
-- Parties, tenant profiles, and delegated authority
-- ============================================================================

CREATE TYPE party_type AS ENUM ('INDIVIDUAL', 'LEGAL_ENTITY');

CREATE TABLE party (
    id UUID PRIMARY KEY DEFAULT uuidv7(),
    party_type party_type NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE party_legal_identifier (
    id UUID PRIMARY KEY DEFAULT uuidv7(),
    party_id UUID NOT NULL REFERENCES party(id) ON DELETE CASCADE,
    jurisdiction_country CHAR(2) NOT NULL,
    identifier_type VARCHAR(100) NOT NULL,
    normalized_identifier VARCHAR(100) NOT NULL,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (jurisdiction_country, identifier_type, normalized_identifier),
    UNIQUE (party_id, jurisdiction_country, identifier_type),
    CONSTRAINT chk_party_legal_identifier_country CHECK (
        jurisdiction_country ~ '^[A-Z]{2}$'
    ),
    CONSTRAINT chk_party_legal_identifier_type CHECK (
        identifier_type ~ '^[A-Z][A-Z0-9_]{0,99}$'
    ),
    CONSTRAINT chk_party_legal_identifier_value CHECK (
        length(normalized_identifier) > 0 AND
        normalized_identifier = btrim(normalized_identifier) AND
        normalized_identifier = upper(normalized_identifier)
    )
);

COMMENT ON TABLE party_legal_identifier IS
    'Globally unique legal/national identifiers for a canonical party, such as IT CODICE_FISCALE or IT PARTITA_IVA. These identify the party independently of tenant registration.';

COMMENT ON COLUMN party_legal_identifier.normalized_identifier IS
    'Canonicalized identifier value used for uniqueness and lookup. Applications should strip formatting and apply jurisdiction/type-specific normalization before persistence.';

CREATE INDEX idx_party_legal_identifier_party
    ON party_legal_identifier (party_id);

CREATE TABLE individual (
    party_id UUID PRIMARY KEY REFERENCES party(id) ON DELETE CASCADE,
    first_name VARCHAR(255) NOT NULL,
    last_name VARCHAR(255) NOT NULL,
    birth_date DATE
);

CREATE TABLE legal_entity (
    party_id UUID PRIMARY KEY REFERENCES party(id) ON DELETE CASCADE,
    legal_name VARCHAR(255) NOT NULL,
    legal_form VARCHAR(100)
);

CREATE TABLE party_profile (
    id UUID PRIMARY KEY DEFAULT uuidv7(),
    party_id UUID NOT NULL REFERENCES party(id),
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (party_id, tenant_id),
    UNIQUE (tenant_id, id)
);

CREATE TYPE party_profile_delegation_type AS ENUM (
    'GUARDIAN',
    'LEGAL_REPRESENTATIVE',
    'DELEGATE',
    'AUTHORIZED_USER'
);

CREATE TABLE party_profile_delegation (
    id UUID PRIMARY KEY DEFAULT uuidv7(),
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    owner_party_profile_id UUID NOT NULL,
    delegate_party_profile_id UUID NOT NULL,
    delegation_type party_profile_delegation_type NOT NULL,
    starts_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    ends_at TIMESTAMPTZ,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (
        tenant_id,
        owner_party_profile_id,
        delegate_party_profile_id,
        delegation_type
    ),
    UNIQUE (tenant_id, id),
    UNIQUE (
        tenant_id,
        id,
        owner_party_profile_id,
        delegate_party_profile_id
    ),
    FOREIGN KEY (tenant_id, owner_party_profile_id)
        REFERENCES party_profile(tenant_id, id),
    FOREIGN KEY (tenant_id, delegate_party_profile_id)
        REFERENCES party_profile(tenant_id, id),
    CONSTRAINT chk_party_profile_delegation_distinct_profiles CHECK (
        owner_party_profile_id <> delegate_party_profile_id
    ),
    CONSTRAINT chk_party_profile_delegation_window CHECK (
        ends_at IS NULL OR ends_at > starts_at
    )
);

CREATE INDEX idx_party_profile_delegation_owner
    ON party_profile_delegation (tenant_id, owner_party_profile_id);

CREATE INDEX idx_party_profile_delegation_delegate
    ON party_profile_delegation (tenant_id, delegate_party_profile_id);

CREATE TABLE bank_worker (
    id UUID PRIMARY KEY DEFAULT uuidv7(),
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    external_reference VARCHAR(255) NOT NULL,
    display_name VARCHAR(255) NOT NULL,
    branch_code VARCHAR(100),
    is_active BOOLEAN NOT NULL DEFAULT true,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, external_reference)
);

COMMENT ON TABLE bank_worker IS
    'Tenant-scoped bank staff who can perform assisted limit changes for customers.';

-- ============================================================================
-- Funding sources and owner-defined groups
-- ============================================================================

CREATE TYPE funding_source_category AS ENUM (
    'TRANSACTIONAL',   -- conto corrente, conto di pagamento
    'SAVINGS',         -- conto deposito libero, deposito a risparmio
    'TERM_DEPOSIT',    -- conto deposito vincolato, certificato di deposito
    'PREPAID',         -- carta prepagata con IBAN, carta ricaricabile
    'CREDIT',          -- fido, linea di credito, carta di credito
    'SECURITIES',      -- dossier titoli, conto titoli
    'EWALLET'          -- wallet digitale EMI-issued, non bancario
);

CREATE TYPE product_node_type AS ENUM (
    'FAMILY',       -- e.g. "Conto Corrente"
    'SUBFAMILY',    -- e.g. "Conto Corrente Estero"
    'PRODUCT'       -- e.g. "CC1000S0", the actual forma tecnica
);

CREATE TABLE funding_source_product (
    id UUID PRIMARY KEY DEFAULT uuidv7(),
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    parent_id UUID,
    account_category funding_source_category NOT NULL,
    node_type product_node_type NOT NULL,
    code VARCHAR(100) NOT NULL,
    name VARCHAR(255) NOT NULL,
    is_leaf BOOLEAN NOT NULL DEFAULT false, -- Only leaf nodes can be assigned to funding sources
    is_active BOOLEAN NOT NULL DEFAULT true,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, code),
    FOREIGN KEY (tenant_id, parent_id)
        REFERENCES funding_source_product(tenant_id, id),
    CONSTRAINT chk_funding_source_product_parent CHECK (
        parent_id IS NULL OR parent_id <> id
    )
);

CREATE TABLE funding_source (
    id UUID PRIMARY KEY DEFAULT uuidv7(),
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    product_id UUID NOT NULL,
    external_reference VARCHAR(255),
    is_active BOOLEAN NOT NULL DEFAULT true,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, product_id)
        REFERENCES funding_source_product(tenant_id, id)
);

CREATE UNIQUE INDEX uq_funding_source_external_reference
    ON funding_source (tenant_id, external_reference)
    WHERE external_reference IS NOT NULL;

CREATE TYPE funding_source_owner_role AS ENUM (
    'PRIMARY_OWNER',
    'JOINT_OWNER',
    'BENEFICIAL_OWNER'
);

CREATE TABLE funding_source_owner (
    id UUID PRIMARY KEY DEFAULT uuidv7(),
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    party_profile_id UUID NOT NULL,
    funding_source_id UUID NOT NULL,
    owner_role funding_source_owner_role NOT NULL DEFAULT 'PRIMARY_OWNER',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, party_profile_id, funding_source_id),
    FOREIGN KEY (tenant_id, party_profile_id)
        REFERENCES party_profile(tenant_id, id),
    FOREIGN KEY (tenant_id, funding_source_id)
        REFERENCES funding_source(tenant_id, id)
);

CREATE INDEX idx_funding_source_owner_source
    ON funding_source_owner (tenant_id, funding_source_id);

CREATE TABLE funding_source_group (
    id UUID PRIMARY KEY DEFAULT uuidv7(),
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    owner_party_profile_id UUID NOT NULL,
    code VARCHAR(100) NOT NULL,
    name VARCHAR(255) NOT NULL,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, owner_party_profile_id, id),
    UNIQUE (tenant_id, owner_party_profile_id, code),
    FOREIGN KEY (tenant_id, owner_party_profile_id)
        REFERENCES party_profile(tenant_id, id)
);

CREATE TABLE funding_source_group_member (
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    group_id UUID NOT NULL,
    owner_party_profile_id UUID NOT NULL,
    funding_source_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (group_id, funding_source_id),
    FOREIGN KEY (tenant_id, group_id, owner_party_profile_id)
        REFERENCES funding_source_group(tenant_id, id, owner_party_profile_id)
        ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, owner_party_profile_id, funding_source_id)
        REFERENCES funding_source_owner(tenant_id, party_profile_id, funding_source_id)
        ON DELETE CASCADE
);

CREATE INDEX idx_funding_source_group_member_source
    ON funding_source_group_member (tenant_id, funding_source_id);

-- ============================================================================
-- Channels
-- ============================================================================

CREATE TABLE channel (
    id UUID PRIMARY KEY DEFAULT uuidv7(),
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    code VARCHAR(100) NOT NULL,
    name VARCHAR(255) NOT NULL,
    is_active BOOLEAN NOT NULL DEFAULT true,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, code)
);

CREATE TABLE channel_group (
    id UUID PRIMARY KEY DEFAULT uuidv7(),
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    code VARCHAR(100) NOT NULL,
    name VARCHAR(255) NOT NULL,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, code)
);

CREATE TABLE channel_group_member (
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    group_id UUID NOT NULL,
    channel_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (group_id, channel_id),
    FOREIGN KEY (tenant_id, group_id)
        REFERENCES channel_group(tenant_id, id)
        ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, channel_id)
        REFERENCES channel(tenant_id, id)
        ON DELETE CASCADE
);

CREATE INDEX idx_channel_group_member_channel
    ON channel_group_member (tenant_id, channel_id);

-- ============================================================================
-- Operation taxonomy
-- ============================================================================

CREATE TABLE operation_type (
    id UUID PRIMARY KEY DEFAULT uuidv7(),
    tenant_id UUID REFERENCES tenant(id),
    parent_id UUID REFERENCES operation_type(id),
    code VARCHAR(100) NOT NULL,
    name VARCHAR(255) NOT NULL,
    is_leaf BOOLEAN NOT NULL DEFAULT false,
    is_global BOOLEAN NOT NULL DEFAULT false,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_operation_type_tenant_scope CHECK (
        (is_global = true AND tenant_id IS NULL) OR
        (is_global = false AND tenant_id IS NOT NULL)
    )
);

CREATE UNIQUE INDEX uq_operation_type_global_code
    ON operation_type (code)
    WHERE tenant_id IS NULL;

CREATE UNIQUE INDEX uq_operation_type_tenant_code
    ON operation_type (tenant_id, code)
    WHERE tenant_id IS NOT NULL;

CREATE INDEX idx_operation_type_parent
    ON operation_type (parent_id);

CREATE TABLE operation_type_closure (
    ancestor_id UUID NOT NULL REFERENCES operation_type(id) ON DELETE CASCADE,
    descendant_id UUID NOT NULL REFERENCES operation_type(id) ON DELETE CASCADE,
    depth INT NOT NULL,
    PRIMARY KEY (ancestor_id, descendant_id),
    CONSTRAINT chk_operation_type_closure_depth CHECK (depth >= 0)
);

-- ============================================================================
-- Transactions and transaction events
-- ============================================================================

CREATE TYPE transaction_status AS ENUM (
    'AUTHORIZED',
    'CAPTURED',
    'PARTIALLY_CAPTURED',
    'VOIDED',
    'REVERSED',
    'EXPIRED'
);

CREATE TABLE payment_transaction (
    id UUID PRIMARY KEY DEFAULT uuidv7(),
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    acting_party_profile_id UUID NOT NULL,
    funding_source_id UUID NOT NULL,
    operation_type_id UUID NOT NULL REFERENCES operation_type(id),
    channel_id UUID NOT NULL,
    authorized_amount NUMERIC(20, 4) NOT NULL,
    captured_amount NUMERIC(20, 4) NOT NULL DEFAULT 0,
    currency CHAR(3) NOT NULL,
    status transaction_status NOT NULL DEFAULT 'AUTHORIZED',
    idempotency_key VARCHAR(255),
    expires_at TIMESTAMPTZ,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, acting_party_profile_id)
        REFERENCES party_profile(tenant_id, id),
    FOREIGN KEY (tenant_id, funding_source_id)
        REFERENCES funding_source(tenant_id, id),
    FOREIGN KEY (tenant_id, channel_id)
        REFERENCES channel(tenant_id, id),
    CONSTRAINT chk_payment_transaction_authorized_amount CHECK (
        authorized_amount > 0
    ),
    CONSTRAINT chk_payment_transaction_captured_amount CHECK (
        captured_amount >= 0 AND captured_amount <= authorized_amount
    ),
    CONSTRAINT chk_payment_transaction_currency CHECK (
        currency ~ '^[A-Z]{3}$'
    )
);

CREATE UNIQUE INDEX uq_payment_transaction_idempotency
    ON payment_transaction (tenant_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;

CREATE INDEX idx_payment_transaction_actor
    ON payment_transaction (tenant_id, acting_party_profile_id, created_at);

CREATE INDEX idx_payment_transaction_funding_source
    ON payment_transaction (tenant_id, funding_source_id, created_at);

CREATE INDEX idx_payment_transaction_channel
    ON payment_transaction (tenant_id, channel_id, created_at);

CREATE TYPE transaction_event_type AS ENUM (
    'AUTHORIZATION',
    'CAPTURE',
    'PARTIAL_CAPTURE',
    'VOID',
    'REVERSAL',
    'EXPIRY',
    'ADJUSTMENT'
);

CREATE TABLE transaction_event (
    id UUID PRIMARY KEY DEFAULT uuidv7(),
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    payment_transaction_id UUID NOT NULL,
    event_type transaction_event_type NOT NULL,
    amount NUMERIC(20, 4),
    reason VARCHAR(255),
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, payment_transaction_id)
        REFERENCES payment_transaction(tenant_id, id)
        ON DELETE CASCADE,
    CONSTRAINT chk_transaction_event_amount CHECK (
        amount IS NULL OR amount >= 0
    )
);

CREATE INDEX idx_transaction_event_transaction
    ON transaction_event (tenant_id, payment_transaction_id, created_at);

-- ============================================================================
-- Limits
-- ============================================================================

CREATE TYPE limit_kind AS ENUM ('PER_TRANSACTION', 'PERIODIC');
CREATE TYPE limit_period AS ENUM ('DAILY', 'MONTHLY');
CREATE TYPE limit_funding_scope AS ENUM (
    'ALL_OWNER_SOURCES',
    'FUNDING_SOURCE',
    'FUNDING_SOURCE_GROUP'
);
CREATE TYPE limit_channel_scope AS ENUM (
    'ALL_CHANNELS',
    'CHANNEL',
    'CHANNEL_GROUP'
);
CREATE TYPE limit_mandate_scope AS ENUM (
    'ALL_OWNER_MANDATES',
    'MANDATE'
);
CREATE TYPE limit_configuration_funding_scope AS ENUM (
    'ALL_FUNDING_SOURCES',
    'FUNDING_SOURCE_CATEGORY',
    'FUNDING_SOURCE_PRODUCT'
);
CREATE TYPE limit_usage_consumption_mode AS ENUM (
    'CASCADING',
    'ISOLATED'
);

CREATE TABLE limit_configuration (
    id UUID PRIMARY KEY DEFAULT uuidv7(),
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    parent_configuration_id UUID,
    code VARCHAR(100) NOT NULL,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    limit_kind limit_kind NOT NULL,
    period limit_period,
    currency CHAR(3) NOT NULL,
    funding_scope limit_configuration_funding_scope NOT NULL DEFAULT 'ALL_FUNDING_SOURCES',
    funding_source_category funding_source_category,
    funding_source_product_id UUID,
    channel_scope limit_channel_scope NOT NULL DEFAULT 'ALL_CHANNELS',
    channel_id UUID,
    channel_group_id UUID,
    operation_type_id UUID REFERENCES operation_type(id),
    max_assignable_amount NUMERIC(20, 4) NOT NULL,
    default_amount NUMERIC(20, 4),
    priority INT NOT NULL DEFAULT 100,
    is_active BOOLEAN NOT NULL DEFAULT true,
    starts_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    ends_at TIMESTAMPTZ,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, code),
    FOREIGN KEY (tenant_id, parent_configuration_id)
        REFERENCES limit_configuration(tenant_id, id),
    FOREIGN KEY (tenant_id, funding_source_product_id)
        REFERENCES funding_source_product(tenant_id, id),
    FOREIGN KEY (tenant_id, channel_id)
        REFERENCES channel(tenant_id, id),
    FOREIGN KEY (tenant_id, channel_group_id)
        REFERENCES channel_group(tenant_id, id),
    CONSTRAINT chk_limit_configuration_kind_period CHECK (
        (limit_kind = 'PER_TRANSACTION' AND period IS NULL) OR
        (limit_kind = 'PERIODIC' AND period IS NOT NULL)
    ),
    CONSTRAINT chk_limit_configuration_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT chk_limit_configuration_parent CHECK (
        parent_configuration_id IS NULL OR parent_configuration_id <> id
    ),
    CONSTRAINT chk_limit_configuration_funding_scope CHECK (
        (
            funding_scope = 'ALL_FUNDING_SOURCES' AND
            funding_source_category IS NULL AND
            funding_source_product_id IS NULL
        ) OR
        (
            funding_scope = 'FUNDING_SOURCE_CATEGORY' AND
            funding_source_category IS NOT NULL AND
            funding_source_product_id IS NULL
        ) OR
        (
            funding_scope = 'FUNDING_SOURCE_PRODUCT' AND
            funding_source_category IS NULL AND
            funding_source_product_id IS NOT NULL
        )
    ),
    CONSTRAINT chk_limit_configuration_channel_scope CHECK (
        (
            channel_scope = 'ALL_CHANNELS' AND
            channel_id IS NULL AND
            channel_group_id IS NULL
        ) OR
        (
            channel_scope = 'CHANNEL' AND
            channel_id IS NOT NULL AND
            channel_group_id IS NULL
        ) OR
        (
            channel_scope = 'CHANNEL_GROUP' AND
            channel_id IS NULL AND
            channel_group_id IS NOT NULL
        )
    ),
    CONSTRAINT chk_limit_configuration_amounts CHECK (
        max_assignable_amount > 0 AND
        (
            default_amount IS NULL OR
            (
                default_amount > 0 AND
                default_amount <= max_assignable_amount
            )
        )
    ),
    CONSTRAINT chk_limit_configuration_priority CHECK (priority >= 0),
    CONSTRAINT chk_limit_configuration_window CHECK (
        ends_at IS NULL OR ends_at > starts_at
    )
);

CREATE FUNCTION enforce_limit_configuration_parent()
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

            IF NEW.max_assignable_amount > parent_configuration.max_assignable_amount THEN
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
              child.max_assignable_amount > NEW.max_assignable_amount OR
              child.default_amount > NEW.max_assignable_amount
          )
    ) THEN
        RAISE EXCEPTION 'limit_configuration parent cannot conflict with existing child configurations';
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_limit_configuration_parent
    BEFORE INSERT OR UPDATE OF
        parent_configuration_id,
        limit_kind,
        period,
        currency,
        max_assignable_amount,
        default_amount
    ON limit_configuration
    FOR EACH ROW
    EXECUTE FUNCTION enforce_limit_configuration_parent();

COMMENT ON TABLE limit_configuration IS
    'Tenant-owned catalog of maximum assignable and default limit values for a scoped kind, period, channel, operation, and funding-source taxonomy.';

COMMENT ON COLUMN limit_configuration.parent_configuration_id IS
    'Optional broader configuration cap. Child configurations must be evaluated against their ancestors so a specific cap/default cannot exceed a broader tenant cap.';

COMMENT ON COLUMN limit_configuration.max_assignable_amount IS
    'Largest limit amount the tenant allows to be granted for this scope.';

COMMENT ON COLUMN limit_configuration.default_amount IS
    'Default amount policies may apply when the matching limit rule does not exist.';

CREATE INDEX idx_limit_configuration_active
    ON limit_configuration (
        tenant_id,
        limit_kind,
        period,
        currency,
        is_active,
        priority
    );

CREATE INDEX idx_limit_configuration_parent
    ON limit_configuration (tenant_id, parent_configuration_id)
    WHERE parent_configuration_id IS NOT NULL;

CREATE INDEX idx_limit_configuration_channel
    ON limit_configuration (tenant_id, channel_id)
    WHERE channel_id IS NOT NULL;

CREATE INDEX idx_limit_configuration_channel_group
    ON limit_configuration (tenant_id, channel_group_id)
    WHERE channel_group_id IS NOT NULL;

CREATE INDEX idx_limit_configuration_operation
    ON limit_configuration (tenant_id, operation_type_id)
    WHERE operation_type_id IS NOT NULL;

CREATE INDEX idx_limit_configuration_funding_product
    ON limit_configuration (tenant_id, funding_source_product_id)
    WHERE funding_source_product_id IS NOT NULL;

CREATE TABLE mandate (
    id UUID PRIMARY KEY DEFAULT uuidv7(),
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    owner_party_profile_id UUID NOT NULL,
    assignee_party_profile_id UUID NOT NULL,
    authority_delegation_id UUID,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    funding_scope limit_funding_scope NOT NULL DEFAULT 'ALL_OWNER_SOURCES',
    funding_source_id UUID,
    funding_source_group_id UUID,
    is_active BOOLEAN NOT NULL DEFAULT true,
    version INT NOT NULL DEFAULT 1,
    starts_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    ends_at TIMESTAMPTZ,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, owner_party_profile_id, id),
    UNIQUE (tenant_id, owner_party_profile_id, name),
    FOREIGN KEY (tenant_id, owner_party_profile_id)
        REFERENCES party_profile(tenant_id, id),
    FOREIGN KEY (tenant_id, assignee_party_profile_id)
        REFERENCES party_profile(tenant_id, id),
    FOREIGN KEY (
        tenant_id,
        authority_delegation_id,
        owner_party_profile_id,
        assignee_party_profile_id
    )
        REFERENCES party_profile_delegation(
            tenant_id,
            id,
            owner_party_profile_id,
            delegate_party_profile_id
        ),
    FOREIGN KEY (tenant_id, owner_party_profile_id, funding_source_id)
        REFERENCES funding_source_owner(tenant_id, party_profile_id, funding_source_id),
    FOREIGN KEY (tenant_id, owner_party_profile_id, funding_source_group_id)
        REFERENCES funding_source_group(tenant_id, owner_party_profile_id, id),
    CONSTRAINT chk_mandate_distinct_profiles CHECK (
        owner_party_profile_id <> assignee_party_profile_id
    ),
    CONSTRAINT chk_mandate_funding_scope CHECK (
        (
            funding_scope = 'ALL_OWNER_SOURCES' AND
            funding_source_id IS NULL AND
            funding_source_group_id IS NULL
        ) OR
        (
            funding_scope = 'FUNDING_SOURCE' AND
            funding_source_id IS NOT NULL AND
            funding_source_group_id IS NULL
        ) OR
        (
            funding_scope = 'FUNDING_SOURCE_GROUP' AND
            funding_source_id IS NULL AND
            funding_source_group_id IS NOT NULL
        )
    ),
    CONSTRAINT chk_mandate_version CHECK (version > 0),
    CONSTRAINT chk_mandate_effective_window CHECK (
        ends_at IS NULL OR ends_at > starts_at
    )
);

COMMENT ON TABLE mandate IS
    'Owner-to-assignee funding envelope. A mandate says which funding sources are delegated; limit rules attach to one mandate or to the owner-wide scope shared by the direct owner and active mandates.';

COMMENT ON COLUMN mandate.authority_delegation_id IS
    'Optional authority relationship that justifies the mandate. When present it must match this mandate owner and assignee.';

CREATE INDEX idx_mandate_active_owner
    ON mandate (tenant_id, owner_party_profile_id, is_active, starts_at, ends_at);

CREATE INDEX idx_mandate_assignee
    ON mandate (tenant_id, assignee_party_profile_id, is_active, starts_at, ends_at);

CREATE INDEX idx_mandate_authority
    ON mandate (tenant_id, authority_delegation_id)
    WHERE authority_delegation_id IS NOT NULL;

CREATE INDEX idx_mandate_funding_source
    ON mandate (tenant_id, funding_source_id)
    WHERE funding_source_id IS NOT NULL;

CREATE INDEX idx_mandate_funding_source_group
    ON mandate (tenant_id, funding_source_group_id)
    WHERE funding_source_group_id IS NOT NULL;

CREATE TABLE limit_rule (
    id UUID PRIMARY KEY DEFAULT uuidv7(),
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    owner_party_profile_id UUID NOT NULL,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    limit_kind limit_kind NOT NULL,
    period limit_period,
    amount NUMERIC(20, 4) NOT NULL,
    currency CHAR(3) NOT NULL,
    mandate_scope limit_mandate_scope NOT NULL DEFAULT 'ALL_OWNER_MANDATES',
    mandate_id UUID,
    configuration_id UUID,
    channel_scope limit_channel_scope NOT NULL DEFAULT 'ALL_CHANNELS',
    channel_id UUID,
    channel_group_id UUID,
    operation_type_id UUID REFERENCES operation_type(id),
    usage_consumption_mode limit_usage_consumption_mode NOT NULL DEFAULT 'CASCADING',
    priority INT NOT NULL DEFAULT 100,
    is_active BOOLEAN NOT NULL DEFAULT true,
    version INT NOT NULL DEFAULT 1,
    starts_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    ends_at TIMESTAMPTZ,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, owner_party_profile_id, name),
    FOREIGN KEY (tenant_id, owner_party_profile_id)
        REFERENCES party_profile(tenant_id, id),
    FOREIGN KEY (tenant_id, owner_party_profile_id, mandate_id)
        REFERENCES mandate(tenant_id, owner_party_profile_id, id),
    FOREIGN KEY (tenant_id, configuration_id)
        REFERENCES limit_configuration(tenant_id, id),
    FOREIGN KEY (tenant_id, channel_id)
        REFERENCES channel(tenant_id, id),
    FOREIGN KEY (tenant_id, channel_group_id)
        REFERENCES channel_group(tenant_id, id),
    CONSTRAINT chk_limit_rule_kind_period CHECK (
        (limit_kind = 'PER_TRANSACTION' AND period IS NULL) OR
        (limit_kind = 'PERIODIC' AND period IS NOT NULL)
    ),
    CONSTRAINT chk_limit_rule_amount CHECK (amount > 0),
    CONSTRAINT chk_limit_rule_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT chk_limit_rule_mandate_scope CHECK (
        (
            mandate_scope = 'ALL_OWNER_MANDATES' AND
            mandate_id IS NULL
        ) OR
        (
            mandate_scope = 'MANDATE' AND
            mandate_id IS NOT NULL
        )
    ),
    CONSTRAINT chk_limit_rule_channel_scope CHECK (
        (
            channel_scope = 'ALL_CHANNELS' AND
            channel_id IS NULL AND
            channel_group_id IS NULL
        ) OR
        (
            channel_scope = 'CHANNEL' AND
            channel_id IS NOT NULL AND
            channel_group_id IS NULL
        ) OR
        (
            channel_scope = 'CHANNEL_GROUP' AND
            channel_id IS NULL AND
            channel_group_id IS NOT NULL
        )
    ),
    CONSTRAINT chk_limit_rule_usage_consumption_mode CHECK (
        limit_kind = 'PERIODIC' OR usage_consumption_mode = 'CASCADING'
    ),
    CONSTRAINT chk_limit_rule_priority CHECK (priority >= 0),
    CONSTRAINT chk_limit_rule_version CHECK (version > 0),
    CONSTRAINT chk_limit_rule_effective_window CHECK (
        ends_at IS NULL OR ends_at > starts_at
    )
);

COMMENT ON TABLE limit_rule IS
    'A limit rule combines a mandate selector, channel scope, operation type, amount, and period.';

COMMENT ON COLUMN limit_rule.owner_party_profile_id IS
    'The party profile that owns the funding sources governed by this rule.';

COMMENT ON COLUMN limit_rule.mandate_scope IS
    'ALL_OWNER_MANDATES applies to the direct owner and every active mandate of the owner; MANDATE applies only to one owner-to-assignee funding envelope.';

COMMENT ON COLUMN limit_rule.configuration_id IS
    'Configuration row that supplied or constrained this rule amount, when the rule was created from tenant configuration.';

COMMENT ON COLUMN limit_rule.usage_consumption_mode IS
    'For periodic rules, CASCADING continues to broader priority tiers while ISOLATED includes its peer tier and terminates the usage-bucket cascade for this owner. Per-transaction rules must use CASCADING and are always evaluated.';

CREATE INDEX idx_limit_rule_active_owner
    ON limit_rule (tenant_id, owner_party_profile_id, is_active, starts_at, ends_at);

CREATE INDEX idx_limit_rule_mandate
    ON limit_rule (tenant_id, mandate_id)
    WHERE mandate_id IS NOT NULL;

CREATE INDEX idx_limit_rule_configuration
    ON limit_rule (tenant_id, configuration_id)
    WHERE configuration_id IS NOT NULL;

CREATE INDEX idx_limit_rule_channel
    ON limit_rule (tenant_id, channel_id)
    WHERE channel_id IS NOT NULL;

CREATE INDEX idx_limit_rule_channel_group
    ON limit_rule (tenant_id, channel_group_id)
    WHERE channel_group_id IS NOT NULL;

CREATE TYPE limit_change_actor_type AS ENUM (
    'PARTY_PROFILE',
    'BANK_WORKER',
    'SYSTEM'
);

CREATE TYPE limit_rule_change_type AS ENUM (
    'CREATE',
    'UPDATE',
    'DEACTIVATE',
    'REACTIVATE',
    'DELETE'
);

CREATE TYPE limit_rule_change_status AS ENUM (
    'REQUESTED',
    'APPROVED',
    'PENDING_EFFECTIVE_DATE',
    'APPLIED',
    'REJECTED',
    'CANCELLED',
    'SUPERSEDED',
    'FAILED'
);

CREATE TYPE limit_rule_change_event_type AS ENUM (
    'REQUESTED',
    'POLICY_EVALUATED',
    'APPROVED',
    'SCHEDULED',
    'APPLIED',
    'REJECTED',
    'CANCELLED',
    'SUPERSEDED',
    'FAILED'
);

CREATE TYPE limit_change_policy_phase AS ENUM (
    'REQUEST_VALIDATION',
    'AUTHORIZATION',
    'DEFAULTING',
    'SCHEDULING',
    'CONFLICT_RESOLUTION',
    'PRE_APPLY',
    'POST_APPLY'
);

CREATE TYPE limit_change_policy_engine AS ENUM (
    'JSON_LOGIC',
    'CEL',
    'SQL_FUNCTION',
    'EXTERNAL_DECISION'
);

CREATE TYPE limit_change_policy_decision AS ENUM (
    'ALLOW',
    'DENY',
    'MUTATE_REQUEST',
    'SCHEDULE_EFFECTIVE_AT',
    'CANCEL_PENDING',
    'CREATE_DEFAULT',
    'REQUIRE_APPROVAL',
    'NO_OP'
);

CREATE TYPE limit_change_policy_evaluation_outcome AS ENUM (
    'MATCHED',
    'NOT_MATCHED',
    'APPLIED',
    'DENIED',
    'ERROR'
);

CREATE TYPE limit_rule_revision_event_type AS ENUM (
    'CREATED',
    'UPDATED',
    'DEACTIVATED',
    'REACTIVATED',
    'DELETED'
);

CREATE TABLE limit_rule_change_request (
    id UUID PRIMARY KEY DEFAULT uuidv7(),
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    limit_rule_id UUID,
    change_type limit_rule_change_type NOT NULL,
    status limit_rule_change_status NOT NULL DEFAULT 'REQUESTED',
    requested_by_actor_type limit_change_actor_type NOT NULL,
    requested_by_party_profile_id UUID,
    requested_by_bank_worker_id UUID,
    requester_party_profile_id UUID,
    authority_delegation_id UUID,
    request_channel_id UUID,
    target_owner_party_profile_id UUID NOT NULL,
    target_mandate_id UUID,
    target_scope JSONB NOT NULL,
    target_scope_hash VARCHAR(128) NOT NULL,
    before_rule JSONB,
    requested_rule JSONB NOT NULL,
    approved_rule JSONB,
    policy_context JSONB NOT NULL DEFAULT '{}'::jsonb,
    effective_at TIMESTAMPTZ,
    expires_at TIMESTAMPTZ,
    decided_at TIMESTAMPTZ,
    applied_at TIMESTAMPTZ,
    cancelled_at TIMESTAMPTZ,
    superseded_by_change_request_id UUID,
    decision_reason TEXT,
    idempotency_key VARCHAR(255),
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, limit_rule_id)
        REFERENCES limit_rule(tenant_id, id),
    FOREIGN KEY (tenant_id, requested_by_party_profile_id)
        REFERENCES party_profile(tenant_id, id),
    FOREIGN KEY (tenant_id, requested_by_bank_worker_id)
        REFERENCES bank_worker(tenant_id, id),
    FOREIGN KEY (tenant_id, requester_party_profile_id)
        REFERENCES party_profile(tenant_id, id),
    FOREIGN KEY (tenant_id, authority_delegation_id)
        REFERENCES party_profile_delegation(tenant_id, id),
    FOREIGN KEY (tenant_id, request_channel_id)
        REFERENCES channel(tenant_id, id),
    FOREIGN KEY (tenant_id, target_owner_party_profile_id)
        REFERENCES party_profile(tenant_id, id),
    FOREIGN KEY (tenant_id, target_owner_party_profile_id, target_mandate_id)
        REFERENCES mandate(tenant_id, owner_party_profile_id, id),
    FOREIGN KEY (tenant_id, superseded_by_change_request_id)
        REFERENCES limit_rule_change_request(tenant_id, id),
    CONSTRAINT chk_limit_rule_change_request_actor CHECK (
        (
            requested_by_actor_type = 'PARTY_PROFILE' AND
            requested_by_party_profile_id IS NOT NULL AND
            requested_by_bank_worker_id IS NULL
        ) OR
        (
            requested_by_actor_type = 'BANK_WORKER' AND
            requested_by_party_profile_id IS NULL AND
            requested_by_bank_worker_id IS NOT NULL
        ) OR
        (
            requested_by_actor_type = 'SYSTEM' AND
            requested_by_party_profile_id IS NULL AND
            requested_by_bank_worker_id IS NULL
        )
    ),
    CONSTRAINT chk_limit_rule_change_request_channel CHECK (
        requested_by_actor_type = 'SYSTEM' OR request_channel_id IS NOT NULL
    ),
    CONSTRAINT chk_limit_rule_change_request_target_rule CHECK (
        (
            change_type = 'CREATE' AND
            limit_rule_id IS NULL AND
            before_rule IS NULL
        ) OR
        (
            change_type <> 'CREATE' AND
            limit_rule_id IS NOT NULL
        )
    ),
    CONSTRAINT chk_limit_rule_change_request_json CHECK (
        jsonb_typeof(target_scope) = 'object' AND
        (before_rule IS NULL OR jsonb_typeof(before_rule) = 'object') AND
        jsonb_typeof(requested_rule) = 'object' AND
        (approved_rule IS NULL OR jsonb_typeof(approved_rule) = 'object') AND
        jsonb_typeof(policy_context) = 'object' AND
        jsonb_typeof(metadata) = 'object'
    ),
    CONSTRAINT chk_limit_rule_change_request_effective_window CHECK (
        effective_at IS NULL OR expires_at IS NULL OR expires_at > effective_at
    )
);

COMMENT ON TABLE limit_rule_change_request IS
    'Auditable workflow request for creating, changing, scheduling, cancelling, or applying a limit_rule.';

COMMENT ON COLUMN limit_rule_change_request.requested_by_actor_type IS
    'Technical actor that submitted the change: a party profile, a bank worker, or an automated system policy.';

COMMENT ON COLUMN limit_rule_change_request.requester_party_profile_id IS
    'Customer or legal person whose intent the change represents; for worker-assisted changes this is the customer behind the branch or phone request.';

COMMENT ON COLUMN limit_rule_change_request.authority_delegation_id IS
    'Delegation record used when the requester acts for another profile, such as a legal representative changing a company delegate limit.';

COMMENT ON COLUMN limit_rule_change_request.target_scope_hash IS
    'Service-generated stable hash of the logical mandate, channel, operation, kind, period, and currency scope, used by policies to find or block pending changes even before a rule exists.';

COMMENT ON COLUMN limit_rule_change_request.policy_context IS
    'Extra facts supplied to policy evaluation, such as mandate identifiers, funding-source identifiers, IBAN country prefix, channel family, configuration matches, or representative role metadata.';

COMMENT ON COLUMN limit_rule_change_request.request_channel_id IS
    'Channel through which the change was requested; required for party and bank-worker changes, optional for system-generated defaults.';

CREATE UNIQUE INDEX uq_limit_rule_change_request_idempotency
    ON limit_rule_change_request (tenant_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;

CREATE INDEX idx_limit_rule_change_request_status
    ON limit_rule_change_request (tenant_id, status, effective_at, created_at);

CREATE INDEX idx_limit_rule_change_request_pending_target
    ON limit_rule_change_request (
        tenant_id,
        target_scope_hash,
        effective_at,
        created_at
    )
    WHERE status IN (
        'REQUESTED',
        'APPROVED',
        'PENDING_EFFECTIVE_DATE'
    );

CREATE INDEX idx_limit_rule_change_request_limit_rule
    ON limit_rule_change_request (tenant_id, limit_rule_id, created_at)
    WHERE limit_rule_id IS NOT NULL;

CREATE INDEX idx_limit_rule_change_request_party_actor
    ON limit_rule_change_request (
        tenant_id,
        requested_by_party_profile_id,
        created_at
    )
    WHERE requested_by_party_profile_id IS NOT NULL;

CREATE INDEX idx_limit_rule_change_request_worker_actor
    ON limit_rule_change_request (
        tenant_id,
        requested_by_bank_worker_id,
        created_at
    )
    WHERE requested_by_bank_worker_id IS NOT NULL;

CREATE INDEX idx_limit_rule_change_request_requester
    ON limit_rule_change_request (
        tenant_id,
        requester_party_profile_id,
        created_at
    )
    WHERE requester_party_profile_id IS NOT NULL;

CREATE INDEX idx_limit_rule_change_request_authority
    ON limit_rule_change_request (tenant_id, authority_delegation_id, created_at)
    WHERE authority_delegation_id IS NOT NULL;

CREATE INDEX idx_limit_rule_change_request_target_mandate
    ON limit_rule_change_request (tenant_id, target_mandate_id, created_at)
    WHERE target_mandate_id IS NOT NULL;

CREATE INDEX idx_limit_rule_change_request_channel
    ON limit_rule_change_request (tenant_id, request_channel_id, created_at);

-- Limit-change policies are tenant-owned rules evaluated by the limits service
-- while processing a limit_rule_change_request. They are configuration, not
-- history: changing a policy changes how future requests are interpreted.
--
-- Evaluation flow:
-- 1. Select active policies for the request phase, ordered by priority.
-- 2. Use applies_to as cheap pre-filtering hints, for example actor type,
--    channel, operation type, funding-source attributes, or IBAN country.
-- 3. Evaluate condition_expression with the configured engine against the full
--    request context: current limit_rule, requested rule, pending requests,
--    channel, requester, mandate, configuration, delegation, worker, and
--    policy_context facts.
-- 4. Apply action_expression/decision, for example deny the request, mutate the
--    requested rule, set a delayed effective_at, cancel older pending requests,
--    create a default rule from limit_configuration, or require extra approval.
-- 5. Insert one limit_change_policy_evaluation row for auditability, including
--    a policy_snapshot so later policy edits do not rewrite history.
CREATE TABLE limit_change_policy (
    id UUID PRIMARY KEY DEFAULT uuidv7(),
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    code VARCHAR(100) NOT NULL,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    phase limit_change_policy_phase NOT NULL,
    engine limit_change_policy_engine NOT NULL DEFAULT 'JSON_LOGIC',
    decision limit_change_policy_decision NOT NULL DEFAULT 'NO_OP',
    limit_configuration_id UUID,
    priority INT NOT NULL DEFAULT 100,
    is_active BOOLEAN NOT NULL DEFAULT true,
    applies_to JSONB NOT NULL DEFAULT '{}'::jsonb,
    condition_expression JSONB NOT NULL DEFAULT '{}'::jsonb,
    action_expression JSONB NOT NULL DEFAULT '[]'::jsonb,
    denial_code VARCHAR(100),
    denial_message TEXT,
    starts_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    ends_at TIMESTAMPTZ,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, code),
    FOREIGN KEY (tenant_id, limit_configuration_id)
        REFERENCES limit_configuration(tenant_id, id),
    CONSTRAINT chk_limit_change_policy_priority CHECK (priority >= 0),
    CONSTRAINT chk_limit_change_policy_window CHECK (
        ends_at IS NULL OR ends_at > starts_at
    ),
    CONSTRAINT chk_limit_change_policy_json CHECK (
        jsonb_typeof(applies_to) = 'object' AND
        jsonb_typeof(condition_expression) = 'object' AND
        jsonb_typeof(action_expression) = 'array' AND
        jsonb_typeof(metadata) = 'object'
    )
);

COMMENT ON TABLE limit_change_policy IS
    'Tenant-defined policy catalog for limit-rule changes. Policies can require a channel, deny a request, enforce configuration caps, create configuration-backed defaults, delay increases, cancel pending changes, or delegate to external decisions.';

COMMENT ON COLUMN limit_change_policy.limit_configuration_id IS
    'Optional configuration row used by this policy. Policies may also look up the most specific active configuration from request context.';

COMMENT ON COLUMN limit_change_policy.applies_to IS
    'Fast-match scope hints, such as operation type, funding-source category, channel group, actor type, or IBAN country prefix.';

COMMENT ON COLUMN limit_change_policy.condition_expression IS
    'Policy-engine expression evaluated against the change request, current rule, requested rule, pending requests, requester authority, mandate, configuration, and policy_context facts.';

COMMENT ON COLUMN limit_change_policy.action_expression IS
    'Ordered actions emitted when the condition matches, such as REQUIRE_CHANNEL, SET_EFFECTIVE_AT, CANCEL_PENDING, ENFORCE_CONFIGURATION_CAP, or CREATE_DEFAULT_FROM_CONFIGURATION.';

CREATE INDEX idx_limit_change_policy_active
    ON limit_change_policy (tenant_id, phase, is_active, priority);

CREATE INDEX idx_limit_change_policy_configuration
    ON limit_change_policy (tenant_id, limit_configuration_id)
    WHERE limit_configuration_id IS NOT NULL;

CREATE INDEX idx_limit_change_policy_applies_to
    ON limit_change_policy USING GIN (applies_to);

CREATE INDEX idx_limit_change_policy_condition
    ON limit_change_policy USING GIN (condition_expression);

-- Audit record for one policy evaluation against one change request. It shows
-- why policy allowed, denied, delayed, or otherwise changed a request.
CREATE TABLE limit_change_policy_evaluation (
    id UUID PRIMARY KEY DEFAULT uuidv7(),
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    change_request_id UUID NOT NULL,
    policy_id UUID NOT NULL,
    phase limit_change_policy_phase NOT NULL,
    outcome limit_change_policy_evaluation_outcome NOT NULL,
    decision limit_change_policy_decision NOT NULL,
    matched BOOLEAN NOT NULL,
    policy_snapshot JSONB NOT NULL,
    input_context JSONB NOT NULL DEFAULT '{}'::jsonb,
    action_result JSONB NOT NULL DEFAULT '{}'::jsonb,
    message TEXT,
    evaluated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, change_request_id)
        REFERENCES limit_rule_change_request(tenant_id, id)
        ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, policy_id)
        REFERENCES limit_change_policy(tenant_id, id),
    CONSTRAINT chk_limit_change_policy_evaluation_json CHECK (
        jsonb_typeof(policy_snapshot) = 'object' AND
        jsonb_typeof(input_context) = 'object' AND
        jsonb_typeof(action_result) = 'object'
    )
);

CREATE INDEX idx_limit_change_policy_evaluation_request
    ON limit_change_policy_evaluation (
        tenant_id,
        change_request_id,
        evaluated_at
    );

CREATE INDEX idx_limit_change_policy_evaluation_policy
    ON limit_change_policy_evaluation (tenant_id, policy_id, evaluated_at);

-- Workflow event log for a limit_rule_change_request. A request may have many
-- events, including policy evaluations and failed attempts that never produce a
-- limit_rule version, so this holds more than limit_rule_revision does.
CREATE TABLE limit_rule_change_event (
    id UUID PRIMARY KEY DEFAULT uuidv7(),
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    change_request_id UUID NOT NULL,
    event_type limit_rule_change_event_type NOT NULL,
    actor_type limit_change_actor_type NOT NULL DEFAULT 'SYSTEM',
    actor_party_profile_id UUID,
    actor_bank_worker_id UUID,
    policy_id UUID,
    before_status limit_rule_change_status,
    after_status limit_rule_change_status,
    -- Snapshot of the relevant rule/request state before this workflow event.
    -- For policy or status-only events this may be NULL or may contain the
    -- candidate/requested rule rather than a persisted limit_rule row.
    before_rule JSONB,

    -- Snapshot after this workflow event. For example, a scheduling policy may
    -- show the requested rule after effective_at was delayed, while an APPLY
    -- event can show the rule that was written to limit_rule.
    after_rule JSONB,
    event_payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    message TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, change_request_id)
        REFERENCES limit_rule_change_request(tenant_id, id)
        ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, actor_party_profile_id)
        REFERENCES party_profile(tenant_id, id),
    FOREIGN KEY (tenant_id, actor_bank_worker_id)
        REFERENCES bank_worker(tenant_id, id),
    FOREIGN KEY (tenant_id, policy_id)
        REFERENCES limit_change_policy(tenant_id, id),
    CONSTRAINT chk_limit_rule_change_event_actor CHECK (
        (
            actor_type = 'PARTY_PROFILE' AND
            actor_party_profile_id IS NOT NULL AND
            actor_bank_worker_id IS NULL
        ) OR
        (
            actor_type = 'BANK_WORKER' AND
            actor_party_profile_id IS NULL AND
            actor_bank_worker_id IS NOT NULL
        ) OR
        (
            actor_type = 'SYSTEM' AND
            actor_party_profile_id IS NULL AND
            actor_bank_worker_id IS NULL
        )
    ),
    CONSTRAINT chk_limit_rule_change_event_json CHECK (
        (before_rule IS NULL OR jsonb_typeof(before_rule) = 'object') AND
        (after_rule IS NULL OR jsonb_typeof(after_rule) = 'object') AND
        jsonb_typeof(event_payload) = 'object'
    )
);

CREATE INDEX idx_limit_rule_change_event_request
    ON limit_rule_change_event (tenant_id, change_request_id, created_at);

CREATE INDEX idx_limit_rule_change_event_party_actor
    ON limit_rule_change_event (tenant_id, actor_party_profile_id, created_at)
    WHERE actor_party_profile_id IS NOT NULL;

CREATE INDEX idx_limit_rule_change_event_worker_actor
    ON limit_rule_change_event (tenant_id, actor_bank_worker_id, created_at)
    WHERE actor_bank_worker_id IS NOT NULL;

-- Immutable version history for the persisted limit_rule row, with exactly one
-- revision each time a change is applied. Rejected, cancelled, pending, and
-- policy-only steps belong in limit_rule_change_event.
CREATE TABLE limit_rule_revision (
    id UUID PRIMARY KEY DEFAULT uuidv7(),
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    limit_rule_id UUID NOT NULL,
    change_request_id UUID,
    revision_number INT NOT NULL,
    revision_event_type limit_rule_revision_event_type NOT NULL,
    actor_type limit_change_actor_type NOT NULL DEFAULT 'SYSTEM',
    actor_party_profile_id UUID,
    actor_bank_worker_id UUID,
    -- Full JSON snapshot of the persisted limit_rule before this revision.
    -- NULL for CREATED revisions because no prior rule existed.
    before_rule JSONB,

    -- Full JSON snapshot of the persisted limit_rule after this revision.
    -- NULL can represent a hard delete; for soft deletes/deactivation it should
    -- contain the rule with is_active=false or ends_at populated.
    after_rule JSONB,
    effective_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, limit_rule_id, revision_number),
    FOREIGN KEY (tenant_id, limit_rule_id)
        REFERENCES limit_rule(tenant_id, id),
    FOREIGN KEY (tenant_id, change_request_id)
        REFERENCES limit_rule_change_request(tenant_id, id),
    FOREIGN KEY (tenant_id, actor_party_profile_id)
        REFERENCES party_profile(tenant_id, id),
    FOREIGN KEY (tenant_id, actor_bank_worker_id)
        REFERENCES bank_worker(tenant_id, id),
    CONSTRAINT chk_limit_rule_revision_number CHECK (revision_number > 0),
    CONSTRAINT chk_limit_rule_revision_actor CHECK (
        (
            actor_type = 'PARTY_PROFILE' AND
            actor_party_profile_id IS NOT NULL AND
            actor_bank_worker_id IS NULL
        ) OR
        (
            actor_type = 'BANK_WORKER' AND
            actor_party_profile_id IS NULL AND
            actor_bank_worker_id IS NOT NULL
        ) OR
        (
            actor_type = 'SYSTEM' AND
            actor_party_profile_id IS NULL AND
            actor_bank_worker_id IS NULL
        )
    ),
    CONSTRAINT chk_limit_rule_revision_json CHECK (
        before_rule IS NOT NULL OR after_rule IS NOT NULL
    ),
    CONSTRAINT chk_limit_rule_revision_json_types CHECK (
        (before_rule IS NULL OR jsonb_typeof(before_rule) = 'object') AND
        (after_rule IS NULL OR jsonb_typeof(after_rule) = 'object') AND
        jsonb_typeof(metadata) = 'object'
    )
);

COMMENT ON TABLE limit_rule_revision IS
    'Immutable applied-change history for limit_rule, linking each version to the request, actor, and before/after snapshots.';

CREATE INDEX idx_limit_rule_revision_rule
    ON limit_rule_revision (tenant_id, limit_rule_id, revision_number DESC);

CREATE INDEX idx_limit_rule_revision_request
    ON limit_rule_revision (tenant_id, change_request_id)
    WHERE change_request_id IS NOT NULL;

CREATE TYPE limit_usage_event_type AS ENUM (
    'RESERVE',
    'CONSUME',
    'RELEASE',
    'REVERSAL',
    'ADJUSTMENT'
);

-- Derived per-period counters used to enforce periodic limits without scanning
-- all historical transactions during authorization. Transactions and
-- transaction_event rows remain the source of truth; this table is the current
-- usage projection for one matched limit rule, one assignee, one currency, and
-- one window.
--
-- Example: a 40 EUR ATM withdrawal by a child can match both "daily ATM limit"
-- and "monthly all-channels limit". That creates/updates two buckets: one for
-- the daily rule and one for the monthly rule, both pointing back to their own
-- limit_rule_id. If the most specific matched rule is ISOLATED, the evaluator
-- can write only that terminal bucket and skip broader matched buckets.
CREATE TABLE limit_usage_bucket (
    id UUID PRIMARY KEY DEFAULT uuidv7(),
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    limit_rule_id UUID NOT NULL,
    assignee_party_profile_id UUID NOT NULL,
    currency CHAR(3) NOT NULL,
    period_started_at TIMESTAMPTZ NOT NULL,
    period_ended_at TIMESTAMPTZ NOT NULL,
    reserved_amount NUMERIC(20, 4) NOT NULL DEFAULT 0,
    consumed_amount NUMERIC(20, 4) NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id),
    UNIQUE (
        limit_rule_id,
        assignee_party_profile_id,
        currency,
        period_started_at
    ),
    FOREIGN KEY (tenant_id, limit_rule_id)
        REFERENCES limit_rule(tenant_id, id)
        ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, assignee_party_profile_id)
        REFERENCES party_profile(tenant_id, id),
    CONSTRAINT chk_limit_usage_bucket_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT chk_limit_usage_bucket_period CHECK (
        period_ended_at > period_started_at
    ),
    CONSTRAINT chk_limit_usage_bucket_amounts CHECK (
        reserved_amount >= 0 AND consumed_amount >= 0
    )
);

CREATE INDEX idx_limit_usage_bucket_lookup
    ON limit_usage_bucket (
        tenant_id,
        limit_rule_id,
        assignee_party_profile_id,
        period_started_at
);

-- Per-rule audit trail of how a transaction_event changed limit usage. One
-- transaction_event can produce multiple limit_usage_event rows because a
-- transaction may hit several cascading limit rules at once.
--
-- Example: authorization of the same 40 EUR ATM withdrawal can insert two
-- RESERVE rows, one for the daily ATM rule bucket and one for the monthly
-- all-channels rule bucket. If the transaction is later voided, matching
-- RELEASE rows undo those reservations. For an ISOLATED stock-buying rule,
-- authorization can instead create only the stock-operation usage event.
CREATE TABLE limit_usage_event (
    id UUID PRIMARY KEY DEFAULT uuidv7(),
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    limit_rule_id UUID NOT NULL,
    usage_bucket_id UUID,
    transaction_event_id UUID NOT NULL,
    assignee_party_profile_id UUID NOT NULL,
    event_type limit_usage_event_type NOT NULL,
    amount_delta NUMERIC(20, 4) NOT NULL,
    currency CHAR(3) NOT NULL,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (limit_rule_id, transaction_event_id, event_type),
    FOREIGN KEY (tenant_id, limit_rule_id)
        REFERENCES limit_rule(tenant_id, id)
        ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, usage_bucket_id)
        REFERENCES limit_usage_bucket(tenant_id, id)
        ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, transaction_event_id)
        REFERENCES transaction_event(tenant_id, id)
        ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, assignee_party_profile_id)
        REFERENCES party_profile(tenant_id, id),
    CONSTRAINT chk_limit_usage_event_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT chk_limit_usage_event_amount_delta CHECK (amount_delta <> 0)
);

CREATE INDEX idx_limit_usage_event_bucket
    ON limit_usage_event (tenant_id, usage_bucket_id, created_at);

CREATE INDEX idx_limit_usage_event_transaction_event
    ON limit_usage_event (tenant_id, transaction_event_id);
