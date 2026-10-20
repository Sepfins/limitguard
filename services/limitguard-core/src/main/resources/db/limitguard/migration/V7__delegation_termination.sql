-- A terminated delegation keeps its row and window; it simply stops justifying anything new.
ALTER TABLE party_profile_delegation
    ADD COLUMN is_active BOOLEAN NOT NULL DEFAULT true;

COMMENT ON COLUMN party_profile_delegation.is_active IS
    'False once the delegation is terminated. A terminated delegation authorizes no representation and justifies no mandate.';
