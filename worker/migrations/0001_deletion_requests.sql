CREATE TABLE IF NOT EXISTS deletion_requests (
  id TEXT PRIMARY KEY,
  email_hash TEXT NOT NULL,
  encrypted_payload TEXT NOT NULL,
  iv TEXT NOT NULL,
  status TEXT NOT NULL DEFAULT 'pending',
  created_at TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS deletion_requests_email_hash_idx
  ON deletion_requests(email_hash);
