CREATE TABLE run_responses(run_id TEXT PRIMARY KEY REFERENCES run_items(id) ON DELETE CASCADE, ciphertext TEXT NOT NULL);
