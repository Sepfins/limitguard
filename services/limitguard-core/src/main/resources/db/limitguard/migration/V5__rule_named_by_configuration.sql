-- A granted rule takes its name from its configuration, so an owner-wide rule and a
-- mandate-narrowed rule on one configuration, or a rule re-created after deletion, share it.

ALTER TABLE limit_rule DROP CONSTRAINT limit_rule_tenant_id_owner_party_profile_id_name_key;
