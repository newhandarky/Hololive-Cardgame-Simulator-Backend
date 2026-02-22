-- V8：為官方卡表批次匯入擴充欄位與枚舉

-- 1) cards 補充外部卡號、商品代碼、標籤、來源網址
ALTER TABLE cards
    ADD COLUMN IF NOT EXISTS card_no VARCHAR(50),
    ADD COLUMN IF NOT EXISTS expansion_code VARCHAR(50),
    ADD COLUMN IF NOT EXISTS tags_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN IF NOT EXISTS source_url VARCHAR(1024);

CREATE INDEX IF NOT EXISTS idx_cards_expansion_code ON cards(expansion_code);
CREATE INDEX IF NOT EXISTS idx_cards_tags_json ON cards USING GIN(tags_json);

-- 2) 顏色主檔補上無色（Spot 卡常見）
INSERT INTO colors (code, name)
VALUES ('COLORLESS', '無色')
ON CONFLICT (code) DO NOTHING;

-- 3) member_cards 的 Bloom 等級擴充為官方可見範圍
ALTER TABLE member_cards DROP CONSTRAINT IF EXISTS chk_member_level;
ALTER TABLE member_cards
    ADD CONSTRAINT chk_member_level
    CHECK (level_type IN ('DEBUT', 'FIRST', 'SECOND', 'SPOT', 'BUZZ'));
