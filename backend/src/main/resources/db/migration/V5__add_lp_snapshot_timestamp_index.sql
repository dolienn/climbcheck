-- Index on timestamp alone: the daily retention cleanup (DELETE ... WHERE timestamp < cutoff)
-- and chart-window queries use the index instead of a seq scan.
-- The composite (player_id, timestamp) index does not serve a condition on timestamp alone.
CREATE INDEX IF NOT EXISTS idx_lp_snapshot_timestamp ON lp_snapshot (timestamp);
