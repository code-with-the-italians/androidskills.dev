"""Fresh-schema checks runnable locally and in CI without a Cloudflare account."""

import sqlite3
from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]
MIGRATIONS = ROOT / "migrations"
NOW = "2026-07-21T00:00:00Z"


class BaselineSchemaTest(unittest.TestCase):
    def setUp(self):
        self.db = sqlite3.connect(":memory:")
        self.db.execute("PRAGMA foreign_keys = ON")
        for migration in sorted(MIGRATIONS.glob("*.sql")):
            self.db.executescript(migration.read_text(encoding="utf-8"))

    def tearDown(self):
        self.db.close()

    def test_expected_tables_and_seed_are_present(self):
        tables = {
            row[0]
            for row in self.db.execute("SELECT name FROM sqlite_master WHERE type = 'table'")
        }
        self.assertEqual(
            tables,
            {
                "users", "categories", "bundles", "skills", "skill_files", "versions",
                "submissions", "stars", "sessions", "jobs", "audit_log",
                "platform_settings", "reports",
            },
        )
        self.assertEqual(self.db.execute("SELECT COUNT(*) FROM categories").fetchone()[0], 11)
        self.assertEqual(self.db.execute("SELECT value FROM platform_settings").fetchone()[0],
                         '{"reviewPolicy":"manual","tokenSoftCap":1000,"llmEnabled":false}')

    def test_d1_operational_deltas_are_explicit(self):
        columns = {
            row[1]: row for row in self.db.execute("PRAGMA table_info(skills)")
        }
        self.assertEqual(columns["source_dir"][3], 1)  # non-null on a fresh D1 database
        jobs = {row[1] for row in self.db.execute("PRAGMA table_info(jobs)")}
        self.assertTrue({"dedup_key", "dispatched_at", "lease_until"} <= jobs)
        submission = {row[1]: row for row in self.db.execute("PRAGMA table_info(submissions)")}
        self.assertEqual(submission["revision"][3], 1)
        self.assertEqual(submission["revision"][4], "0")

    def test_constraints_and_foreign_key_actions(self):
        self.db.execute("INSERT INTO users VALUES (?, 1, 'owner', NULL, NULL, 'member', 'active', NULL, NULL, ?, ?)",
                        ("owner", NOW, NOW))
        self.db.execute("INSERT INTO users VALUES (?, 2, 'reporter', NULL, NULL, 'member', 'active', NULL, NULL, ?, ?)",
                        ("reporter", NOW, NOW))
        category_id = self.db.execute("SELECT id FROM categories LIMIT 1").fetchone()[0]
        self.db.execute("INSERT INTO bundles VALUES ('bundle', 'repo', 'o/r', 'owner', NULL, NULL, NULL, ?)", (NOW,))
        self.db.execute(
            "INSERT INTO skills(id,bundle_id,slug,source_dir,name,description,category_id,version,version_source,created_at,updated_at) "
            "VALUES ('skill','bundle','slug','skills/slug','Name','Description',?,'1.0.0','manifest',?,?)",
            (category_id, NOW, NOW),
        )
        self.db.execute("INSERT INTO skill_files VALUES ('file','skill','SKILL.md',1,0,'skills/skill/SKILL.md')")
        self.db.execute("INSERT INTO versions VALUES ('version','skill','1.0.0','sha',NULL,?)", (NOW,))
        self.db.execute("INSERT INTO reports VALUES ('report','skill','reporter','reason',?)", (NOW,))
        self.db.execute("INSERT INTO submissions(id,bundle_id,skill_id,submitter_id,state,created_at,updated_at) VALUES ('submission','bundle','skill','owner','draft',?,?)", (NOW, NOW))
        self.db.execute("INSERT INTO jobs(id,type,payload,run_after,dedup_key,created_at,updated_at) VALUES ('job','review','{}',?,'skill:sha',?,?)", (NOW, NOW, NOW))
        with self.assertRaises(sqlite3.IntegrityError):
            self.db.execute("INSERT INTO jobs(id,type,payload,run_after,dedup_key,created_at,updated_at) VALUES ('job2','review','{}',?,'skill:sha',?,?)", (NOW, NOW, NOW))

        self.db.execute("DELETE FROM categories WHERE id = ?", (category_id,))
        self.assertIsNone(self.db.execute("SELECT category_id FROM skills").fetchone()[0])
        self.db.execute("DELETE FROM users WHERE id = 'reporter'")
        self.assertIsNone(self.db.execute("SELECT reporter_id FROM reports").fetchone()[0])
        self.db.execute("DELETE FROM bundles WHERE id = 'bundle'")
        self.assertEqual(self.db.execute("SELECT COUNT(*) FROM skills").fetchone()[0], 0)
        self.assertEqual(self.db.execute("SELECT COUNT(*) FROM skill_files").fetchone()[0], 0)
        self.assertEqual(self.db.execute("SELECT COUNT(*) FROM versions").fetchone()[0], 0)
        self.assertEqual(self.db.execute("SELECT bundle_id,skill_id FROM submissions").fetchone(), (None, None))

        self.db.execute("INSERT INTO audit_log VALUES ('audit','owner','user.update','user:owner',NULL,?)", (NOW,))
        with self.assertRaises(sqlite3.IntegrityError):
            self.db.execute("DELETE FROM users WHERE id = 'owner'")


if __name__ == "__main__":
    unittest.main()
