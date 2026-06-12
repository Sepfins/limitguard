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
    parent_id UUID REFERENCES funding_source_product(id),
    account_category funding_source_category NOT NULL,
    node_type product_node_type NOT NULL,
    code VARCHAR(100) NOT NULL,
    name VARCHAR(255) NOT NULL,
    is_leaf BOOLEAN NOT NULL DEFAULT false, -- Only leaf nodes can be assigned to funding sources
    is_active BOOLEAN NOT NULL DEFAULT true,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, code)
);

CREATE TABLE funding_source (
    id UUID PRIMARY KEY DEFAULT uuidv7(),
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    type NOT NULL REFERENCES funding_source_product(id),
    external_reference VARCHAR(255),
    is_active BOOLEAN NOT NULL DEFAULT true,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id)
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
CREATE TYPE limit_assignee_scope AS ENUM ('ALL_DELEGATES', 'PARTY_PROFILE');
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
    assignee_scope limit_assignee_scope NOT NULL,
    assignee_party_profile_id UUID,
    funding_scope limit_funding_scope NOT NULL DEFAULT 'ALL_OWNER_SOURCES',
    funding_source_id UUID,
    funding_source_group_id UUID,
    channel_scope limit_channel_scope NOT NULL DEFAULT 'ALL_CHANNELS',
    channel_id UUID,
    channel_group_id UUID,
    operation_type_id UUID REFERENCES operation_type(id),
    priority INT NOT NULL DEFAULT 100,
    is_active BOOLEAN NOT NULL DEFAULT true,
    starts_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    ends_at TIMESTAMPTZ,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, owner_party_profile_id, name),
    FOREIGN KEY (tenant_id, owner_party_profile_id)
        REFERENCES party_profile(tenant_id, id),
    FOREIGN KEY (tenant_id, assignee_party_profile_id)
        REFERENCES party_profile(tenant_id, id),
    FOREIGN KEY (tenant_id, owner_party_profile_id, funding_source_id)
        REFERENCES funding_source_owner(tenant_id, party_profile_id, funding_source_id),
    FOREIGN KEY (tenant_id, owner_party_profile_id, funding_source_group_id)
        REFERENCES funding_source_group(tenant_id, owner_party_profile_id, id),
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
    CONSTRAINT chk_limit_rule_assignee_scope CHECK (
        (
            assignee_scope = 'ALL_DELEGATES' AND
            assignee_party_profile_id IS NULL
        ) OR
        (
            assignee_scope = 'PARTY_PROFILE' AND
            assignee_party_profile_id IS NOT NULL
        )
    ),
    CONSTRAINT chk_limit_rule_funding_scope CHECK (
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
    CONSTRAINT chk_limit_rule_priority CHECK (priority >= 0),
    CONSTRAINT chk_limit_rule_effective_window CHECK (
        ends_at IS NULL OR ends_at > starts_at
    )
);

COMMENT ON TABLE limit_rule IS
    'A limit rule combines actor assignment, funding-source scope, channel scope, operation type, amount, and period.';

COMMENT ON COLUMN limit_rule.owner_party_profile_id IS
    'The party profile that owns the funding sources governed by this rule.';

COMMENT ON COLUMN limit_rule.assignee_scope IS
    'ALL_DELEGATES applies to active delegates of the owner profile; PARTY_PROFILE applies to one acting profile.';

CREATE INDEX idx_limit_rule_active_owner
    ON limit_rule (tenant_id, owner_party_profile_id, is_active, starts_at, ends_at);

CREATE INDEX idx_limit_rule_assignee
    ON limit_rule (tenant_id, assignee_party_profile_id)
    WHERE assignee_party_profile_id IS NOT NULL;

CREATE INDEX idx_limit_rule_funding_source
    ON limit_rule (tenant_id, funding_source_id)
    WHERE funding_source_id IS NOT NULL;

CREATE INDEX idx_limit_rule_funding_source_group
    ON limit_rule (tenant_id, funding_source_group_id)
    WHERE funding_source_group_id IS NOT NULL;

CREATE INDEX idx_limit_rule_channel
    ON limit_rule (tenant_id, channel_id)
    WHERE channel_id IS NOT NULL;

CREATE INDEX idx_limit_rule_channel_group
    ON limit_rule (tenant_id, channel_group_id)
    WHERE channel_group_id IS NOT NULL;

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
-- limit_rule_id.
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
-- transaction may hit several limit rules at once.
--
-- Example: authorization of the same 40 EUR ATM withdrawal can insert two
-- RESERVE rows, one for the daily ATM rule bucket and one for the monthly
-- all-channels rule bucket. If the transaction is later voided, matching
-- RELEASE rows undo those reservations.
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