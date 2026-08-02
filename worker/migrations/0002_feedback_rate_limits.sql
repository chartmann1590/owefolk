CREATE TABLE IF NOT EXISTS feedback_rate_limits (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  requester_hash TEXT NOT NULL,
  action TEXT NOT NULL,
  created_at INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_feedback_rate_limits_lookup
ON feedback_rate_limits (requester_hash, action, created_at);
