-- Fresh Cloudflare D1 baseline. This deliberately does not import the disposable Ktor SQLite DB.
PRAGMA foreign_keys = ON;

CREATE TABLE users (
  id TEXT PRIMARY KEY,
  github_id INTEGER NOT NULL UNIQUE,
  handle TEXT NOT NULL UNIQUE,
  name TEXT,
  avatar_url TEXT,
  role TEXT NOT NULL DEFAULT 'member',
  status TEXT NOT NULL DEFAULT 'active',
  settings_json TEXT,
  deleted_at TEXT,
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL
);

CREATE TABLE categories (
  id TEXT PRIMARY KEY,
  slug TEXT NOT NULL UNIQUE,
  name TEXT NOT NULL
);

CREATE TABLE bundles (
  id TEXT PRIMARY KEY,
  kind TEXT NOT NULL,
  provenance TEXT NOT NULL,
  owner_user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  source_ref TEXT,
  installation_id INTEGER,
  synced_at TEXT,
  created_at TEXT NOT NULL,
  UNIQUE(kind, provenance)
);

CREATE TABLE skills (
  id TEXT PRIMARY KEY,
  bundle_id TEXT NOT NULL REFERENCES bundles(id) ON DELETE CASCADE,
  slug TEXT NOT NULL UNIQUE,
  source_dir TEXT NOT NULL,
  name TEXT NOT NULL,
  description TEXT NOT NULL,
  license TEXT,
  tags TEXT NOT NULL DEFAULT '[]',
  category_id TEXT REFERENCES categories(id) ON DELETE SET NULL,
  version TEXT NOT NULL,
  version_source TEXT NOT NULL,
  token_upfront INTEGER NOT NULL DEFAULT 0,
  token_ondemand INTEGER NOT NULL DEFAULT 0,
  token_band TEXT NOT NULL DEFAULT '100s',
  verified INTEGER NOT NULL DEFAULT 0,
  status TEXT NOT NULL DEFAULT 'published',
  featured INTEGER NOT NULL DEFAULT 0,
  installs INTEGER NOT NULL DEFAULT 0,
  readme_md TEXT,
  security TEXT,
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL,
  UNIQUE(bundle_id, source_dir)
);

CREATE TABLE skill_files (
  id TEXT PRIMARY KEY,
  skill_id TEXT NOT NULL REFERENCES skills(id) ON DELETE CASCADE,
  path TEXT NOT NULL,
  size INTEGER NOT NULL,
  is_binary INTEGER NOT NULL DEFAULT 0,
  r2_key TEXT NOT NULL,
  UNIQUE(skill_id, path)
);

CREATE TABLE versions (
  id TEXT PRIMARY KEY,
  skill_id TEXT NOT NULL REFERENCES skills(id) ON DELETE CASCADE,
  version TEXT NOT NULL,
  source_ref TEXT NOT NULL,
  r2_zip_key TEXT,
  created_at TEXT NOT NULL,
  UNIQUE(skill_id, version)
);

CREATE TABLE submissions (
  id TEXT PRIMARY KEY,
  bundle_id TEXT REFERENCES bundles(id) ON DELETE SET NULL,
  skill_id TEXT REFERENCES skills(id) ON DELETE SET NULL,
  submitter_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  state TEXT NOT NULL,
  lint_score INTEGER,
  note TEXT,
  payload TEXT,
  revision INTEGER NOT NULL DEFAULT 0,
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL
);

CREATE TABLE stars (
  user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  skill_id TEXT NOT NULL REFERENCES skills(id) ON DELETE CASCADE,
  created_at TEXT NOT NULL,
  PRIMARY KEY(user_id, skill_id)
);

CREATE TABLE sessions (
  id TEXT PRIMARY KEY,
  user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  created_at TEXT NOT NULL,
  expires_at TEXT NOT NULL
);

CREATE TABLE jobs (
  id TEXT PRIMARY KEY,
  type TEXT NOT NULL,
  payload TEXT NOT NULL,
  state TEXT NOT NULL DEFAULT 'queued',
  attempts INTEGER NOT NULL DEFAULT 0,
  run_after TEXT NOT NULL,
  last_error TEXT,
  dedup_key TEXT NOT NULL,
  dispatched_at TEXT,
  lease_until TEXT,
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL,
  UNIQUE(type, dedup_key)
);

CREATE TABLE audit_log (
  id TEXT PRIMARY KEY,
  actor_id TEXT NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
  action TEXT NOT NULL,
  target TEXT NOT NULL,
  meta TEXT,
  created_at TEXT NOT NULL
);

CREATE TABLE platform_settings (
  key TEXT PRIMARY KEY,
  value TEXT NOT NULL
);

CREATE TABLE reports (
  id TEXT PRIMARY KEY,
  skill_id TEXT NOT NULL REFERENCES skills(id) ON DELETE CASCADE,
  reporter_id TEXT REFERENCES users(id) ON DELETE SET NULL,
  reason TEXT NOT NULL,
  created_at TEXT NOT NULL
);

CREATE INDEX ix_users_role_status ON users(role, status);
CREATE INDEX ix_bundles_owner ON bundles(owner_user_id);
CREATE INDEX ix_skills_bundle ON skills(bundle_id);
CREATE INDEX ix_skills_category ON skills(category_id);
CREATE INDEX ix_skills_status_verified ON skills(status, verified);
CREATE INDEX ix_skills_featured ON skills(featured);
CREATE INDEX ix_skills_installs ON skills(installs);
CREATE INDEX ix_skills_updated ON skills(updated_at);
CREATE INDEX ix_skills_created ON skills(created_at);
CREATE INDEX ix_skills_name ON skills(name);
CREATE INDEX ix_skill_files_skill ON skill_files(skill_id);
CREATE INDEX ix_versions_skill ON versions(skill_id);
CREATE INDEX ix_versions_created ON versions(created_at);
CREATE INDEX ix_submissions_submitter ON submissions(submitter_id);
CREATE INDEX ix_submissions_state ON submissions(state);
CREATE INDEX ix_submissions_bundle ON submissions(bundle_id);
CREATE INDEX ix_submissions_skill ON submissions(skill_id);
CREATE INDEX ix_sessions_user ON sessions(user_id);
CREATE INDEX ix_sessions_expires ON sessions(expires_at);
CREATE INDEX ix_jobs_state_runafter ON jobs(state, run_after);
CREATE INDEX ix_audit_created ON audit_log(created_at);
CREATE INDEX ix_audit_action ON audit_log(action);
CREATE INDEX ix_reports_skill ON reports(skill_id);
CREATE INDEX ix_reports_created ON reports(created_at);
