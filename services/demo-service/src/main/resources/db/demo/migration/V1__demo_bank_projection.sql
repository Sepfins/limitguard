CREATE TABLE demo_scenario (
    id UUID PRIMARY KEY,
    scenario_key VARCHAR(100) NOT NULL UNIQUE,
    status VARCHAR(30) NOT NULL,
    tenant_id UUID,
    customer_party_profile_id UUID,
    owner_party_profile_id UUID,
    authority_delegation_id UUID,
    mandate_id UUID,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT chk_demo_scenario_status CHECK (status IN ('BOOTSTRAPPING', 'READY', 'FAILED'))
);

CREATE TABLE demo_account (
    id UUID PRIMARY KEY,
    scenario_id UUID NOT NULL REFERENCES demo_scenario(id) ON DELETE CASCADE,
    funding_source_id UUID NOT NULL,
    owner_party_profile_id UUID NOT NULL,
    account_type VARCHAR(30) NOT NULL,
    display_name VARCHAR(100) NOT NULL,
    masked_iban VARCHAR(80) NOT NULL,
    access_label VARCHAR(80) NOT NULL,
    currency CHAR(3) NOT NULL,
    opening_balance NUMERIC(19, 4) NOT NULL,
    display_order INT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE (scenario_id, funding_source_id),
    CONSTRAINT chk_demo_account_type CHECK (account_type IN ('CURRENT', 'SAVINGS')),
    CONSTRAINT chk_demo_account_opening_balance CHECK (opening_balance >= 0)
);

CREATE TABLE demo_card (
    id UUID PRIMARY KEY,
    scenario_id UUID NOT NULL REFERENCES demo_scenario(id) ON DELETE CASCADE,
    account_id UUID NOT NULL REFERENCES demo_account(id),
    display_name VARCHAR(100) NOT NULL,
    last_four CHAR(4) NOT NULL,
    expires_month INT NOT NULL,
    expires_year INT NOT NULL,
    status VARCHAR(20) NOT NULL,
    network VARCHAR(30) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT chk_demo_card_last_four CHECK (last_four ~ '^[0-9]{4}$'),
    CONSTRAINT chk_demo_card_expiry_month CHECK (expires_month BETWEEN 1 AND 12),
    CONSTRAINT chk_demo_card_status CHECK (status IN ('ACTIVE', 'INACTIVE'))
);

CREATE TABLE demo_limit_setting (
    id UUID PRIMARY KEY,
    scenario_id UUID NOT NULL REFERENCES demo_scenario(id) ON DELETE CASCADE,
    operation VARCHAR(30) NOT NULL,
    display_name VARCHAR(100) NOT NULL,
    limit_rule_id UUID NOT NULL UNIQUE,
    limit_configuration_id UUID NOT NULL,
    operation_type_id UUID NOT NULL,
    channel_id UUID NOT NULL,
    minimum_amount NUMERIC(19, 4) NOT NULL,
    maximum_amount NUMERIC(19, 4) NOT NULL,
    step_amount NUMERIC(19, 4) NOT NULL,
    agreed_maximum_amount NUMERIC(19, 4),
    pending_change_request_id UUID UNIQUE,
    pending_change_amount NUMERIC(19, 4),
    pending_change_effective_at TIMESTAMPTZ,
    display_order INT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT chk_demo_limit_operation CHECK (operation IN ('CARD_PURCHASE', 'ATM_WITHDRAWAL', 'BANK_TRANSFER')),
    CONSTRAINT chk_demo_limit_amounts CHECK (
        minimum_amount > 0 AND maximum_amount >= minimum_amount AND step_amount > 0
    ),
    CONSTRAINT chk_demo_limit_agreed_maximum CHECK (
        agreed_maximum_amount IS NULL OR agreed_maximum_amount BETWEEN minimum_amount AND maximum_amount
    ),
    CONSTRAINT chk_demo_limit_pending_change CHECK (
        (pending_change_request_id IS NULL) = (pending_change_amount IS NULL)
        AND (pending_change_effective_at IS NULL OR pending_change_request_id IS NOT NULL)
    )
);

CREATE TABLE demo_payment (
    external_payment_id VARCHAR(100) PRIMARY KEY,
    scenario_id UUID NOT NULL REFERENCES demo_scenario(id) ON DELETE CASCADE,
    account_id UUID NOT NULL REFERENCES demo_account(id),
    card_id UUID REFERENCES demo_card(id),
    operation VARCHAR(30) NOT NULL,
    description VARCHAR(160) NOT NULL,
    category VARCHAR(30) NOT NULL,
    requested_amount NUMERIC(19, 4) NOT NULL,
    currency CHAR(3) NOT NULL,
    authorization_idempotency_key VARCHAR(160) NOT NULL,
    processing_state VARCHAR(20) NOT NULL,
    decision VARCHAR(30),
    authorization_decision_id UUID,
    payment_transaction_id UUID,
    transaction_status VARCHAR(30),
    authorized_amount NUMERIC(19, 4) NOT NULL DEFAULT 0,
    captured_amount NUMERIC(19, 4) NOT NULL DEFAULT 0,
    uncaptured_amount NUMERIC(19, 4) NOT NULL DEFAULT 0,
    violations JSONB NOT NULL DEFAULT '[]'::jsonb,
    failure_code VARCHAR(80),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    UNIQUE (scenario_id, authorization_idempotency_key),
    CONSTRAINT chk_demo_payment_operation CHECK (operation IN ('CARD_PURCHASE', 'ATM_WITHDRAWAL', 'BANK_TRANSFER')),
    CONSTRAINT chk_demo_payment_category CHECK (category IN ('GROCERIES', 'DINING', 'TRANSPORT', 'CASH', 'TRANSFER', 'SHOPPING', 'OTHER')),
    CONSTRAINT chk_demo_payment_processing CHECK (processing_state IN ('PROCESSING', 'COMPLETED', 'FAILED')),
    CONSTRAINT chk_demo_payment_amount CHECK (requested_amount > 0),
    CONSTRAINT chk_demo_payment_violations CHECK (jsonb_typeof(violations) = 'array')
);

CREATE INDEX idx_demo_payment_recent
    ON demo_payment (scenario_id, created_at DESC, external_payment_id DESC);

CREATE TABLE demo_payment_mutation (
    id UUID PRIMARY KEY,
    external_payment_id VARCHAR(100) NOT NULL REFERENCES demo_payment(external_payment_id) ON DELETE CASCADE,
    mutation_type VARCHAR(20) NOT NULL,
    idempotency_key VARCHAR(160) NOT NULL,
    requested_amount NUMERIC(19, 4),
    reason VARCHAR(160),
    processing_state VARCHAR(20) NOT NULL,
    result_status VARCHAR(30),
    result_amount NUMERIC(19, 4),
    transaction_event_id UUID,
    authorized_amount NUMERIC(19, 4),
    captured_amount NUMERIC(19, 4),
    uncaptured_amount NUMERIC(19, 4),
    created_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    UNIQUE (external_payment_id, mutation_type, idempotency_key),
    UNIQUE (idempotency_key),
    CONSTRAINT chk_demo_mutation_type CHECK (mutation_type IN ('CAPTURE', 'RELEASE', 'REVERSAL')),
    CONSTRAINT chk_demo_mutation_processing CHECK (processing_state IN ('PROCESSING', 'COMPLETED', 'FAILED')),
    CONSTRAINT chk_demo_mutation_amount CHECK (requested_amount IS NULL OR requested_amount > 0)
);
