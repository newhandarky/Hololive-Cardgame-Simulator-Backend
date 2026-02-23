-- V42：新增對戰牌組快照，確保開戰後牌組內容固定，不受後續編輯影響

CREATE TABLE IF NOT EXISTS match_deck_snapshots (
    id BIGSERIAL PRIMARY KEY,
    match_id BIGINT NOT NULL REFERENCES matches(id) ON DELETE CASCADE,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    deck_id BIGINT REFERENCES decks(id) ON DELETE SET NULL,
    snapshot_json JSONB NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'uq_match_deck_snapshots_match_user'
    ) THEN
        ALTER TABLE match_deck_snapshots
            ADD CONSTRAINT uq_match_deck_snapshots_match_user UNIQUE (match_id, user_id);
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_match_deck_snapshots_match_id
    ON match_deck_snapshots (match_id);
