-- V50：holomem 附加型 support（マスコット/ツール/ファン）落地表

CREATE TABLE IF NOT EXISTS match_holomem_supports (
    id SERIAL PRIMARY KEY,
    match_holomem_id INT NOT NULL REFERENCES match_holomems(id) ON DELETE CASCADE,
    match_card_id INT NOT NULL REFERENCES match_cards(id) ON DELETE CASCADE,
    support_card_id VARCHAR(50) NOT NULL REFERENCES support_cards(card_id) ON DELETE CASCADE,
    support_type VARCHAR(20) NOT NULL
        CONSTRAINT chk_match_holomem_support_type CHECK (support_type IN ('MASCOT', 'TOOL', 'FAN', 'OTHER')),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (match_card_id)
);

CREATE INDEX IF NOT EXISTS idx_match_holomem_supports_holomem
    ON match_holomem_supports(match_holomem_id, support_type);

-- 向後相容：若舊資料曾把 SUPPORT 誤放進 holomem stack，回填為 OTHER 類型附加。
INSERT INTO match_holomem_supports (match_holomem_id, match_card_id, support_card_id, support_type)
SELECT s.match_holomem_id, s.match_card_id, mc.card_id, 'OTHER'
FROM match_holomem_stack_cards s
JOIN match_cards mc ON mc.id = s.match_card_id
JOIN cards c ON c.card_id = mc.card_id
LEFT JOIN match_holomem_supports hs ON hs.match_card_id = s.match_card_id
WHERE c.card_type = 'SUPPORT'
  AND hs.id IS NULL;
