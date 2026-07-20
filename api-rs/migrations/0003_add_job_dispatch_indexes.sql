-- Forward-only operational indexes. Kept separate because 0001 may already be applied.
CREATE INDEX IF NOT EXISTS ix_jobs_dispatch_pending ON jobs(run_after) WHERE state = 'queued' AND dispatched_at IS NULL;
CREATE INDEX IF NOT EXISTS ix_jobs_lease_expired ON jobs(lease_until) WHERE state = 'running' AND lease_until IS NOT NULL;
