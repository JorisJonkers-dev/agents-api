-- Adds a per-workspace boot lease that must be held before any
-- scale-down / provision / apply on the runner pod.  Separate from
-- runner_setup_operation, which governs setup-configuration transitions;
-- this tracks pod lifecycle only.
--
-- runner_boot_attempt counts how many boot attempts have been made,
-- incremented on each failure (including stale-lease recovery) so
-- repeated failures are observable without scanning history.
ALTER TABLE workspaces
    ADD COLUMN runner_boot_lease_id   UUID,
    ADD COLUMN runner_boot_attempt    INT         NOT NULL DEFAULT 0,
    ADD COLUMN runner_boot_started_at TIMESTAMPTZ,
    ADD COLUMN runner_boot_updated_at TIMESTAMPTZ;
