CREATE TABLE request_templates(id TEXT PRIMARY KEY, platform_id TEXT NOT NULL REFERENCES platforms(id) ON DELETE CASCADE, name TEXT NOT NULL, rules_json TEXT NOT NULL, version INTEGER NOT NULL DEFAULT 1);
CREATE INDEX request_templates_platform ON request_templates(platform_id);
