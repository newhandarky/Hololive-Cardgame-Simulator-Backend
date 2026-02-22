-- V13：清除早期測試用舊卡資料（含 SUP-999）
-- 說明：
-- 1) 這批卡沒有 expansion_code，且規格與官方卡表不一致。
-- 2) 為避免外鍵衝突，先刪除對戰執行中可能殘留的關聯資料，再刪主檔 cards。

BEGIN;

-- 目標卡片清單（B 方案：共 8 張）
-- OSHI-001, OSHI-002, MEM-001, MEM-002, CHE-001, CHE-002, SUP-001, SUP-999

-- 若有使用舊 Cheer 卡的附掛資料，先清掉
DELETE FROM match_holomem_cheers
WHERE cheer_card_id IN ('CHE-001', 'CHE-002');

-- 若有使用舊 Oshi 卡的對戰玩家狀態，先清掉
DELETE FROM match_players
WHERE oshi_card_id IN ('OSHI-001', 'OSHI-002');

-- 若有對戰中的舊卡實例，先清掉（會級聯清除 match_holomems/match_holopower 等）
DELETE FROM match_cards
WHERE card_id IN (
  'OSHI-001', 'OSHI-002',
  'MEM-001', 'MEM-002',
  'CHE-001', 'CHE-002',
  'SUP-001', 'SUP-999'
);

-- 使用者持有舊卡（雖然 cards ON DELETE CASCADE 會處理，先刪可讀性更好）
DELETE FROM user_cards
WHERE card_id IN (
  'OSHI-001', 'OSHI-002',
  'MEM-001', 'MEM-002',
  'CHE-001', 'CHE-002',
  'SUP-001', 'SUP-999'
);

-- 最後刪主卡表，子表（oshi/member/support/cheer）會跟著 cascade
DELETE FROM cards
WHERE card_id IN (
  'OSHI-001', 'OSHI-002',
  'MEM-001', 'MEM-002',
  'CHE-001', 'CHE-002',
  'SUP-001', 'SUP-999'
);

COMMIT;

