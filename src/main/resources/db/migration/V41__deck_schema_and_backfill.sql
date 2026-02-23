-- V41：新增牌組主檔/明細，並把既有 user_cards 回填為每位玩家的預設牌組

CREATE TABLE IF NOT EXISTS decks (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name VARCHAR(100) NOT NULL,
    format VARCHAR(30) NOT NULL DEFAULT 'STANDARD',
    is_active BOOLEAN NOT NULL DEFAULT FALSE,
    version INT NOT NULL DEFAULT 1,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'uq_decks_user_name'
    ) THEN
        ALTER TABLE decks
            ADD CONSTRAINT uq_decks_user_name UNIQUE (user_id, name);
    END IF;
END $$;

CREATE UNIQUE INDEX IF NOT EXISTS uq_decks_user_active_true
    ON decks (user_id)
    WHERE is_active = TRUE;

CREATE INDEX IF NOT EXISTS idx_decks_user_updated_at
    ON decks (user_id, updated_at DESC);

CREATE TABLE IF NOT EXISTS deck_cards (
    id BIGSERIAL PRIMARY KEY,
    deck_id BIGINT NOT NULL REFERENCES decks(id) ON DELETE CASCADE,
    card_id VARCHAR(50) NOT NULL REFERENCES cards(card_id) ON DELETE RESTRICT,
    count INT NOT NULL CHECK (count > 0),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'uq_deck_cards_deck_card'
    ) THEN
        ALTER TABLE deck_cards
            ADD CONSTRAINT uq_deck_cards_deck_card UNIQUE (deck_id, card_id);
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_deck_cards_deck_id
    ON deck_cards (deck_id);

-- 為尚未建立任何牌組的玩家建立預設牌組（設為 active）。
INSERT INTO decks (user_id, name, format, is_active, version)
SELECT u.id, '預設牌組', 'STANDARD', TRUE, 1
FROM users u
WHERE NOT EXISTS (
    SELECT 1
    FROM decks d
    WHERE d.user_id = u.id
);

-- 若玩家已有牌組但都未啟用，補一個 active 牌組，避免後續開戰找不到出戰牌組。
WITH first_deck AS (
    SELECT DISTINCT ON (d.user_id) d.id, d.user_id
    FROM decks d
    ORDER BY d.user_id, d.id
)
UPDATE decks d
SET is_active = TRUE,
    updated_at = CURRENT_TIMESTAMP
FROM first_deck fd
WHERE d.id = fd.id
  AND NOT EXISTS (
      SELECT 1
      FROM decks d2
      WHERE d2.user_id = fd.user_id
        AND d2.is_active = TRUE
  );

-- 將既有 user_cards 回填到 active deck，讓舊資料可直接延續使用。
INSERT INTO deck_cards (deck_id, card_id, count)
SELECT d.id, uc.card_id, uc.count
FROM user_cards uc
JOIN decks d
  ON d.user_id = uc.user_id
 AND d.is_active = TRUE
WHERE uc.count > 0
ON CONFLICT (deck_id, card_id) DO UPDATE
SET count = EXCLUDED.count,
    updated_at = CURRENT_TIMESTAMP;
