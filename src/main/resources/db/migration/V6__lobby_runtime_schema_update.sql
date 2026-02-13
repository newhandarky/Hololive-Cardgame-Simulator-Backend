-- V6：補齊 Lobby 與 Action Pipeline 執行所需欄位

-- matches 新增 lobby_status，供 WAITING/READY/STARTED 使用
ALTER TABLE matches
    ADD COLUMN IF NOT EXISTS lobby_status VARCHAR(20) NOT NULL DEFAULT 'WAITING';

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'chk_matches_lobby_status'
    ) THEN
        ALTER TABLE matches
            ADD CONSTRAINT chk_matches_lobby_status
            CHECK (lobby_status IN ('WAITING','READY','STARTED'));
    END IF;
END $$;

-- Lobby 建房時允許先只有房主，玩家二改成可為 NULL
ALTER TABLE matches
    ALTER COLUMN player_b_id DROP NOT NULL;

-- match_players 新增 ready 狀態，並允許先不填正式對戰欄位
ALTER TABLE match_players
    ALTER COLUMN oshi_card_id DROP NOT NULL,
    ALTER COLUMN current_life DROP NOT NULL;

ALTER TABLE match_players
    ADD COLUMN IF NOT EXISTS ready BOOLEAN NOT NULL DEFAULT FALSE;

CREATE INDEX IF NOT EXISTS idx_match_players_match_user
    ON match_players (match_id, user_id);
