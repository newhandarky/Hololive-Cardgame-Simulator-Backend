-- V46：支援 HOLMEM BLOOM 疊牌與回合限制追蹤

ALTER TABLE match_holomems
    ADD COLUMN IF NOT EXISTS entered_turn_number INT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS last_bloom_turn INT;

ALTER TABLE match_holomems DROP CONSTRAINT IF EXISTS chk_holomem_current_level;
ALTER TABLE match_holomems
    ADD CONSTRAINT chk_holomem_current_level
    CHECK (current_level IN ('DEBUT', 'FIRST', 'SECOND', 'SPOT', 'BUZZ'));

CREATE TABLE IF NOT EXISTS match_holomem_stack_cards (
    id SERIAL PRIMARY KEY,
    match_holomem_id INT NOT NULL REFERENCES match_holomems(id) ON DELETE CASCADE,
    match_card_id INT NOT NULL REFERENCES match_cards(id) ON DELETE CASCADE,
    stack_order INT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (match_holomem_id, match_card_id),
    UNIQUE (match_card_id)
);

CREATE INDEX IF NOT EXISTS idx_match_holomem_stack_cards_holomem
    ON match_holomem_stack_cards(match_holomem_id, stack_order);

-- 既有對戰資料回填：把目前每個 holomem 的 top 卡當作初始堆疊第 1 層。
INSERT INTO match_holomem_stack_cards (match_holomem_id, match_card_id, stack_order)
SELECT h.id, h.match_card_id, 1
FROM match_holomems h
LEFT JOIN match_holomem_stack_cards s
    ON s.match_holomem_id = h.id
   AND s.match_card_id = h.match_card_id
WHERE s.id IS NULL;
