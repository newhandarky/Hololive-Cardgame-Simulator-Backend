-- V16：卡片變體（平行圖）與使用者顯示偏好
-- 設計說明：
-- 1) cards 仍維持一張卡一筆主資料。
-- 2) card_variants 儲存同一張卡的多個圖片版本。
-- 3) user_card_variant_prefs 讓使用者可為每張卡選擇偏好的顯示圖。

BEGIN;

CREATE TABLE IF NOT EXISTS card_variants (
    id BIGSERIAL PRIMARY KEY,
    card_id VARCHAR(50) NOT NULL REFERENCES cards(card_id) ON DELETE CASCADE,
    variant_code VARCHAR(50) NOT NULL,
    variant_name VARCHAR(255),
    image_url VARCHAR(1024) NOT NULL,
    source_url VARCHAR(1024),
    is_default BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_card_variants_card_code UNIQUE (card_id, variant_code)
);

CREATE INDEX IF NOT EXISTS idx_card_variants_card_id
    ON card_variants(card_id);

-- 一張卡最多只允許一個預設圖
CREATE UNIQUE INDEX IF NOT EXISTS uq_card_variants_default_per_card
    ON card_variants(card_id)
    WHERE is_default = TRUE;

CREATE TABLE IF NOT EXISTS user_card_variant_prefs (
    id BIGSERIAL PRIMARY KEY,
    user_id INT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    card_id VARCHAR(50) NOT NULL REFERENCES cards(card_id) ON DELETE CASCADE,
    variant_id BIGINT NOT NULL REFERENCES card_variants(id) ON DELETE CASCADE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_user_card_variant_pref UNIQUE (user_id, card_id)
);

CREATE INDEX IF NOT EXISTS idx_user_card_variant_prefs_user
    ON user_card_variant_prefs(user_id);

CREATE INDEX IF NOT EXISTS idx_user_card_variant_prefs_card
    ON user_card_variant_prefs(card_id);

-- 將既有 cards.image_url 回填成預設變體，維持向下相容
INSERT INTO card_variants (card_id, variant_code, variant_name, image_url, source_url, is_default)
SELECT
    c.card_id,
    'DEFAULT',
    '預設圖',
    c.image_url,
    c.source_url,
    TRUE
FROM cards c
WHERE c.image_url IS NOT NULL
  AND c.image_url <> ''
ON CONFLICT (card_id, variant_code) DO UPDATE SET
    variant_name = EXCLUDED.variant_name,
    image_url = EXCLUDED.image_url,
    source_url = EXCLUDED.source_url,
    is_default = EXCLUDED.is_default,
    updated_at = CURRENT_TIMESTAMP;

COMMIT;

