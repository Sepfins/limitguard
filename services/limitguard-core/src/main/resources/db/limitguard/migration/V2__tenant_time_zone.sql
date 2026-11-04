-- The bank's zone decides where daily and monthly usage windows start. Tenants registered
-- before this migration keep UTC, the zone their existing usage buckets were opened in.
ALTER TABLE tenant ADD COLUMN time_zone TEXT;
UPDATE tenant SET time_zone = 'UTC';
ALTER TABLE tenant ALTER COLUMN time_zone SET NOT NULL;

COMMENT ON COLUMN tenant.time_zone IS
    'IANA zone of the bank. Daily and monthly usage windows start at midnight here. Fixed at registration: changing it would move window boundaries under existing usage buckets.';
