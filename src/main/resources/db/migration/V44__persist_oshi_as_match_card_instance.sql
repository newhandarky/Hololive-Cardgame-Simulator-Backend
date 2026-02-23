-- V44：將 OSHI 正式落地成 match_cards 實例，提供真實 cardInstanceId

ALTER TABLE match_cards
    DROP CONSTRAINT IF EXISTS chk_match_card_zone;

ALTER TABLE match_cards
    ADD CONSTRAINT chk_match_card_zone
    CHECK (zone IN ('OSHI','DECK','HAND','CHEER_DECK','HOLOPOWER','STAGE','ARCHIVE','LIFE'));

-- 補齊既有對戰資料：若玩家已有 oshi_card_id 但尚未有 OSHI 實例，補一筆實例紀錄。
INSERT INTO match_cards (match_id, owner_user_id, card_id, zone, order_index, is_face_down)
SELECT
    mp.match_id,
    mp.user_id,
    mp.oshi_card_id,
    'OSHI',
    1,
    FALSE
FROM match_players mp
LEFT JOIN match_cards mc
    ON mc.match_id = mp.match_id
   AND mc.owner_user_id = mp.user_id
   AND mc.zone = 'OSHI'
WHERE mp.oshi_card_id IS NOT NULL
  AND mc.id IS NULL;
